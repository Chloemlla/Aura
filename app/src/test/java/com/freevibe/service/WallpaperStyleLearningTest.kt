package com.freevibe.service

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperStyleLearningTest {

    @Test
    fun `profile does not affect ranking before minimum signal count`() {
        val wallpaper = wallpaper(tags = listOf("minimal", "dark"))
        val profile = WallpaperStyleLearningProfile.EMPTY
            .record(wallpaper, WallpaperStyleLearningSignal.FAVORITED)
            .record(wallpaper, WallpaperStyleLearningSignal.APPLIED)

        assertFalse(profile.canRank)
        assertEquals(0, profile.scoreFor(wallpaper))
    }

    @Test
    fun `profile scores favorite and skipped styles in opposite directions`() {
        val liked = wallpaper(id = "liked", tags = listOf("minimal", "gradient"), colors = listOf("#101010"))
        val skipped = wallpaper(id = "skipped", tags = listOf("logo", "promo"), colors = listOf("#ffffff"))
        val profile = WallpaperStyleLearningProfile.EMPTY
            .record(liked, WallpaperStyleLearningSignal.FAVORITED)
            .record(liked, WallpaperStyleLearningSignal.APPLIED)
            .record(skipped, WallpaperStyleLearningSignal.SKIPPED)

        assertTrue(profile.canRank)
        assertTrue(profile.scoreFor(liked) > 0)
        assertTrue(profile.scoreFor(skipped) < 0)
    }

    @Test
    fun `profile survives json roundtrip`() {
        val wallpaper = wallpaper(tags = listOf("nature", "forest"), colors = listOf("#227744"))
        val profile = WallpaperStyleLearningProfile.EMPTY
            .record(wallpaper, WallpaperStyleLearningSignal.FAVORITED)
            .record(wallpaper, WallpaperStyleLearningSignal.APPLIED)
            .record(wallpaper, WallpaperStyleLearningSignal.APPLIED)

        val parsed = WallpaperStyleLearningProfile.parse(WallpaperStyleLearningProfile.serialize(profile))

        assertEquals(profile, parsed)
    }

    private fun wallpaper(
        id: String = "wallpaper",
        tags: List<String>,
        colors: List<String> = emptyList(),
    ) = Wallpaper(
        id = id,
        source = ContentSource.WALLHAVEN,
        thumbnailUrl = "https://example.com/$id.jpg",
        fullUrl = "https://example.com/$id.jpg",
        width = 1440,
        height = 3200,
        tags = tags,
        colors = colors,
    )
}
