package com.freevibe.ui.screens.wallpapers

import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.local.WallpaperCacheManager
import com.freevibe.data.model.stableKey
import com.freevibe.data.repository.RedditRepository
import com.freevibe.service.WallpaperStyleLearningProfile
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WallpaperRedditRetryCoordinator(
    private val redditRepo: RedditRepository,
    private val prefs: PreferencesManager,
    private val cacheManager: WallpaperCacheManager,
    private val state: MutableStateFlow<WallpapersUiState>,
    private val scope: CoroutineScope,
    private val categorizeError: (Exception) -> String,
) {
    private var retryJob: Job? = null
    private var discoverSecondaryPage = 0
    private var discoverSecondaryHasMore = false

    fun cancel() {
        retryJob?.cancel()
    }

    fun recordDiscoverSecondaryPage(page: Int, hasMore: Boolean) {
        discoverSecondaryPage = page
        discoverSecondaryHasMore = hasMore
    }

    fun schedule(delayMs: Long, expectedTab: WallpaperTab, expectedPage: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            var nextDelayMs = delayMs
            while (true) {
                delay(nextDelayMs.coerceAtLeast(250L) + 100L)
                val current = state.value
                if (
                    current.selectedTab != expectedTab ||
                    current.currentPage != expectedPage ||
                    !current.hasMore ||
                    current.isLoading ||
                    current.isLoadingMore
                ) {
                    break
                }
                if (!retryPage(expectedTab, expectedPage)) break
                nextDelayMs = redditRepo.retryDelayMs()
            }
        }
    }

    private suspend fun retryPage(expectedTab: WallpaperTab, expectedPage: Int): Boolean {
        return try {
            val redditResult = redditRepo.getMultiSubreddit(page = expectedPage)
            val retryAgain = redditResult.hasMore &&
                (redditResult.items.isEmpty() || redditRepo.hasDeferredRequest())
            val snapshot = state.value
            if (snapshot.selectedTab != expectedTab || snapshot.currentPage != expectedPage) return false

            val rankedWallpapers = if (redditResult.items.isEmpty()) {
                snapshot.wallpapers
            } else {
                rankWallpapers(
                    wallpapers = (snapshot.wallpapers + redditResult.items).distinctBy { it.stableKey() },
                    filter = if (expectedTab == WallpaperTab.DISCOVER) {
                        snapshot.discoverFilter
                    } else {
                        WallpaperDiscoverFilter.FOR_YOU
                    },
                    preferredResolution = prefs.preferredResolution.first(),
                    userStyles = prefs.userStyles.first()
                        .split(',')
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotBlank() },
                    styleLearningProfile = WallpaperStyleLearningProfile.parse(
                        prefs.wallpaperStyleLearningJson.first(),
                    ),
                )
            }
            state.update { current ->
                if (current.selectedTab != expectedTab || current.currentPage != expectedPage) {
                    current
                } else {
                    current.copy(
                        wallpapers = rankedWallpapers,
                        hasMore = if (expectedTab == WallpaperTab.DISCOVER) {
                            redditResult.hasMore ||
                                (discoverSecondaryPage == expectedPage && discoverSecondaryHasMore)
                        } else {
                            redditResult.hasMore
                        },
                        error = null,
                        errorSource = null,
                    )
                }
            }
            cacheDiscoverRetry(expectedTab, expectedPage, redditResult.items.isNotEmpty())
            retryAgain
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val current = state.value
            if (
                current.selectedTab == expectedTab &&
                current.currentPage == expectedPage &&
                current.wallpapers.isEmpty()
            ) {
                state.update { it.copy(error = categorizeError(error), errorSource = expectedTab.name) }
            }
            false
        }
    }

    private suspend fun cacheDiscoverRetry(tab: WallpaperTab, page: Int, hasNewItems: Boolean) {
        if (tab != WallpaperTab.DISCOVER || !hasNewItems) return
        val current = state.value
        if (current.selectedTab != tab || current.currentPage != page) return
        try {
            cacheManager.cache("discover_home_v2_$page", current.wallpapers)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }
}
