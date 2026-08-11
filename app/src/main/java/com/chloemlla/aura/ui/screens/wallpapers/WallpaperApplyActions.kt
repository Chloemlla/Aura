package com.chloemlla.aura.ui.screens.wallpapers

import android.content.Context
import android.content.res.Configuration
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperTarget
import com.chloemlla.aura.data.model.favoriteIdentity
import com.chloemlla.aura.data.model.isSourceUnavailable
import com.chloemlla.aura.data.model.sourceUnavailableReasonForFailure
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.data.remote.toFavoriteEntity
import com.chloemlla.aura.data.repository.AiWallpaperRepository
import com.chloemlla.aura.data.repository.FavoritesRepository
import com.chloemlla.aura.service.ApplyFeedbackBus
import com.chloemlla.aura.service.ApplyFeedbackEvent
import com.chloemlla.aura.service.DownloadManager
import com.chloemlla.aura.service.DualWallpaperService
import com.chloemlla.aura.service.OfflineFavoritesManager
import com.chloemlla.aura.service.WallpaperApplyCoordinator
import com.chloemlla.aura.service.WallpaperApplyPolicy
import com.chloemlla.aura.service.WallpaperApplier
import com.chloemlla.aura.service.WallpaperHistoryManager
import com.chloemlla.aura.service.WallpaperStyleLearningSignal
import com.chloemlla.aura.service.shouldUseNightWallpaperVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

internal class WallpaperApplyActions(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val wallpaperApplier: WallpaperApplier,
    private val downloadManager: DownloadManager,
    private val dualWallpaperService: DualWallpaperService,
    private val historyManager: WallpaperHistoryManager,
    private val favoritesRepo: FavoritesRepository,
    private val offlineFavorites: OfflineFavoritesManager,
    private val aiWallpaperRepository: AiWallpaperRepository,
    private val applyFeedbackBus: ApplyFeedbackBus,
    private val applyCoordinator: WallpaperApplyCoordinator,
    private val state: MutableStateFlow<WallpapersUiState>,
    private val scope: CoroutineScope,
    private val onStyleSignal: suspend (Wallpaper, WallpaperStyleLearningSignal) -> Unit = { _, _ -> },
) {

    val activeDownloads = downloadManager.activeDownloads

    fun applyWallpaper(wallpaper: Wallpaper, target: WallpaperTarget) {
        scope.launch {
            state.update { it.copy(isApplying = true, applySuccess = null) }
            // History, undo, night-variant, style learning, and feedback all commit
            // in the coordinator so this path cannot drift from the editor/crop/AI
            // ones. Feedback goes through the global bus only - also setting
            // applySuccess stacks a second snackbar (seen on-device).
            applyCoordinator.apply(
                wallpaper = wallpaper,
                target = target,
                policy = WallpaperApplyPolicy.BROWSE,
                onStyleSignal = { onStyleSignal(it, WallpaperStyleLearningSignal.APPLIED) },
            ) {
                wallpaperApplier.applyFromUrl(
                    wallpaper.fullUrl,
                    target,
                    nightVariant = shouldApplyNightVariant(),
                )
            }
                .onSuccess {
                    clearSourceUnavailableAfterSuccess(wallpaper)
                    state.update { it.copy(isApplying = false) }
                }
                .onFailure { e ->
                    markSourceUnavailableIfRemoved(wallpaper, e)
                    state.update { it.copy(isApplying = false, error = e.message) }
                }
        }
    }

    fun undoApply(entry: com.chloemlla.aura.data.model.WallpaperHistoryEntity) {
        scope.launch {
            state.update { it.copy(isApplying = true) }
            val target = runCatching { WallpaperTarget.valueOf(entry.target) }
                .getOrDefault(WallpaperTarget.BOTH)
            wallpaperApplier.applyFromUrl(
                entry.fullUrl,
                target,
                nightVariant = shouldApplyNightVariant(),
            )
                .onSuccess {
                    prefs.setLastNightVariantWallpaper(entry.fullUrl, target.name)
                    // Bus-only feedback — see applyWallpaper.
                    state.update { it.copy(isApplying = false) }
                    applyFeedbackBus.post(
                        ApplyFeedbackEvent(
                            message = context.getString(R.string.apply_feedback_reverted),
                            undoTarget = null,
                        ),
                    )
                }
                .onFailure { e ->
                    state.update {
                        it.copy(
                            isApplying = false,
                            error = context.getString(
                                R.string.apply_feedback_undo_failed,
                                e.message ?: context.getString(R.string.apply_feedback_unknown_error),
                            ),
                        )
                    }
                }
        }
    }

    fun applySplitCrop(wallpaper: Wallpaper) {
        scope.launch {
            state.update { it.copy(isApplying = true, applySuccess = null) }
            // Split crop keeps its own success copy but commits through the same
            // coordinator, so it records history and can be undone like any apply.
            applyCoordinator.apply(
                wallpaper = wallpaper,
                target = WallpaperTarget.BOTH,
                policy = WallpaperApplyPolicy.BROWSE.copy(postFeedback = false),
                onStyleSignal = { onStyleSignal(it, WallpaperStyleLearningSignal.APPLIED) },
            ) { dualWallpaperService.applySplitCrop(wallpaper) }
                .onSuccess {
                    state.update {
                        it.copy(
                            isApplying = false,
                            applySuccess = context.getString(R.string.wallpaper_feedback_split_crop_applied),
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(isApplying = false, error = e.message) }
                }
        }
    }

    fun applyParallax(wallpaper: Wallpaper) {
        scope.launch {
            state.update { it.copy(isApplying = true, applySuccess = null) }
            val ext = guessImageExtension(wallpaper.fileType, wallpaper.fullUrl)
            wallpaperApplier.prepareParallaxWallpaper(wallpaper.fullUrl, "parallax_wp.$ext")
                .onSuccess {
                    onStyleSignal(wallpaper, WallpaperStyleLearningSignal.APPLIED)
                    state.update { it.copy(isApplying = false, pendingLiveWallpaperLaunch = true) }
                }
                .onFailure { e ->
                    state.update { it.copy(isApplying = false, error = e.message) }
                }
        }
    }

    fun clearPendingLaunch() = state.update { it.copy(pendingLiveWallpaperLaunch = false) }

    private suspend fun shouldApplyNightVariant(): Boolean = shouldUseNightWallpaperVariant(
        enabled = prefs.autoWallpaperNightVariantEnabled.first(),
        schedulerEnabled = prefs.schedulerEnabled.first(),
        schedulerMode = prefs.schedulerDayNightMode.first(),
        hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        dayStartHour = prefs.schedulerDayStartHour.first(),
        nightStartHour = prefs.schedulerNightStartHour.first(),
        isSystemDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
    )

    fun downloadWallpaper(wallpaper: Wallpaper) {
        scope.launch {
            val ext = guessImageExtension(wallpaper.fileType, wallpaper.fullUrl)
            downloadManager.downloadWallpaper(
                id = wallpaper.stableKey(),
                url = wallpaper.fullUrl,
                fileName = buildWallpaperDownloadFileName(wallpaper, ext),
                source = wallpaper.source.name,
            ).onSuccess {
                clearSourceUnavailableAfterSuccess(wallpaper)
            }.onFailure { error ->
                markSourceUnavailableIfRemoved(wallpaper, error)
                state.update { it.copy(error = error.message) }
            }
        }
    }

    fun dismissDownload(id: String) {
        downloadManager.clearCompleted(id)
    }

    fun toggleFavorite(wallpaper: Wallpaper) {
        scope.launch {
            val entity = wallpaper.toFavoriteEntity()
            val isFav = favoritesRepo.isFavorite(wallpaper.favoriteIdentity()).first()
            favoritesRepo.toggle(entity, isFav)
            if (!isFav) {
                onStyleSignal(wallpaper, WallpaperStyleLearningSignal.FAVORITED)
                offlineFavorites.cacheOffline(entity, wallpaper.fullUrl)
            } else {
                onStyleSignal(wallpaper, WallpaperStyleLearningSignal.UNFAVORITED)
                offlineFavorites.removeOffline(entity)
                if (wallpaper.source == ContentSource.AI_GENERATED) {
                    aiWallpaperRepository.deleteGeneratedWallpaper(wallpaper.fullUrl)
                    if (wallpaper.thumbnailUrl != wallpaper.fullUrl) {
                        aiWallpaperRepository.deleteGeneratedWallpaper(wallpaper.thumbnailUrl)
                    }
                }
            }
            state.update {
                it.copy(
                    applySuccess = context.getString(
                        if (isFav) {
                            R.string.wallpaper_feedback_favorite_removed
                        } else {
                            R.string.wallpaper_feedback_favorite_added
                        },
                    ),
                )
            }
        }
    }

    fun isFavorite(wallpaper: Wallpaper): Flow<Boolean> = favoritesRepo.isFavorite(wallpaper.favoriteIdentity())

    /**
     * Persist an unavailable state only when the remote item is genuinely gone.
     * A 403, a throttle, or a timeout is about this attempt, not the item, and
     * must never permanently disable a saved wallpaper.
     */
    private suspend fun markSourceUnavailableIfRemoved(wallpaper: Wallpaper, failure: Throwable) {
        sourceUnavailableReasonForFailure(wallpaper.source, failure)?.let { reason ->
            favoritesRepo.markSourceUnavailable(wallpaper.favoriteIdentity(), reason)
        }
    }

    /**
     * A successful fetch is proof the source works again, so any previously
     * recorded unavailable state is cleared.
     */
    private suspend fun clearSourceUnavailableAfterSuccess(wallpaper: Wallpaper) {
        if (!wallpaper.isSourceUnavailable()) return
        favoritesRepo.clearSourceUnavailable(wallpaper.favoriteIdentity())
    }
}

internal fun guessImageExtension(fileType: String, url: String): String {
    if (fileType.isNotBlank()) {
        return when {
            fileType.contains("png", true) -> "png"
            fileType.contains("webp", true) -> "webp"
            fileType.contains("gif", true) -> "gif"
            else -> "jpg"
        }
    }
    val path = url.substringBefore("?").substringBefore("#").lowercase(java.util.Locale.ROOT)
    return when {
        path.endsWith(".png") -> "png"
        path.endsWith(".webp") -> "webp"
        path.endsWith(".gif") -> "gif"
        else -> "jpg"
    }
}
