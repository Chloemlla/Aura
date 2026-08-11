package com.chloemlla.aura.service

import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_CLOCK
import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_SINGLE
import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.WALLPAPER_SOURCE_LOCAL_FOLDER
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWallpaperWorkerTest {

    @Test
    fun `triggered one-shot uses saved legacy rotation when periodic rotation is off`() {
        assertTrue(shouldRunLegacyRotation(schedulerEnabled = false, legacyEnabled = false, triggeredRotation = true))
        assertTrue(shouldRunLegacyRotation(schedulerEnabled = false, legacyEnabled = true, triggeredRotation = false))
        assertFalse(shouldRunLegacyRotation(schedulerEnabled = false, legacyEnabled = false, triggeredRotation = false))
        assertFalse(shouldRunLegacyRotation(schedulerEnabled = true, legacyEnabled = true, triggeredRotation = true))
    }

    @Test
    fun `clock schedule swaps sources exactly at day and night boundaries`() {
        fun sourceAt(hour: Int) = resolveScheduledWallpaperSource(
            defaultSource = "discover",
            daySource = "bing",
            nightSource = "wallhaven",
            mode = SCHEDULER_DAY_NIGHT_MODE_CLOCK,
            hour = hour,
            dayStartHour = 6,
            nightStartHour = 18,
            isSystemDark = false,
        )

        assertEquals("wallhaven", sourceAt(5))
        assertEquals("bing", sourceAt(6))
        assertEquals("bing", sourceAt(17))
        assertEquals("wallhaven", sourceAt(18))
    }

    @Test
    fun `clock schedule supports a day window that crosses midnight`() {
        assertTrue(isHourInScheduledDayWindow(hour = 22, dayStartHour = 20, nightStartHour = 5))
        assertTrue(isHourInScheduledDayWindow(hour = 4, dayStartHour = 20, nightStartHour = 5))
        assertEquals(false, isHourInScheduledDayWindow(hour = 5, dayStartHour = 20, nightStartHour = 5))
    }

    @Test
    fun `system theme schedule maps light to day and dark to night`() {
        val light = resolveScheduledWallpaperSource(
            defaultSource = "discover",
            daySource = "favorites",
            nightSource = "local_folder",
            mode = SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME,
            hour = 23,
            dayStartHour = 6,
            nightStartHour = 18,
            isSystemDark = false,
        )
        val dark = resolveScheduledWallpaperSource(
            defaultSource = "discover",
            daySource = "favorites",
            nightSource = "local_folder",
            mode = SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME,
            hour = 12,
            dayStartHour = 6,
            nightStartHour = 18,
            isSystemDark = true,
        )

        assertEquals("favorites", light)
        assertEquals("local_folder", dark)
    }

    @Test
    fun `single source mode ignores persisted phase values and phase blanks fall back`() {
        assertEquals(
            "discover",
            resolveScheduledWallpaperSource(
                defaultSource = "discover",
                daySource = "bing",
                nightSource = "wallhaven",
                mode = SCHEDULER_DAY_NIGHT_MODE_SINGLE,
                hour = 23,
                dayStartHour = 6,
                nightStartHour = 18,
                isSystemDark = true,
            ),
        )
        assertEquals(
            setOf("discover", "wallhaven"),
            scheduledSourceCandidates(
                defaultSource = "discover",
                daySource = "",
                nightSource = "wallhaven",
                mode = SCHEDULER_DAY_NIGHT_MODE_CLOCK,
            ),
        )
    }

    @Test
    fun `night variant follows system dark mode and clock night window`() {
        fun active(
            enabled: Boolean = true,
            schedulerEnabled: Boolean = true,
            mode: String = SCHEDULER_DAY_NIGHT_MODE_CLOCK,
            hour: Int = 12,
            isSystemDark: Boolean = false,
        ) = shouldUseNightWallpaperVariant(
            enabled = enabled,
            schedulerEnabled = schedulerEnabled,
            schedulerMode = mode,
            hour = hour,
            dayStartHour = 6,
            nightStartHour = 18,
            isSystemDark = isSystemDark,
        )

        assertFalse(active(enabled = false, hour = 22, isSystemDark = true))
        assertTrue(active(isSystemDark = true))
        assertTrue(active(hour = 22))
        assertFalse(active(hour = 12))
        assertFalse(active(schedulerEnabled = false, hour = 22))
        assertFalse(active(mode = SCHEDULER_DAY_NIGHT_MODE_SINGLE, hour = 22))
    }

    @Test
    fun `normalizeWallpaperRotationSource maps legacy unsplash to discover`() {
        assertEquals("discover", "unsplash".normalizeWallpaperRotationSource())
        assertEquals("discover", "reddit".normalizeWallpaperRotationSource())
        assertEquals("discover", "".normalizeWallpaperRotationSource())
        assertEquals("wallhaven", "wallhaven".normalizeWallpaperRotationSource())
        assertEquals(WALLPAPER_SOURCE_LOCAL_FOLDER, WALLPAPER_SOURCE_LOCAL_FOLDER.normalizeWallpaperRotationSource())
    }

    @Test
    fun `isLocalWallpaperMimeType accepts image mime types and common extensions`() {
        assertTrue(isLocalWallpaperMimeType("photo.jpg", "image/jpeg"))
        assertTrue(isLocalWallpaperMimeType("portrait.WEBP", null))
        assertTrue(isLocalWallpaperMimeType("wallpaper.heic", "application/octet-stream"))
    }

    @Test
    fun `isLocalWallpaperMimeType rejects folders and non-image media`() {
        assertEquals(false, isLocalWallpaperMimeType("Pictures", "vnd.android.document/directory"))
        assertEquals(false, isLocalWallpaperMimeType("clip.mp4", "video/mp4"))
        assertEquals(false, isLocalWallpaperMimeType("notes.txt", null))
    }

    @Test
    fun `stable-key comparison keeps different-provider alternatives available`() {
        val primary = wallpaper(id = "shared", source = ContentSource.PEXELS)
        val alternate = wallpaper(id = "shared", source = ContentSource.PIXABAY)

        val rawIdFiltered = listOf(primary, alternate).filter { it.id != primary.id }
        val stableKeyFiltered = listOf(primary, alternate).filter { it.stableKey() != primary.stableKey() }

        assertTrue(rawIdFiltered.isEmpty())
        assertEquals(listOf(alternate), stableKeyFiltered)
    }

    @Test
    fun `pickScheduledWallpaper returns null for empty inputs`() {
        assertNull(pickScheduledWallpaper(emptyList(), shuffle = true))
        assertNull(pickScheduledWallpaper(emptyList(), shuffle = false))
    }

    @Test
    fun `pickScheduledWallpaper returns first item when shuffle disabled`() {
        val first = wallpaper(id = "first", source = ContentSource.WALLHAVEN)
        val second = wallpaper(id = "second", source = ContentSource.REDDIT)

        val picked = pickScheduledWallpaper(listOf(first, second), shuffle = false)

        assertEquals(first, picked)
    }

    // ── excludeRecentWallpapers (sequential selection) ──

    @Test
    fun `excludeRecentWallpapers returns full list when recentIds is empty`() {
        val wallpapers = listOf(
            wallpaper("a", ContentSource.WALLHAVEN),
            wallpaper("b", ContentSource.PEXELS),
        )
        val result = excludeRecentWallpapers(wallpapers, emptySet())
        assertEquals(wallpapers, result)
    }

    @Test
    fun `excludeRecentWallpapers filters out recently applied wallpapers`() {
        val a = wallpaper("a", ContentSource.WALLHAVEN)
        val b = wallpaper("b", ContentSource.PEXELS)
        val c = wallpaper("c", ContentSource.PIXABAY)
        val recentIds = setOf(a.stableKey())

        val result = excludeRecentWallpapers(listOf(a, b, c), recentIds)

        assertEquals(listOf(b, c), result)
    }

    @Test
    fun `excludeRecentWallpapers returns empty when all are recent so the caller resets the cycle`() {
        val a = wallpaper("a", ContentSource.WALLHAVEN)
        val b = wallpaper("b", ContentSource.PEXELS)
        val recentIds = setOf(a.stableKey(), b.stableKey())

        val result = excludeRecentWallpapers(listOf(a, b), recentIds)

        assertTrue(result.isEmpty())
    }

    private fun wallpaper(id: String, source: ContentSource) = Wallpaper(
        id = id,
        source = source,
        thumbnailUrl = "thumb_$id",
        fullUrl = "full_${source.name.lowercase()}_$id",
        width = 1080,
        height = 2400,
    )
}
