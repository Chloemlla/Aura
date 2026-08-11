package com.chloemlla.aura.service

import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.WallpaperTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemThemeListenerTest {

    @Test
    fun `night variant reapplies the same locator dark and restores it light`() = runTest {
        val prefs = mockk<PreferencesManager>().also {
            every { it.darkModeAutoSwitch } returns flowOf(false)
            every { it.autoWallpaperNightVariantEnabled } returns flowOf(true)
            every { it.lastNightVariantWallpaperLocator } returns flowOf("content://wallpaper/original")
            every { it.lastNightVariantWallpaperTarget } returns flowOf("HOME")
            every { it.lastNightVariantWallpaperDarkenPercent } returns flowOf(20)
        }
        val applier = mockApplier()
        val listener = SystemThemeListener(RuntimeEnvironment.getApplication(), prefs, applier)

        listener.applyForMode(isNight = true)
        listener.applyForMode(isNight = false)

        coVerifySequence {
            applier.applyByLocator(
                "content://wallpaper/original",
                WallpaperTarget.HOME,
                null,
                20,
                true,
            )
            applier.applyByLocator(
                "content://wallpaper/original",
                WallpaperTarget.HOME,
                null,
                20,
                false,
            )
        }
    }

    @Test
    fun `dedicated light dark pair retains priority and can use night transform`() = runTest {
        val prefs = mockk<PreferencesManager>().also {
            every { it.darkModeAutoSwitch } returns flowOf(true)
            every { it.autoWallpaperNightVariantEnabled } returns flowOf(true)
            every { it.darkModeWallpaperId } returns flowOf("reddit|dark|https://example.com/dark.jpg")
            every { it.lightModeWallpaperId } returns flowOf("reddit|light|https://example.com/light.jpg")
        }
        val applier = mockApplier()
        val listener = SystemThemeListener(RuntimeEnvironment.getApplication(), prefs, applier)

        listener.applyForMode(isNight = true)
        listener.applyForMode(isNight = false)

        coVerify {
            applier.applyByLocator(
                "https://example.com/dark.jpg",
                WallpaperTarget.BOTH,
                null,
                0,
                true,
            )
            applier.applyByLocator(
                "https://example.com/light.jpg",
                WallpaperTarget.BOTH,
                null,
                0,
                false,
            )
        }
    }

    @Test
    fun `night transform crushes black point while preserving alpha`() {
        val matrix = nightWallpaperVariantColorMatrix()

        assertEquals(20, matrix.size)
        assertTrue(32f * matrix[0] + matrix[4] < 0f)
        assertTrue(255f * matrix[0] + matrix[4] < 170f)
        assertEquals(1f, matrix[18])
        assertEquals(0f, matrix[19])
    }

    private fun mockApplier(): WallpaperApplier = mockk<WallpaperApplier>().also {
        coEvery { it.applyByLocator(any(), any(), any(), any(), any()) } returns Result.success(Unit)
    }
}
