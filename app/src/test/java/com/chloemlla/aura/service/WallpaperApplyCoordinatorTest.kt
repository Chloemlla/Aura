package com.chloemlla.aura.service

import android.content.Context
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperHistoryEntity
import com.chloemlla.aura.data.model.WallpaperTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The commit rule for every apply surface: nothing persists unless the system
 * call actually succeeded, and then each effect happens exactly once. Browsing
 * used to do all of this inline while the editor, crop, and AI screens skipped
 * it, so an edited wallpaper never entered history and could not be undone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperApplyCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var prefs: PreferencesManager
    private lateinit var historyManager: WallpaperHistoryManager
    private lateinit var feedbackBus: ApplyFeedbackBus
    private lateinit var coordinator: WallpaperApplyCoordinator

    private val wallpaper = Wallpaper(
        id = "wp-1",
        source = ContentSource.WALLHAVEN,
        thumbnailUrl = "https://w.example/t.jpg",
        fullUrl = "https://w.example/f.jpg",
        width = 1080,
        height = 1920,
    )

    private val previous = WallpaperHistoryEntity(
        wallpaperId = "wp-0",
        source = "WALLHAVEN",
        thumbnailUrl = "https://w.example/t0.jpg",
        fullUrl = "https://w.example/f0.jpg",
        width = 1080,
        height = 1920,
    )

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        historyManager = mockk(relaxed = true)
        coEvery { historyManager.previousSnapshot() } returns previous
        feedbackBus = ApplyFeedbackBus()
        coordinator = WallpaperApplyCoordinator(context, prefs, historyManager, feedbackBus)
    }

    @Test
    fun `a successful browse apply commits every effect exactly once`() = runTest {
        var styleSignals = 0

        val receipt = coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.BOTH,
            policy = WallpaperApplyPolicy.BROWSE,
            onStyleSignal = { styleSignals++ },
        ) { Result.success(Unit) }.getOrThrow()

        assertTrue(receipt.historyRecorded)
        assertEquals(previous, receipt.undoTarget)
        assertNotNull(receipt.feedbackMessage)
        assertEquals(1, styleSignals)
        coVerify(exactly = 1) { historyManager.record(wallpaper, WallpaperTarget.BOTH) }
        coVerify(exactly = 1) { prefs.setLastNightVariantWallpaper(wallpaper.fullUrl, "BOTH") }
    }

    @Test
    fun `a failed apply commits nothing`() = runTest {
        var styleSignals = 0

        val result = coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.HOME,
            policy = WallpaperApplyPolicy.BROWSE,
            onStyleSignal = { styleSignals++ },
        ) { Result.failure(IllegalStateException("no permission")) }

        assertTrue(result.isFailure)
        assertEquals(0, styleSignals)
        coVerify(exactly = 0) { historyManager.record(any(), any()) }
        coVerify(exactly = 0) { prefs.setLastNightVariantWallpaper(any(), any()) }
    }

    @Test
    fun `a thrown failure is captured rather than escaping as a crash`() = runTest {
        val result = coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.HOME,
            policy = WallpaperApplyPolicy.BROWSE,
        ) { throw IllegalStateException("decode failed") }

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { historyManager.record(any(), any()) }
    }

    @Test
    fun `cancellation propagates and commits nothing`() = runTest {
        var cancelled = false
        try {
            coordinator.apply(
                wallpaper = wallpaper,
                target = WallpaperTarget.HOME,
                policy = WallpaperApplyPolicy.BROWSE,
            ) { throw CancellationException("navigated away") }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must not be swallowed into a failed Result", cancelled)
        coVerify(exactly = 0) { historyManager.record(any(), any()) }
    }

    @Test
    fun `derived output records history but not a style signal`() = runTest {
        var styleSignals = 0

        val receipt = coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.LOCK,
            policy = WallpaperApplyPolicy.DERIVED,
            onStyleSignal = { styleSignals++ },
        ) { Result.success(Unit) }.getOrThrow()

        assertTrue("edited output must still be undoable", receipt.historyRecorded)
        assertNotNull(receipt.undoTarget)
        assertEquals("the source already contributed its taste signal", 0, styleSignals)
    }

    @Test
    fun `background applies record history without user feedback`() = runTest {
        val receipt = coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.BOTH,
            policy = WallpaperApplyPolicy.BACKGROUND,
        ) { Result.success(Unit) }.getOrThrow()

        assertTrue(receipt.historyRecorded)
        assertNull("nobody is looking at a snackbar", receipt.feedbackMessage)
    }

    @Test
    fun `output with no catalog identity skips history instead of inventing a row`() = runTest {
        val receipt = coordinator.apply(
            wallpaper = null,
            target = WallpaperTarget.HOME,
            policy = WallpaperApplyPolicy.DERIVED,
            locator = "file:/data/user/0/com.chloemlla.aura/files/edited.png",
        ) { Result.success(Unit) }.getOrThrow()

        assertFalse(receipt.historyRecorded)
        assertNull(receipt.undoTarget)
        coVerify(exactly = 0) { historyManager.record(any(), any()) }
        // The night-variant locator still lands, because that is about the file on
        // screen, not about a catalog entry.
        coVerify(exactly = 1) {
            prefs.setLastNightVariantWallpaper("file:/data/user/0/com.chloemlla.aura/files/edited.png", "HOME")
        }
    }

    @Test
    fun `an explicit locator overrides the wallpaper url for the night variant`() = runTest {
        coordinator.apply(
            wallpaper = wallpaper,
            target = WallpaperTarget.HOME,
            policy = WallpaperApplyPolicy.DERIVED,
            locator = "file:/tmp/edited.png",
        ) { Result.success(Unit) }.getOrThrow()

        coVerify(exactly = 1) { prefs.setLastNightVariantWallpaper("file:/tmp/edited.png", "HOME") }
        coVerify(exactly = 0) { prefs.setLastNightVariantWallpaper(wallpaper.fullUrl, any()) }
    }

    @Test
    fun `every apply surface declares a policy instead of hand-rolling side effects`() {
        val surfaces = listOf(
            "src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperApplyActions.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/editor/WallpaperEditorViewModel.kt",
            "src/main/java/com/chloemlla/aura/ui/screens/editor/WallpaperCropViewModel.kt",
            "src/full/java/com/chloemlla/aura/ui/screens/aigenerate/AiWallpaperViewModel.kt",
        )
        surfaces.forEach { relative ->
            val source = File(relative).readText()
            assertTrue(
                "$relative must apply through the coordinator",
                source.contains("applyCoordinator.apply("),
            )
            assertTrue(
                "$relative must declare a persistence policy",
                source.contains("WallpaperApplyPolicy."),
            )
            assertFalse(
                "$relative must not record history itself",
                source.contains("historyManager.record("),
            )
        }
    }
}
