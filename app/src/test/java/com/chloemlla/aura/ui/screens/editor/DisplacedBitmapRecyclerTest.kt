package com.chloemlla.aura.ui.screens.editor

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplacedBitmapRecyclerTest {

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Test
    fun `the first displaced bitmap is held, not freed, because compose may still paint it`() {
        val recycler = DisplacedBitmapRecycler()
        val first = bitmap()

        assertEquals(0, recycler.displace(first, emptyList()))
        assertFalse(first.isRecycled)
        assertEquals(1, recycler.pendingCount)
    }

    @Test
    fun `a later replacement frees the one that has waited a generation`() {
        val recycler = DisplacedBitmapRecycler()
        val first = bitmap()
        val second = bitmap()

        recycler.displace(first, emptyList())
        assertEquals(1, recycler.displace(second, emptyList()))

        assertTrue(first.isRecycled)
        assertFalse(second.isRecycled)
    }

    /** The whole reason the editor leaked: a slider drag is many renders in a row. */
    @Test
    fun `a long drag never holds more than one generation`() {
        val recycler = DisplacedBitmapRecycler()
        val bitmaps = List(30) { bitmap() }

        bitmaps.forEach { recycler.displace(it, emptyList()) }

        assertEquals(1, recycler.pendingCount)
        assertEquals(29, bitmaps.count { it.isRecycled })
        assertFalse(bitmaps.last().isRecycled)
    }

    @Test
    fun `a bitmap editor state still points at is never freed`() {
        val recycler = DisplacedBitmapRecycler()
        val retained = bitmap()

        recycler.displace(retained, listOf(retained))
        recycler.displace(bitmap(), listOf(retained))
        recycler.displace(bitmap(), listOf(retained))

        assertFalse(retained.isRecycled)
    }

    /**
     * `resetAll()` puts the original back into `editedBitmap`, so a bitmap can be
     * queued and then become live again before its generation is up. Release
     * re-checks the retained set for exactly this.
     */
    @Test
    fun `a bitmap taken back into state after being queued is spared`() {
        val recycler = DisplacedBitmapRecycler()
        val reclaimed = bitmap()

        recycler.displace(reclaimed, emptyList())
        recycler.displace(bitmap(), listOf(reclaimed))

        assertFalse(reclaimed.isRecycled)
    }

    @Test
    fun `queuing the same bitmap twice cannot produce a double recycle`() {
        val recycler = DisplacedBitmapRecycler()
        val repeated = bitmap()

        recycler.displace(repeated, emptyList())
        recycler.displace(repeated, emptyList())

        assertEquals(1, recycler.pendingCount)
        assertFalse(repeated.isRecycled)
    }

    @Test
    fun `an already recycled bitmap is not queued`() {
        val recycler = DisplacedBitmapRecycler()
        val dead = bitmap()
        dead.recycle()

        assertEquals(0, recycler.displace(dead, emptyList()))
        assertEquals(0, recycler.pendingCount)
    }

    @Test
    fun `a bitmap recycled elsewhere while queued is not recycled again`() {
        val recycler = DisplacedBitmapRecycler()
        val queued = bitmap()
        recycler.displace(queued, emptyList())
        queued.recycle()

        assertEquals(0, recycler.displace(bitmap(), emptyList()))
        assertEquals(1, recycler.pendingCount)
    }

    @Test
    fun `null is accepted and changes nothing`() {
        val recycler = DisplacedBitmapRecycler()

        assertEquals(0, recycler.displace(null, emptyList()))
        assertEquals(0, recycler.pendingCount)
    }

    @Test
    fun `draining frees everything held`() {
        val recycler = DisplacedBitmapRecycler(generations = 3)
        val bitmaps = List(3) { bitmap() }
        bitmaps.forEach { recycler.displace(it, emptyList()) }

        assertEquals(3, recycler.drain())

        assertEquals(0, recycler.pendingCount)
        assertTrue(bitmaps.all { it.isRecycled })
    }

    @Test
    fun `draining still spares what state points at`() {
        val recycler = DisplacedBitmapRecycler(generations = 2)
        val live = bitmap()
        val dead = bitmap()
        recycler.displace(live, emptyList())
        recycler.displace(dead, emptyList())

        assertEquals(1, recycler.drain(listOf(live)))

        assertFalse(live.isRecycled)
        assertTrue(dead.isRecycled)
    }

    @Test
    fun `draining twice is a no-op rather than a second recycle`() {
        val recycler = DisplacedBitmapRecycler()
        recycler.displace(bitmap(), emptyList())

        assertEquals(1, recycler.drain())
        assertEquals(0, recycler.drain())
    }
}
