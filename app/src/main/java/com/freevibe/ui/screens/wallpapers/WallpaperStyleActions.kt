package com.freevibe.ui.screens.wallpapers

import android.content.Context
import com.freevibe.R
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.stableKey
import com.freevibe.service.WallpaperStyleLearningProfile
import com.freevibe.service.WallpaperStyleLearningSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the local wallpaper style-learning profile: signal recording, reset, and
 * Discover re-ranking. Signal writes are serialized behind a [Mutex] because apply,
 * favorite, and skip signals arrive from independent coroutines whose DataStore
 * read-modify-write would otherwise interleave and drop signals.
 */
internal class WallpaperStyleActions(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val browse: WallpaperBrowseViewModel,
    private val state: MutableStateFlow<WallpapersUiState>,
    private val scope: CoroutineScope,
) {
    private val styleLearningMutex = Mutex()

    fun skipWallpaper(wallpaper: Wallpaper) {
        scope.launch {
            recordSignal(wallpaper, WallpaperStyleLearningSignal.SKIPPED)
            state.update { s ->
                s.copy(
                    wallpapers = s.wallpapers.filterNot { it.stableKey() == wallpaper.stableKey() },
                    applySuccess = context.getString(R.string.wallpaper_feedback_hidden),
                )
            }
        }
    }

    fun resetStyleLearning() {
        scope.launch {
            prefs.clearWallpaperStyleLearning()
            rerankCurrentDiscoverFeed(WallpaperStyleLearningProfile.EMPTY)
        }
    }

    fun setDiscoverFilter(filter: WallpaperDiscoverFilter) {
        scope.launch {
            val preferredResolution = prefs.preferredResolution.first()
            val userStyles = browse.loadUserStyles()
            val styleLearningProfile = browse.loadStyleLearningProfile()
            val ranked = rankWallpapers(
                wallpapers = state.value.wallpapers,
                filter = filter,
                preferredResolution = preferredResolution,
                userStyles = userStyles,
                styleLearningProfile = styleLearningProfile,
            )
            state.update {
                it.copy(
                    discoverFilter = filter,
                    wallpapers = ranked,
                )
            }
        }
    }

    suspend fun recordSignal(
        wallpaper: Wallpaper,
        signal: WallpaperStyleLearningSignal,
    ) {
        val next = styleLearningMutex.withLock {
            val current = WallpaperStyleLearningProfile.parse(prefs.wallpaperStyleLearningJson.first())
            val updated = current.record(wallpaper, signal)
            prefs.setWallpaperStyleLearningJson(WallpaperStyleLearningProfile.serialize(updated))
            updated
        }
        rerankCurrentDiscoverFeed(next)
    }

    private suspend fun rerankCurrentDiscoverFeed(profile: WallpaperStyleLearningProfile) {
        val current = state.value
        if (current.selectedTab != WallpaperTab.DISCOVER || current.wallpapers.isEmpty()) return
        val preferredResolution = prefs.preferredResolution.first()
        val userStyles = browse.loadUserStyles()
        val ranked = rankWallpapers(
            wallpapers = current.wallpapers,
            filter = current.discoverFilter,
            preferredResolution = preferredResolution,
            userStyles = userStyles,
            styleLearningProfile = profile,
        )
        state.update { it.copy(wallpapers = ranked) }
    }
}
