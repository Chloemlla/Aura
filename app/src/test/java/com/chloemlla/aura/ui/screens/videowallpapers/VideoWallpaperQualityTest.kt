package com.chloemlla.aura.ui.screens.videowallpapers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWallpaperQualityTest {

    @Test
    fun `quality floor drops weak off-fit videos when stronger set exists`() {
        val strongCandidates = listOf(
            video(
                id = "px_one",
                source = "Pexels",
                title = "Abstract loop",
                duration = 12,
                popularity = 12_000,
                videoWidth = 1080,
                videoHeight = 1920,
            ),
            video(
                id = "pb_two",
                source = "Pixabay",
                title = "Ambient particles",
                duration = 10,
                popularity = 8_500,
                videoWidth = 1080,
                videoHeight = 1920,
            ),
            video(
                id = "rd_three",
                source = "Reddit",
                title = "Rain loop",
                duration = 9,
                popularity = 7_000,
                videoWidth = 1080,
                videoHeight = 1920,
            ),
            video(
                id = "yt_four",
                source = "YouTube",
                title = "Galaxy ambient loop",
                duration = 15,
                popularity = 25_000,
                videoWidth = 1080,
                videoHeight = 1920,
            ),
        )
        val weakCandidate = video(
            id = "yt_weak",
            source = "YouTube",
            title = "Phone setup review",
            duration = 75,
            popularity = 40,
            videoWidth = 1920,
            videoHeight = 1080,
        )

        val ranked = rankVideoWallpapers(
            items = listOf(weakCandidate) + strongCandidates,
            filter = VideoFocusFilter.BEST,
            orientation = OrientationFilter.PORTRAIT,
        )

        assertEquals(4, ranked.size)
        assertTrue(ranked.none { it.id == "yt_weak" })
    }

    @Test
    fun `phone fit filter respects landscape orientation`() {
        val portrait = video(
            id = "portrait_one",
            source = "Pexels",
            title = "Portrait loop",
            duration = 12,
            popularity = 5_000,
            videoWidth = 1080,
            videoHeight = 1920,
        )
        val landscape = video(
            id = "landscape_one",
            source = "Pixabay",
            title = "Landscape loop",
            duration = 12,
            popularity = 5_500,
            videoWidth = 1920,
            videoHeight = 1080,
        )

        val ranked = rankVideoWallpapers(
            items = listOf(portrait, landscape),
            filter = VideoFocusFilter.PHONE_FIT,
            orientation = OrientationFilter.LANDSCAPE,
        )

        assertEquals(listOf("landscape_one"), ranked.map { it.id })
        assertFalse(ranked.any { it.id == "portrait_one" })
    }

    @Test
    fun `reddit is first and dominant while quality remains ordered within source`() {
        val reddit = (1..6).map { index ->
            video(
                id = "rd_$index",
                source = "Reddit",
                title = "Cinemagraph loop $index",
                duration = 12,
                popularity = index * 10_000L,
                videoWidth = 1080,
                videoHeight = 1920,
            )
        }
        val alternatives = listOf(
            video("px_1", "Pexels", "Abstract loop", 12, 40_000, 1080, 1920),
            video("pb_1", "Pixabay", "Ambient loop", 12, 40_000, 1080, 1920),
            video("yt_1", "YouTube", "Galaxy loop", 12, 40_000, 1080, 1920),
        )

        val ranked = rankVideoWallpapers(
            items = alternatives + reddit,
            filter = VideoFocusFilter.BEST,
            orientation = OrientationFilter.PORTRAIT,
        )

        assertEquals(listOf("rd_6", "rd_5", "rd_4"), ranked.take(3).map { it.id })
        assertEquals(5, ranked.take(8).count { it.source == "Reddit" })
        assertTrue(
            ranked.filter { it.source == "Reddit" }
                .zipWithNext()
                .all { (first, second) -> first.popularity >= second.popularity },
        )
    }

    private fun video(
        id: String,
        source: String,
        title: String,
        duration: Long,
        popularity: Long,
        videoWidth: Int,
        videoHeight: Int,
    ) = VideoWallpaperItem(
        id = id,
        title = title,
        thumbnailUrl = "https://example.com/$id.jpg",
        source = source,
        duration = duration,
        popularity = popularity,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )
}
