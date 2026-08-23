package com.chloemlla.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three readings that matter, and the one that must stay silent.
 *
 * Before this existed, a wallpaper service dropped after a reboot, replaced by
 * another app, or killed by an OEM battery manager was indistinguishable from a
 * working one: the user saw a stock wallpaper while Aura's settings read "on".
 */
class LiveWallpaperLivenessTest {

    private val ours = "com.chloemlla.aura"

    @Test
    fun `an Aura engine running is reported active`() {
        val result = classifyLiveWallpaperActivity(
            runningPackage = ours,
            runningService = "com.chloemlla.aura.service.WeatherWallpaperService",
            ownPackage = ours,
        )

        assertEquals(LiveWallpaperActivity.ACTIVE, result.activity)
        assertEquals("com.chloemlla.aura.service.WeatherWallpaperService", result.runningService)
        assertFalse(result.needsReapply)
    }

    @Test
    fun `another app's live wallpaper is reported as a replacement, naming it`() {
        val result = classifyLiveWallpaperActivity(
            runningPackage = "net.example.wallpapers",
            runningService = "net.example.wallpapers.Service",
            ownPackage = ours,
        )

        assertEquals(LiveWallpaperActivity.REPLACED_BY_OTHER_APP, result.activity)
        assertEquals("net.example.wallpapers", result.runningPackage)
        assertTrue(result.needsReapply)
    }

    @Test
    fun `no live wallpaper at all is reported as static`() {
        val result = classifyLiveWallpaperActivity(
            runningPackage = null,
            runningService = null,
            ownPackage = ours,
        )

        assertEquals(LiveWallpaperActivity.STATIC, result.activity)
        assertNull(result.runningPackage)
        assertTrue(result.needsReapply)
    }

    /**
     * The replacement branch must not leak the other app's service name into a
     * field the UI reads as "which of ours is running".
     */
    @Test
    fun `a replacement does not report a running Aura service`() {
        val result = classifyLiveWallpaperActivity(
            runningPackage = "net.example.wallpapers",
            runningService = "net.example.wallpapers.Service",
            ownPackage = ours,
        )

        assertNull(result.runningService)
    }

    @Test
    fun `an unknown reading never asks for a re-apply`() {
        assertFalse(LiveWallpaperLivenessResult(LiveWallpaperActivity.UNKNOWN).needsReapply)
    }
}

/**
 * Whether the warning is raised at all. Nagging a user whose wallpaper is fine is
 * how a warning gets ignored when it is real.
 */
class LiveWallpaperLivenessStateTest {

    private fun state(
        activity: LiveWallpaperActivity,
        everApplied: Boolean,
    ) = LiveWallpaperLivenessState(
        result = LiveWallpaperLivenessResult(activity),
        everApplied = everApplied,
    )

    @Test
    fun `a fresh install with a static wallpaper says nothing`() {
        assertFalse(state(LiveWallpaperActivity.STATIC, everApplied = false).shouldWarn)
    }

    @Test
    fun `a static wallpaper after Aura ran one is worth warning about`() {
        assertTrue(state(LiveWallpaperActivity.STATIC, everApplied = true).shouldWarn)
    }

    @Test
    fun `being replaced by another app is worth warning about`() {
        assertTrue(state(LiveWallpaperActivity.REPLACED_BY_OTHER_APP, everApplied = true).shouldWarn)
    }

    @Test
    fun `a working Aura wallpaper says nothing`() {
        assertFalse(state(LiveWallpaperActivity.ACTIVE, everApplied = true).shouldWarn)
    }

    /** "Cannot tell" is not "broken", on any OEM that refuses to answer. */
    @Test
    fun `an unknown reading says nothing even after Aura ran one`() {
        assertFalse(state(LiveWallpaperActivity.UNKNOWN, everApplied = true).shouldWarn)
    }
}
