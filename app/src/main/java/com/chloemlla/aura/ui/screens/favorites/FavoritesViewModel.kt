package com.chloemlla.aura.ui.screens.favorites

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.FavoriteEntity
import com.chloemlla.aura.data.model.favoriteIdentity
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.data.remote.toWallpaper
import com.chloemlla.aura.data.remote.toSound
import com.chloemlla.aura.data.repository.AiWallpaperRepository
import com.chloemlla.aura.data.repository.FavoritesRepository
import com.chloemlla.aura.service.BatchDownloadService
import com.chloemlla.aura.service.FavoritesExporter
import com.chloemlla.aura.service.SelectedContentHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoritesRepo: FavoritesRepository,
    private val exporter: FavoritesExporter,
    private val selectedContent: SelectedContentHolder,
    private val batchDownloadService: BatchDownloadService,
    private val aiWallpaperRepository: AiWallpaperRepository,
) : ViewModel() {
    val wallpapers = favoritesRepo.getWallpapers().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val sounds = favoritesRepo.getSounds().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun removeFavorite(entity: FavoriteEntity) = viewModelScope.launch { favoritesRepo.remove(entity.favoriteIdentity()) }
    fun restoreFavorite(entity: FavoriteEntity) = viewModelScope.launch { favoritesRepo.add(entity) }
    fun deleteGeneratedWallpaperFiles(entities: List<FavoriteEntity>) = viewModelScope.launch {
        entities
            .filter { it.type.equals("WALLPAPER", ignoreCase = true) }
            .filter { it.source.equals(ContentSource.AI_GENERATED.name, ignoreCase = true) }
            .forEach { entity ->
                aiWallpaperRepository.deleteGeneratedWallpaper(entity.fullUrl)
                if (entity.thumbnailUrl != entity.fullUrl) {
                    aiWallpaperRepository.deleteGeneratedWallpaper(entity.thumbnailUrl)
                }
            }
    }
    fun markSourceUnavailable(entity: FavoriteEntity, reason: String? = null) = viewModelScope.launch {
        favoritesRepo.markSourceUnavailable(entity.favoriteIdentity(), reason)
    }
    fun clearSourceUnavailable(entity: FavoriteEntity) = viewModelScope.launch {
        favoritesRepo.clearSourceUnavailable(entity.favoriteIdentity())
    }

    /** Convert FavoriteEntity to domain Wallpaper and populate shared holder with the visible list */
    fun selectWallpaper(fav: FavoriteEntity, visibleWallpapers: List<FavoriteEntity>) {
        selectedContent.selectWallpaper(
            fav.toWallpaper(),
            visibleWallpapers.map { it.toWallpaper() },
        )
    }

    /** Convert FavoriteEntity to domain Sound and populate shared holder */
    fun selectSound(fav: FavoriteEntity) {
        selectedContent.selectSound(fav.toSound())
    }

    fun exportFavorites(uri: Uri) = viewModelScope.launch {
        exporter.export(uri)
            .onSuccess { count ->
                _message.update {
                    context.resources.getQuantityString(R.plurals.favorites_exported, count, count)
                }
            }
            .onFailure { e ->
                _message.update {
                    context.getString(
                        R.string.favorites_export_failed,
                        e.message ?: context.getString(R.string.common_retry_later),
                    )
                }
            }
    }

    fun importFavorites(uri: Uri) = viewModelScope.launch {
        exporter.import(uri)
            .onSuccess { count ->
                _message.update {
                    context.resources.getQuantityString(R.plurals.favorites_imported, count, count)
                }
            }
            .onFailure { e ->
                _message.update {
                    context.getString(
                        R.string.favorites_import_failed,
                        e.message ?: context.getString(R.string.common_retry_later),
                    )
                }
            }
    }

    val batchState = batchDownloadService.state

    fun downloadAllWallpapers() {
        val wps = wallpapers.value.map { it.toWallpaper() }
        if (wps.isEmpty()) return
        val result = batchDownloadService.downloadBatch(wps)
        _message.update { batchDownloadMessage(result, context) }
    }

    // -- Bulk actions (selection mode) ---------------------------------------

    /** Remove every favorite whose stableKey is in [keys]. Returns the count deleted. */
    fun bulkDelete(keys: Set<String>) = viewModelScope.launch {
        if (keys.isEmpty()) return@launch
        val w = wallpapers.value.filter { it.stableKey() in keys }
        val s = sounds.value.filter { it.stableKey() in keys }
        (w + s).forEach { favoritesRepo.remove(it.favoriteIdentity()) }
        val total = w.size + s.size
        if (total > 0) {
            _message.update {
                context.resources.getQuantityString(R.plurals.favorites_removed, total, total)
            }
        }
    }

    /** Kick off a batch download for every selected wallpaper favorite. Sounds are ignored. */
    fun bulkDownload(keys: Set<String>) {
        if (keys.isEmpty()) return
        val wps = wallpapers.value.filter { it.stableKey() in keys }.map { it.toWallpaper() }
        if (wps.isEmpty()) {
            _message.update { context.getString(R.string.favorites_select_wallpaper) }
            return
        }
        val result = batchDownloadService.downloadBatch(wps)
        _message.update { batchDownloadMessage(result, context) }
    }

    fun clearMessage() { _message.update { _ -> null } }
}

private fun batchDownloadMessage(
    result: com.chloemlla.aura.service.BatchDownloadStartResult,
    context: Context,
): String = when {
    result.alreadyRunning -> context.getString(R.string.favorites_batch_already_running)
    result.acceptedCount == 0 && result.blockedCount > 0 ->
        context.resources.getQuantityString(
            R.plurals.favorites_batch_provider_blocked,
            result.blockedCount,
            result.blockedCount,
        )
    result.blockedCount > 0 ->
        context.resources.getQuantityString(
            R.plurals.favorites_batch_downloading_with_blocked,
            result.acceptedCount,
            result.acceptedCount,
            result.blockedCount,
        )
    else ->
        context.resources.getQuantityString(
            R.plurals.favorites_batch_downloading,
            result.acceptedCount,
            result.acceptedCount,
        )
}
