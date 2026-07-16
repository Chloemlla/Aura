package com.freevibe.ui.screens.wallpapers

import android.content.Context
import android.content.res.Configuration
import com.freevibe.R
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.favoriteIdentity
import com.freevibe.data.model.sourceUnavailableReasonForFailure
import com.freevibe.data.model.stableKey
import com.freevibe.data.remote.toFavoriteEntity
import com.freevibe.data.repository.AiWallpaperRepository
import com.freevibe.data.repository.FavoritesRepository
import com.freevibe.service.ApplyFeedbackBus
import com.freevibe.service.ApplyFeedbackEvent
import com.freevibe.service.DownloadManager
import com.freevibe.service.DualWallpaperService
import com.freevibe.service.OfflineFavoritesManager
import com.freevibe.service.WallpaperApplier
import com.freevibe.service.WallpaperHistoryManager
import com.freevibe.service.WallpaperStyleLearningSignal
import com.freevibe.service.shouldUseNightWallpaperVariant
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
    private val state: MutableStateFlow<WallpapersUiState>,
    private val scope: CoroutineScope,
    private val onStyleSignal: suspend (Wallpaper, WallpaperStyleLearningSignal) -> Unit = { _, _ -> },
) {

    val activeDownloads = downloadManager.activeDownloads

    fun applyWallpaper(wallpaper: Wallpaper, target: WallpaperTarget) {
        scope.launch {
            state.update { it.copy(isApplying = true, applySuccess = null) }
            wallpaperApplier.applyFromUrl(
                wallpaper.fullUrl,
                target,
                nightVariant = shouldApplyNightVariant(),
            )
                .onSuccess {
                    onStyleSignal(wallpaper, WallpaperStyleLearningSignal.APPLIED)
                    historyManager.record(wallpaper, target)
                    prefs.setLastNightVariantWallpaper(wallpaper.fullUrl, target.name)
                    val undoTarget = historyManager.previousSnapshot()
                    val labelRes = when (target) {
                        WallpaperTarget.HOME -> R.string.apply_target_home
                        WallpaperTarget.LOCK -> R.string.apply_target_lock
                        WallpaperTarget.BOTH -> R.string.apply_target_both
                    }
                    // Feedback goes through the global bus only — setting applySuccess
                    // too stacks a second snackbar on top of the bus one (seen on-device).
                    state.update { it.copy(isApplying = false) }
                    applyFeedbackBus.post(
                        ApplyFeedbackEvent(
                            message = context.getString(
                                R.string.apply_feedback_applied_to,
                                context.getString(labelRes),
                            ),
                            undoTarget = undoTarget,
                        )
                    )
                }
                .onFailure { e ->
                    markSourceUnavailableIfRemoved(wallpaper, e)
                    state.update { it.copy(isApplying = false, error = e.message) }
                }
        }
    }

    fun undoApply(entry: com.freevibe.data.model.WallpaperHistoryEntity) {
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
            dualWallpaperService.applySplitCrop(wallpaper)
                .onSuccess {
                    onStyleSignal(wallpaper, WallpaperStyleLearningSignal.APPLIED)
                    historyManager.record(wallpaper, WallpaperTarget.BOTH)
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
            ).onFailure { error ->
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

    private suspend fun markSourceUnavailableIfRemoved(wallpaper: Wallpaper, failure: Throwable) {
        sourceUnavailableReasonForFailure(wallpaper.source, failure)?.let { reason ->
            favoritesRepo.markSourceUnavailable(wallpaper.favoriteIdentity(), reason)
        }
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
