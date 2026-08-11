package com.chloemlla.aura.ui.screens.wallpapers

import com.chloemlla.aura.data.model.Wallpaper

@androidx.compose.runtime.Immutable
data class WallpapersUiState(
    val wallpapers: List<Wallpaper> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val errorSource: String? = null,
    val query: String = "",
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val selectedTab: WallpaperTab = WallpaperTab.DISCOVER,
    val isApplying: Boolean = false,
    val applySuccess: String? = null,
    val pendingLiveWallpaperLaunch: Boolean = false,
    val selectedColor: String? = null,
    val topRange: String = "1M",
    val discoverFilter: WallpaperDiscoverFilter = WallpaperDiscoverFilter.FOR_YOU,
    val browseTab: WallpaperTab = WallpaperTab.DISCOVER,
    val isUploadingWallpaper: Boolean = false,
    val wallpaperUploadProgress: Float = 0f,
    val degradedSources: Set<String> = emptySet(),
    // True when a searchable non-Reddit source (Wallhaven or Pixabay) is enabled. The Newest and
    // Categories tabs are backed by search, so they only work with those providers; in the default
    // Reddit-only mode this is false and the Newest/Categories/Collections tabs are hidden.
    val extendedBrowseSourcesEnabled: Boolean = false,
)

enum class WallpaperTab {
    DISCOVER,
    NEWEST,
    REDDIT,
    WALLHAVEN,
    PEXELS,
    PIXABAY,
    COMMUNITY,
    COLOR,
    SEARCH,
}
