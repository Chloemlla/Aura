package com.freevibe.ui.screens.wallpapers

import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.local.WallpaperCacheManager
import com.freevibe.data.model.COMMUNITY_GUIDELINES_REQUIRED_MESSAGE
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.stableKey
import com.freevibe.data.repository.RedditRepository
import com.freevibe.data.repository.VoteRepository
import com.freevibe.data.repository.WallpaperRepository
import com.freevibe.data.repository.WallpaperUploadRepository
import com.freevibe.service.SourceMetrics
import com.freevibe.service.WallpaperStyleLearningProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class WallpaperBrowseViewModel(
    private val wallpaperRepo: WallpaperRepository,
    private val redditRepo: RedditRepository,
    private val prefs: PreferencesManager,
    private val cacheManager: WallpaperCacheManager,
    private val voteRepo: VoteRepository,
    private val wallpaperUploadRepo: WallpaperUploadRepository,
    private val sourceMetrics: SourceMetrics,
    private val wallhavenProviderEnabled: StateFlow<Boolean>,
    private val pexelsProviderEnabled: StateFlow<Boolean>,
    private val pixabayProviderEnabled: StateFlow<Boolean>,
    private val communityProviderEnabled: StateFlow<Boolean>,
    private val communityGuidelinesAccepted: StateFlow<Boolean>,
    private val state: MutableStateFlow<WallpapersUiState>,
    private val topVoted: MutableStateFlow<List<Pair<Wallpaper, Int>>>,
    private val dailyPick: MutableStateFlow<Wallpaper?>,
    private val scope: CoroutineScope,
) {
    private var loadJob: Job? = null

    fun start() {
        fetchDailyPick()
        fetchTopVoted()
        scope.launch {
            sourceMetrics.version.collect {
                state.update { s -> s.copy(degradedSources = sourceMetrics.degradedSources()) }
            }
        }
    }

    fun cancel() {
        loadJob?.cancel()
    }

    fun fetchTopVoted(seedWallpapers: List<Wallpaper> = emptyList()) {
        scope.launch {
            try {
                if (!isCommunityProviderEnabled()) {
                    sourceMetrics.recordDisabled(SOURCE_COMMUNITY)
                    topVoted.value = emptyList()
                    return@launch
                }
                val topIds = withTimeoutOrNull(5000L) { voteRepo.getTopVotedIds(50) } ?: return@launch
                if (com.freevibe.BuildConfig.DEBUG) android.util.Log.d(
                    "WallpapersVM",
                    "Top voted IDs from Firebase: ${topIds.size} entries, first=${topIds.firstOrNull()}",
                )
                if (topIds.isEmpty()) return@launch

                val allIds = topIds.flatMap { (voteKey, _) -> extractWallpaperLookupIds(voteKey) }.distinct()
                val cachedWallpapers = cacheManager.getByIds(allIds)
                val resolvedIds = (seedWallpapers + cachedWallpapers).map { it.id }.toSet()
                val missingCommunityKeys = allIds
                    .filter { it.startsWith("cw_") && it !in resolvedIds }
                    .map { it.removePrefix("cw_") }
                    .toSet()
                val remoteWallpapers = if (missingCommunityKeys.isNotEmpty()) {
                    wallpaperUploadRepo.fetchWallpapersByKeys(missingCommunityKeys)
                } else {
                    emptyList()
                }
                val wallpapers = (seedWallpapers + cachedWallpapers + remoteWallpapers).distinctBy { it.stableKey() }
                if (com.freevibe.BuildConfig.DEBUG) android.util.Log.d(
                    "WallpapersVM",
                    "Resolved ${wallpapers.size} wallpapers (${remoteWallpapers.size} hydrated from RTDB) for ${allIds.size} ID variants",
                )

                val voteMap = topIds.toMap()
                val ambiguousLegacyIds = wallpapers
                    .groupBy { it.id }
                    .filterValues { matches -> matches.size > 1 }
                    .keys
                val sorted = wallpapers
                    .mapNotNull { wallpaper ->
                        resolveWallpaperVoteCount(
                            wallpaper = wallpaper,
                            voteMap = voteMap,
                            ambiguousLegacyIds = ambiguousLegacyIds,
                            sanitizeKey = voteRepo::sanitizeKey,
                        )?.let { wallpaper to it }
                    }
                    .distinctBy { it.first.stableKey() }
                    .sortedByDescending { it.second }
                if (com.freevibe.BuildConfig.DEBUG) android.util.Log.d(
                    "WallpapersVM",
                    "Final top voted: ${sorted.size} wallpapers, top=${sorted.firstOrNull()?.let { "${it.first.id}=${it.second}" }}",
                )
                topVoted.value = sorted
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (com.freevibe.BuildConfig.DEBUG) {
                    android.util.Log.e("WallpapersVM", "fetchTopVoted failed: ${e.message}", e)
                }
            }
        }
    }

    fun loadWallpapers(loadMore: Boolean = false, isRefresh: Boolean = false) {
        if (!loadMore) loadJob?.cancel()
        loadJob = scope.launch {
            val s = state.value
            if (!isRefresh && !loadMore) {
                state.update { it.copy(isLoading = true, error = null, errorSource = null) }
            } else if (loadMore) {
                state.update { it.copy(isLoadingMore = true) }
            }

            if (s.selectedTab == WallpaperTab.DISCOVER && !loadMore && !isRefresh) {
                val cached = wallpaperRepo.getCachedDiscover(s.currentPage)
                val visibleCached = cached
                    ?.filter { it.source != ContentSource.WALLHAVEN || wallhavenProviderEnabled.value }
                    .orEmpty()
                if (visibleCached.isNotEmpty()) {
                    val preferredResolution = prefs.preferredResolution.first()
                    val userStyles = loadUserStyles()
                    val styleLearningProfile = loadStyleLearningProfile()
                    val rankedCached = rankWallpapers(
                        wallpapers = visibleCached,
                        filter = state.value.discoverFilter,
                        preferredResolution = preferredResolution,
                        userStyles = userStyles,
                        styleLearningProfile = styleLearningProfile,
                    )
                    state.update {
                        it.copy(
                            wallpapers = rankedCached,
                            hasMore = true,
                        )
                    }
                }
            }

            val currentTab = state.value.selectedTab
            val currentPage = state.value.currentPage
            try {
                val userStyles = loadUserStyles()
                val styleLearningProfile = loadStyleLearningProfile()
                if (currentTab == WallpaperTab.REDDIT && !isRedditProviderEnabled()) {
                    redditRepo.getMultiSubreddit()
                    state.update {
                        it.copy(
                            wallpapers = emptyList(),
                            isLoading = false,
                            isLoadingMore = false,
                            isRefreshing = false,
                            hasMore = false,
                            error = redditDisabledMessage(),
                            errorSource = WallpaperTab.REDDIT.name,
                        )
                    }
                    return@launch
                }
                if (isProviderDisabledTab(currentTab)) {
                    recordDisabledProvider(currentTab)
                    state.update {
                        it.copy(
                            wallpapers = emptyList(),
                            isLoading = false,
                            isLoadingMore = false,
                            isRefreshing = false,
                            hasMore = false,
                            error = providerDisabledMessage(currentTab),
                            errorSource = currentTab.name,
                        )
                    }
                    return@launch
                }
                val result = when (currentTab) {
                    WallpaperTab.DISCOVER -> wallpaperRepo.getDiscover(
                        page = currentPage,
                        userStyles = userStyles,
                    )
                    WallpaperTab.PIXABAY -> wallpaperRepo.getPixabay(currentPage)
                    WallpaperTab.PEXELS -> wallpaperRepo.getPexelsCurated(currentPage)
                    WallpaperTab.REDDIT -> redditRepo.getMultiSubreddit()
                    WallpaperTab.WALLHAVEN -> wallpaperRepo.getWallhaven(page = currentPage, topRange = state.value.topRange)
                    WallpaperTab.COMMUNITY -> wallpaperUploadRepo.getCommunityWallpapers()
                    WallpaperTab.SEARCH -> wallpaperRepo.searchAll(state.value.query, page = currentPage)
                    WallpaperTab.COLOR -> wallpaperRepo.searchByColor(state.value.selectedColor ?: "", currentPage)
                }
                val preferredResolution = prefs.preferredResolution.first()
                val activeFilter = if (currentTab == WallpaperTab.DISCOVER) {
                    state.value.discoverFilter
                } else {
                    WallpaperDiscoverFilter.FOR_YOU
                }
                val combined = if (loadMore) state.value.wallpapers + result.items else result.items
                val rankedWallpapers = if (currentTab == WallpaperTab.COMMUNITY) {
                    combined.distinctBy { it.stableKey() }
                } else {
                    rankWallpapers(
                        wallpapers = combined,
                        filter = activeFilter,
                        preferredResolution = preferredResolution,
                        userStyles = userStyles,
                        styleLearningProfile = styleLearningProfile,
                    )
                }
                val preserveExistingDiscoverFeed =
                    currentTab == WallpaperTab.DISCOVER &&
                        !loadMore &&
                        result.items.isEmpty() &&
                        state.value.wallpapers.isNotEmpty()
                state.update {
                    it.copy(
                        wallpapers = if (preserveExistingDiscoverFeed) it.wallpapers else rankedWallpapers,
                        isLoading = false,
                        isLoadingMore = false,
                        isRefreshing = false,
                        hasMore = result.hasMore,
                        error = null,
                        errorSource = null,
                    )
                }
                if (currentTab == WallpaperTab.DISCOVER && (!loadMore || topVoted.value.isEmpty())) {
                    fetchTopVoted(result.items)
                }
                if (currentTab == WallpaperTab.COMMUNITY && result.items.isNotEmpty()) {
                    cacheManager.cache("community_wallpapers_$currentPage", result.items)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        isRefreshing = false,
                        error = categorizeError(e),
                        errorSource = currentTab.name,
                    )
                }
            }
        }
    }

    fun isProviderDisabledTab(tab: WallpaperTab): Boolean = when (tab) {
        WallpaperTab.WALLHAVEN -> !wallhavenProviderEnabled.value
        WallpaperTab.REDDIT -> true
        WallpaperTab.PEXELS -> !pexelsProviderEnabled.value
        WallpaperTab.PIXABAY -> !pixabayProviderEnabled.value
        WallpaperTab.COMMUNITY -> !communityProviderEnabled.value || !communityGuidelinesAccepted.value
        else -> false
    }

    private fun fetchDailyPick() {
        scope.launch {
            try {
                dailyPick.value = withTimeoutOrNull(5000L) { wallpaperRepo.getWallpaperOfTheDay() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private fun categorizeError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Connection timed out - try again"
        is java.net.ConnectException -> "Could not connect to server"
        is retrofit2.HttpException -> when (e.code()) {
            401, 403 -> "API key invalid or expired"
            404 -> "Content not found"
            429 -> "Rate limited - wait a moment and retry"
            in 500..599 -> "Server error - try again later"
            else -> "Service temporarily unavailable"
        }
        else -> e.message ?: "Failed to load wallpapers"
    }

    suspend fun loadUserStyles(): List<String> =
        prefs.userStyles.first()
            .split(",")
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotBlank() }

    suspend fun loadStyleLearningProfile(): WallpaperStyleLearningProfile =
        WallpaperStyleLearningProfile.parse(prefs.wallpaperStyleLearningJson.first())

    private suspend fun isRedditProviderEnabled(): Boolean = false

    private suspend fun isCommunityProviderEnabled(): Boolean =
        prefs.communityProviderEnabled.first() && prefs.communityGuidelinesAccepted.first()

    private fun recordDisabledProvider(tab: WallpaperTab) {
        val source = when (tab) {
            WallpaperTab.WALLHAVEN -> SOURCE_WALLHAVEN
            WallpaperTab.PEXELS -> "pexels"
            WallpaperTab.PIXABAY -> "pixabay"
            WallpaperTab.COMMUNITY -> SOURCE_COMMUNITY
            else -> return
        }
        sourceMetrics.recordDisabled(source)
    }

    private fun providerDisabledMessage(tab: WallpaperTab): String = when (tab) {
        WallpaperTab.WALLHAVEN -> wallhavenDisabledMessage()
        WallpaperTab.PEXELS -> "Pexels source is disabled in Settings"
        WallpaperTab.PIXABAY -> "Pixabay source is disabled in Settings"
        WallpaperTab.COMMUNITY -> communityDisabledMessage()
        else -> redditDisabledMessage()
    }

    private fun communityDisabledMessage(): String =
        if (!communityProviderEnabled.value) {
            "Community source is disabled in Settings"
        } else {
            COMMUNITY_GUIDELINES_REQUIRED_MESSAGE
        }

    private fun redditDisabledMessage(): String =
        "Reddit source is discontinued. Saved Reddit items keep their metadata, but new Reddit feeds are off."

    private fun wallhavenDisabledMessage(): String = "Wallhaven source is disabled in Settings"

    private companion object {
        const val SOURCE_WALLHAVEN = "wallhaven"
        const val SOURCE_COMMUNITY = "community"
    }
}
