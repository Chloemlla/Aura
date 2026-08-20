package com.freevibe.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMemoryLimiterTest {

    @Test
    fun `the documented anon-swap description is recognised`() {
        assertTrue(
            AndroidMemoryLimiter.isMemoryLimiterExit(
                "Process killed by ${AndroidMemoryLimiter.ANON_SWAP_VARIANT} (rss 512MB)",
            ),
        )
    }

    /**
     * The platform writes free text, so anything carrying the marker counts. Pinning
     * the exact anon-swap string would miss a future variant and report the kill as
     * an ordinary death, which is the failure this exists to prevent.
     */
    @Test
    fun `any limiter variant is recognised, not only anon-swap`() {
        assertTrue(AndroidMemoryLimiter.isMemoryLimiterExit("MemoryLimiter:SomethingElse"))
        assertTrue(AndroidMemoryLimiter.isMemoryLimiterExit("memorylimiter lowercase"))
    }

    @Test
    fun `ordinary exits are left alone`() {
        assertFalse(AndroidMemoryLimiter.isMemoryLimiterExit(null))
        assertFalse(AndroidMemoryLimiter.isMemoryLimiterExit(""))
        assertFalse(AndroidMemoryLimiter.isMemoryLimiterExit("user request"))
        assertFalse(AndroidMemoryLimiter.isMemoryLimiterExit("Native crash in libfoo.so"))
        assertFalse(AndroidMemoryLimiter.isMemoryLimiterExit("lmkd kill: low memory"))
    }

    @Test
    fun `an exit line is annotated only when the limiter did it`() {
        assertTrue(
            AndroidMemoryLimiter.annotate(AndroidMemoryLimiter.ANON_SWAP_VARIANT)
                .contains(AndroidMemoryLimiter.EXPLANATION),
        )
        assertEquals("", AndroidMemoryLimiter.annotate("ANR in com.freevibe"))
        assertEquals("", AndroidMemoryLimiter.annotate(null))
    }

    @Test
    fun `limiter kills are counted across a mixed exit history`() {
        val history = listOf(
            "user request",
            AndroidMemoryLimiter.ANON_SWAP_VARIANT,
            null,
            "MemoryLimiter:AnonSwap again",
            "CRASH",
        )

        assertEquals(2, AndroidMemoryLimiter.countMemoryLimiterExits(history))
    }

    @Test
    fun `an empty history counts nothing rather than failing`() {
        assertEquals(0, AndroidMemoryLimiter.countMemoryLimiterExits(emptyList()))
    }
}

class WallpaperEditorMemoryBudgetTest {

    @Test
    fun `a full-size editor bitmap is the expected 64 MiB`() {
        assertEquals(
            64L * 1024 * 1024,
            WallpaperEditorMemoryBudget.bitmapBytes(4096, 4096),
        )
    }

    /**
     * The point of the whole item: the worst case the dimension cap admits has to sit
     * under the recorded ceiling. Raising `MAX_EDIT_LONG_EDGE` without raising the
     * ceiling deliberately fails here rather than on a user's phone under Android 17.
     */
    @Test
    fun `the worst case the dimension cap admits stays under the recorded ceiling`() {
        val peak = WallpaperEditorMemoryBudget.worstCasePeakBytes()

        assertTrue(
            "editor worst case $peak exceeds ceiling ${WallpaperEditorMemoryBudget.PEAK_ALLOCATION_CEILING_BYTES}",
            WallpaperEditorMemoryBudget.withinCeiling(peak),
        )
    }

    @Test
    fun `a third concurrent bitmap would breach the ceiling`() {
        val orphaned = WallpaperEditorMemoryBudget.peakAllocationBytes(
            WallpaperEditorMemoryBudget.MAX_EDIT_LONG_EDGE,
            WallpaperEditorMemoryBudget.MAX_EDIT_LONG_EDGE,
            concurrentBitmaps = 3,
        )

        assertFalse(WallpaperEditorMemoryBudget.withinCeiling(orphaned))
    }

    @Test
    fun `degenerate dimensions cost nothing instead of going negative`() {
        assertEquals(0L, WallpaperEditorMemoryBudget.bitmapBytes(0, 4096))
        assertEquals(0L, WallpaperEditorMemoryBudget.bitmapBytes(-10, 4096))
        assertEquals(0L, WallpaperEditorMemoryBudget.peakAllocationBytes(4096, 4096, concurrentBitmaps = -1))
    }

    @Test
    fun `the bundle line names the numbers a support reader needs`() {
        val described = WallpaperEditorMemoryBudget.describe()

        assertTrue(described, described.contains("128 MiB"))
        assertTrue(described, described.contains("4096px"))
    }
}
