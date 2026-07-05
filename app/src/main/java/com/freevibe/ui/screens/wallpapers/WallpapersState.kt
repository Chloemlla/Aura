package com.freevibe.ui.screens.wallpapers

import com.freevibe.data.model.Wallpaper

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
)

enum class WallpaperTab {
    DISCOVER,
    PEXELS,
    PIXABAY,
    REDDIT,
    WALLHAVEN,
    COMMUNITY,
    COLOR,
    SEARCH,
}
