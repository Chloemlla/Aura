package com.chloemlla.aura.ui.screens.wallpapers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.SearchResult
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.service.WallpaperStyleLearningProfile
import com.chloemlla.aura.service.WallpaperStyleLearningSignal
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperFeedQualityTest {

    private val resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `rankWallpapers keeps stronger duplicate instead of first duplicate`() {
        val weaker = wallpaper(
            id = "px_1",
            source = ContentSource.PEXELS,
            url = "https://example.com/shared.jpg",
            width = 1440,
            height = 2560,
            tags = listOf("photo"),
        )
        val stronger = wallpaper(
            id = "wh_1",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/shared.jpg",
            width = 2160,
            height = 3840,
            tags = listOf("amoled", "minimal"),
            favorites = 900,
        )

        val ranked = rankWallpapers(
            wallpapers = listOf(weaker, stronger),
            filter = WallpaperDiscoverFilter.FOR_YOU,
        )

        assertEquals(1, ranked.size)
        assertEquals("wh_1", ranked.first().id)
    }

    @Test
    fun `amoled filter keeps dark candidates`() {
        val dark = wallpaper(
            id = "dark",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/dark.jpg",
            width = 1440,
            height = 3200,
            tags = listOf("amoled", "black"),
            colors = listOf("#000000"),
        )
        val bright = wallpaper(
            id = "bright",
            source = ContentSource.PEXELS,
            url = "https://example.com/bright.jpg",
            width = 1440,
            height = 3200,
            tags = listOf("nature"),
            colors = listOf("#f5d142"),
        )

        val ranked = rankWallpapers(
            wallpapers = listOf(bright, dark),
            filter = WallpaperDiscoverFilter.AMOLED,
        )

        assertEquals(listOf("dark"), ranked.map { it.id })
    }

    @Test
    fun `quality hints expose resolution and icon safety`() {
        val wallpaper = wallpaper(
            id = "icon_safe",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/icon.jpg",
            width = 2160,
            height = 3840,
            tags = listOf("minimal", "gradient"),
            colors = listOf("#111111", "#222222"),
        )

        val hints = wallpaper.qualityHints(resources)

        assertEquals(resources.getString(R.string.wallpaper_quality_resolution_4k), hints.resolutionLabel)
        assertTrue(hints.isIconSafe)
    }

    @Test
    fun `wallpaper card summary gives screen readers useful state`() {
        val wallpaper = wallpaper(
            id = "community_pick",
            source = ContentSource.COMMUNITY,
            url = "https://example.com/community.jpg",
            width = 1440,
            height = 3200,
            tags = listOf("amoled", "minimal"),
            colors = listOf("#000000"),
        ).copy(category = "Minimal")

        val summary = wallpaper.cardAccessibilitySummary(
            resources = resources,
            isFavorite = true,
            voteCount = 12,
        )

        assertEquals(
            listOf(
                resources.getString(R.string.wallpapers_source_wallpaper, "Community"),
                "Minimal",
                resources.getString(R.string.wallpaper_quality_resolution_qhd),
                resources.getString(R.string.wallpaper_quality_orientation_portrait),
                resources.getString(R.string.wallpaper_quality_amoled_friendly),
                resources.getString(R.string.wallpaper_quality_icon_safe),
                resources.getQuantityString(R.plurals.wallpapers_card_upvote_count, 12, 12),
                resources.getString(R.string.wallpapers_card_saved_to_favorites),
            ).joinToString(", "),
            summary,
        )
    }

    @Test
    fun `color filter labels stay bound to their string resources`() {
        // clearWallpaperColorFilterLabel() and wallpaperColorFilterActionLabel() are
        // @Composable, so their key binding is pinned from the source instead of by
        // calling them.
        val screen = File("src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpapersScreen.kt").readText()

        assertTrue(
            screen.contains(
                "fun clearWallpaperColorFilterLabel(): String = stringResource(R.string.wallpapers_clear_color_filter)",
            ),
        )
        assertTrue(screen.contains("stringResource(R.string.wallpapers_color_filter_action, hex)"))
        assertEquals("Clear color filter", resources.getString(R.string.wallpapers_clear_color_filter))
        assertEquals(
            "Set wallpaper color filter #112233",
            resources.getString(R.string.wallpapers_color_filter_action, "112233"),
        )
    }

    @Test
    fun `low signal wallpaper remains at the end instead of truncating endless inventory`() {
        val strongCandidates = listOf(
            wallpaper(
                id = "strong_one",
                source = ContentSource.WALLHAVEN,
                url = "https://example.com/strong-1.jpg",
                width = 2160,
                height = 3840,
                tags = listOf("minimal", "amoled", "gradient"),
                colors = listOf("#000000"),
                favorites = 900,
            ),
            wallpaper(
                id = "strong_two",
                source = ContentSource.BING,
                url = "https://example.com/strong-2.jpg",
                width = 2160,
                height = 3840,
                tags = listOf("clean", "abstract", "dark"),
                colors = listOf("#050505", "#101010"),
                favorites = 500,
            ),
            wallpaper(
                id = "strong_three",
                source = ContentSource.PEXELS,
                url = "https://example.com/strong-3.jpg",
                width = 1440,
                height = 3200,
                tags = listOf("minimal", "soft", "nature"),
                colors = listOf("#101820", "#13293d"),
                favorites = 420,
            ),
            wallpaper(
                id = "strong_four",
                source = ContentSource.REDDIT,
                url = "https://example.com/strong-4.jpg",
                width = 1440,
                height = 3200,
                tags = listOf("amoled", "simple", "space"),
                colors = listOf("#000000", "#0b0f1a"),
                favorites = 300,
            ),
        )
        val weakCandidate = wallpaper(
            id = "weak_logo",
            source = ContentSource.PEXELS,
            url = "https://example.com/weak.jpg",
            width = 720,
            height = 720,
            tags = listOf("logo", "promo"),
            colors = listOf("#f1f1f1"),
        )

        val ranked = rankWallpapers(
            wallpapers = listOf(weakCandidate) + strongCandidates,
            filter = WallpaperDiscoverFilter.FOR_YOU,
        )

        assertEquals(5, ranked.size)
        assertEquals("weak_logo", ranked.last().id)
    }

    @Test
    fun `reddit inventory is an explicit first tier ahead of secondary providers`() {
        val exceptionalSecondary = wallpaper(
            id = "wallhaven_featured",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/featured.jpg",
            width = 4320,
            height = 7680,
            tags = listOf("minimal", "amoled", "clean"),
            colors = listOf("#000000"),
            favorites = 50_000,
        )
        val reddit = wallpaper(
            id = "reddit_mobile",
            source = ContentSource.REDDIT,
            url = "https://i.redd.it/mobile.jpg",
            width = 0,
            height = 0,
            tags = listOf("reddit", "MobileWallpaper"),
        )

        val ranked = rankWallpapers(
            wallpapers = listOf(exceptionalSecondary, reddit),
            filter = WallpaperDiscoverFilter.FOR_YOU,
        )

        assertEquals(listOf("reddit_mobile", "wallhaven_featured"), ranked.map { it.id })
    }

    @Test
    fun `home merge puts reddit first and preserves pagination from either tier`() {
        val reddit = wallpaper(
            id = "reddit_1",
            source = ContentSource.REDDIT,
            url = "https://i.redd.it/one.jpg",
            width = 1440,
            height = 3200,
        )
        val secondary = wallpaper(
            id = "wallhaven_1",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/one.jpg",
            width = 1440,
            height = 3200,
        )

        val merged = mergeRedditFirstHomeResults(
            reddit = SearchResult(listOf(reddit), -1, 3, true),
            secondary = SearchResult(listOf(secondary), 200, 3, false),
            page = 3,
        )

        assertEquals(listOf(ContentSource.REDDIT, ContentSource.WALLHAVEN), merged.items.map { it.source })
        assertEquals(3, merged.currentPage)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `style learning adapts for you ranking after enough local signals`() {
        val liked = wallpaper(
            id = "liked_forest",
            source = ContentSource.WALLHAVEN,
            url = "https://example.com/liked.jpg",
            width = 1440,
            height = 3200,
            tags = listOf("forest", "nature"),
            colors = listOf("#226633"),
        )
        val skipped = wallpaper(
            id = "skipped_minimal",
            source = ContentSource.PEXELS,
            url = "https://example.com/skipped.jpg",
            width = 1440,
            height = 3200,
            tags = listOf("minimal", "gradient", "amoled"),
            colors = listOf("#050505"),
            favorites = 900,
        )
        val earlyProfile = WallpaperStyleLearningProfile.EMPTY
            .record(liked, WallpaperStyleLearningSignal.FAVORITED)
            .record(skipped, WallpaperStyleLearningSignal.SKIPPED)
        val learnedProfile = earlyProfile.record(liked, WallpaperStyleLearningSignal.APPLIED)
            .record(skipped, WallpaperStyleLearningSignal.SKIPPED)

        val earlyRanked = rankWallpapers(
            wallpapers = listOf(skipped, liked),
            filter = WallpaperDiscoverFilter.FOR_YOU,
            styleLearningProfile = earlyProfile,
        )
        val learnedRanked = rankWallpapers(
            wallpapers = listOf(skipped, liked),
            filter = WallpaperDiscoverFilter.FOR_YOU,
            styleLearningProfile = learnedProfile,
        )

        assertEquals(listOf("skipped_minimal", "liked_forest"), earlyRanked.map { it.id })
        assertEquals(listOf("liked_forest", "skipped_minimal"), learnedRanked.map { it.id })
    }

    private fun wallpaper(
        id: String,
        source: ContentSource,
        url: String,
        width: Int,
        height: Int,
        tags: List<String> = emptyList(),
        colors: List<String> = emptyList(),
        favorites: Int = 0,
    ) = Wallpaper(
        id = id,
        source = source,
        thumbnailUrl = url,
        fullUrl = url,
        width = width,
        height = height,
        tags = tags,
        colors = colors,
        favorites = favorites,
    )
}
