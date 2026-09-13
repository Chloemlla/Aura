package com.chloemlla.aura.ui.screens.wallpapers

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.repository.RedditRepository
import com.chloemlla.aura.data.repository.SearchHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WallpaperFilterNavigation(
    private val state: MutableStateFlow<WallpapersUiState>,
    private val browse: WallpaperBrowseViewModel,
    private val searchActions: WallpaperSearchActions,
    private val redditRepo: RedditRepository,
    private val searchHistoryRepo: SearchHistoryRepository,
    private val scope: CoroutineScope,
) {

    private var lastRouteQuery: String? = null
    private var lastRouteColor: String? = null
    private var lastRouteSimilarId: String? = null
    private var lastRouteSimilarSource: String? = null
    private var lastRouteSimilarFullUrl: String? = null
    private var hasInitiallyLoaded = false

    fun handle(
        query: String?,
        color: String?,
        similarId: String? = null,
        similarSource: String? = null,
        similarFullUrl: String? = null,
    ) {
        val normalizedQuery = query?.ifBlank { null }
        val normalizedColor = color?.ifBlank { null }
        val normalizedSimilarId = similarId?.ifBlank { null }
        val normalizedSimilarSource = similarSource?.ifBlank { null }
        val normalizedSimilarFullUrl = similarFullUrl?.ifBlank { null }

        // Skip dedup only for non-initial calls with identical filters
        if (
            hasInitiallyLoaded &&
            normalizedQuery == lastRouteQuery &&
            normalizedColor == lastRouteColor &&
            normalizedSimilarId == lastRouteSimilarId &&
            normalizedSimilarSource == lastRouteSimilarSource &&
            normalizedSimilarFullUrl == lastRouteSimilarFullUrl
        ) return

        lastRouteQuery = normalizedQuery
        lastRouteColor = normalizedColor
        lastRouteSimilarId = normalizedSimilarId
        lastRouteSimilarSource = normalizedSimilarSource
        lastRouteSimilarFullUrl = normalizedSimilarFullUrl
        hasInitiallyLoaded = true

        val resolvedSimilarSource = normalizedSimilarSource?.let { sourceName ->
            runCatching { ContentSource.valueOf(sourceName) }.getOrNull()
        }

        when {
            normalizedQuery != null -> {
                if (state.value.selectedTab != WallpaperTab.SEARCH || state.value.query != normalizedQuery) {
                    search(normalizedQuery)
                }
            }
            normalizedColor != null -> {
                if (state.value.selectedTab != WallpaperTab.COLOR || state.value.selectedColor != normalizedColor) {
                    searchByColor(normalizedColor)
                }
            }
            normalizedSimilarId != null -> searchActions.findSimilarById(
                wallpaperId = normalizedSimilarId,
                source = resolvedSimilarSource,
                fullUrl = normalizedSimilarFullUrl,
            )
            state.value.wallpapers.isEmpty() && !state.value.isLoading -> browse.loadWallpapers()
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            clearActiveFilter()
            return
        }
        val returnTab = state.value.selectedTab
            .takeIf { it != WallpaperTab.SEARCH && it != WallpaperTab.COLOR }
            ?: state.value.browseTab
        searchActions.cancel()
        state.update {
            it.copy(
                query = query,
                selectedTab = WallpaperTab.SEARCH,
                browseTab = returnTab,
                selectedColor = null,
                wallpapers = emptyList(),
                currentPage = 1,
                hasMore = true,
            )
        }
        scope.launch { searchHistoryRepo.addWallpaperSearch(query) }
        browse.loadWallpapers()
    }

    fun removeSearch(query: String) {
        scope.launch { searchHistoryRepo.removeSearch(query, "WALLPAPER") }
    }

    fun clearSearchHistory() {
        scope.launch { searchHistoryRepo.clearWallpaperHistory() }
    }

    // #9: Color-based search
    fun searchByColor(color: String) {
        if (color.isBlank()) {
            clearActiveFilter()
            return
        }
        searchActions.searchByColor(color)
    }

    fun clearActiveFilter() {
        val returnTab = if (browse.isProviderDisabledTab(state.value.browseTab)) {
            WallpaperTab.DISCOVER
        } else {
            state.value.browseTab
        }
        if (returnTab == WallpaperTab.REDDIT) redditRepo.resetPagination()
        searchActions.cancel()
        state.update {
            it.copy(
                selectedTab = returnTab,
                query = "",
                selectedColor = null,
                wallpapers = emptyList(),
                currentPage = 1,
                hasMore = true,
                error = null,
                errorSource = null,
                isLoading = false,
                isLoadingMore = false,
                isRefreshing = false,
            )
        }
        browse.loadWallpapers()
    }
}
