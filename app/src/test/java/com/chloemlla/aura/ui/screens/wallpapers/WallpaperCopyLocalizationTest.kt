package com.chloemlla.aura.ui.screens.wallpapers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chloemlla.aura.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperCopyLocalizationTest {

    private val resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `video feed badges and empty states resolve in english`() {
        assertEquals("Loop-safe", resources.getString(R.string.video_wp_badge_loop_safe))
        assertEquals("Needs crop", resources.getString(R.string.video_wp_badge_needs_crop))
        assertEquals("No video wallpapers found", resources.getString(R.string.video_wp_empty_default_title))
        assertEquals(
            "Try another focus filter or switch the Portrait view.",
            resources.getString(
                R.string.video_wp_empty_default_description,
                resources.getString(R.string.video_wp_badge_portrait),
            ),
        )
        assertEquals("Photos", resources.getStringArray(R.array.preview_mock_app_labels)[2])
        assertEquals("QHD", resources.getString(R.string.wallpaper_quality_resolution_qhd))
        assertEquals("icon-safe", resources.getString(R.string.wallpaper_quality_icon_safe))
        assertEquals("Pexels wallpaper", resources.getString(R.string.wallpapers_source_wallpaper, "Pexels"))
        assertEquals(
            "By Alex on Pexels",
            resources.getString(R.string.detail_subtitle_by_uploader, "Alex", "Pexels"),
        )
        assertEquals(
            "12 upvotes",
            resources.getQuantityString(R.plurals.wallpapers_card_upvote_count, 12, 12),
        )
        assertEquals(
            "Unknown video dimensions",
            resources.getString(R.string.video_wp_summary_unknown_dimensions),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh")
    fun `chinese locale resolves video feed and preview mock copy`() {
        assertEquals("循环顺滑", resources.getString(R.string.video_wp_badge_loop_safe))
        assertEquals("需要裁剪", resources.getString(R.string.video_wp_badge_needs_crop))
        assertEquals("没有找到视频壁纸", resources.getString(R.string.video_wp_empty_default_title))
        assertEquals(
            "换一个焦点筛选，或者切换到竖屏视图。",
            resources.getString(
                R.string.video_wp_empty_default_description,
                resources.getString(R.string.video_wp_badge_portrait),
            ),
        )
        assertEquals("相册", resources.getStringArray(R.array.preview_mock_app_labels)[2])
        assertEquals("视频源当前不可用。", resources.getString(R.string.video_wp_sources_unavailable))
        assertEquals("2K", resources.getString(R.string.wallpaper_quality_resolution_qhd))
        assertEquals("图标安全", resources.getString(R.string.wallpaper_quality_icon_safe))
        assertEquals("适合 AMOLED", resources.getString(R.string.wallpaper_quality_amoled_friendly))
        assertEquals("Pexels 壁纸", resources.getString(R.string.wallpapers_source_wallpaper, "Pexels"))
        assertEquals(
            "Alex 发布于 Pexels",
            resources.getString(R.string.detail_subtitle_by_uploader, "Alex", "Pexels"),
        )
        assertEquals("视频尺寸未知", resources.getString(R.string.video_wp_summary_unknown_dimensions))
    }

    @Test
    fun `wallpaper and video screens keep feed copy out of the sources`() {
        val sources = listOf(
            "src/main/java/com/chloemlla/aura/ui/screens/videowallpapers/VideoWallpaperQuality.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/videowallpapers/VideoWallpapersScreen.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperPreviewScreen.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperFeedQuality.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperDetailScreen.kt",
        ).associateWith { File(it).readText() }
        val forbiddenCopy = listOf(
            "\"Loop-safe\"",
            "\"Needs crop\"",
            "\"Low battery\"",
            "\"No video wallpapers found\"",
            "\"Everything here is hidden\"",
            "\"Photos\"",
            "AMOLED friendly",
            "icon-safe",
            "saved to favorites",
            "Unknown video dimensions",
            "Sourced from ",
            "; source is unavailable",
        )

        sources.forEach { (path, source) ->
            forbiddenCopy.forEach { copy ->
                assertFalse("$path should not hardcode $copy", source.contains(copy))
            }
        }
    }
}
