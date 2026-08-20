package com.freevibe.service

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The publisher decides *when* an engine recomputes and republishes, which is the
 * part with logic in it. The framework quantizer is stubbed so these assertions
 * describe the caching policy rather than AOSP's colour maths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class LiveWallpaperColorPublisherTest {

    private var computeCount = 0

    private fun colors(rgb: Int): WallpaperColors {
        val color = Color.valueOf(rgb)
        return WallpaperColors(color, color, color)
    }

    private fun publisher(
        compute: (Bitmap) -> WallpaperColors? = { colors(0xFF102030.toInt()) },
    ) = LiveWallpaperColorPublisher { bitmap ->
        computeCount++
        compute(bitmap)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun `first source computes and asks the engine to notify`() {
        val subject = publisher()

        assertTrue(subject.update("wallpaper-a", bitmap()))
        assertEquals(1, computeCount)
        assertNotNull(subject.current)
    }

    @Test
    fun `the same source never recomputes, so a redraw costs nothing`() {
        val subject = publisher()
        subject.update("wallpaper-a", bitmap())

        assertFalse(subject.update("wallpaper-a", bitmap()))
        assertFalse(subject.update("wallpaper-a", bitmap()))
        assertEquals(1, computeCount)
    }

    @Test
    fun `a changed source recomputes and republishes`() {
        val subject = publisher()
        subject.update("wallpaper-a", bitmap())
        val first = subject.current

        assertTrue(subject.update("wallpaper-b", bitmap()))
        assertEquals(2, computeCount)
        assertFalse(first === subject.current)
    }

    @Test
    fun `a null token forces a recompute for engines that cannot name their source`() {
        val subject = publisher()
        subject.update(null, bitmap())

        assertTrue(subject.update(null, bitmap()))
        assertEquals(2, computeCount)
    }

    @Test
    fun `nothing is published while the user has publication suppressed`() {
        val subject = publisher()
        subject.update("wallpaper-a", bitmap())
        assertNotNull(subject.current)

        assertTrue(subject.setEnabled(false))
        assertNull(subject.current)

        // Suppression hides the answer; it does not throw away the cache, so
        // turning it back on republishes without decoding anything again.
        assertTrue(subject.setEnabled(true))
        assertNotNull(subject.current)
        assertEquals(1, computeCount)
    }

    @Test
    fun `suppressed updates still cache but do not ask the engine to notify`() {
        val subject = publisher()
        subject.setEnabled(false)

        assertFalse(subject.update("wallpaper-a", bitmap()))
        assertEquals(1, computeCount)
        assertNull(subject.current)
    }

    @Test
    fun `toggling to the value already held changes nothing`() {
        val subject = publisher()

        assertFalse(subject.setEnabled(true))
        subject.update("wallpaper-a", bitmap())
        assertFalse(subject.setEnabled(true))
    }

    @Test
    fun `enabling before any source has been seen has nothing to republish`() {
        val subject = publisher()

        subject.setEnabled(false)
        assertFalse(subject.setEnabled(true))
        assertNull(subject.current)
    }

    /**
     * The whole point of publishing colours rather than bitmaps: the soak harness
     * asserts `imageBuffers` does not grow, and it only stays true because the
     * publisher reads a bitmap and lets go of it.
     */
    @Test
    fun `the source bitmap is not retained and may be recycled immediately`() {
        val subject = publisher()
        val source = bitmap()

        subject.update("wallpaper-a", source)
        val published = subject.current
        source.recycle()

        assertTrue(source.isRecycled)
        assertSame(published, subject.current)
        assertNotNull(subject.current)
    }

    @Test
    fun `a recycled bitmap is refused instead of crashing the decode thread`() {
        val subject = publisher()
        val source = bitmap()
        source.recycle()

        assertFalse(subject.update("wallpaper-a", source))
        assertEquals(0, computeCount)
        assertNull(subject.current)
    }

    @Test
    fun `a quantizer failure leaves the last good colors in place`() {
        var fail = false
        val subject = publisher { if (fail) throw IllegalStateException("quantizer") else colors(0xFF445566.toInt()) }
        subject.update("wallpaper-a", bitmap())
        val good = subject.current

        fail = true
        assertFalse(subject.update("wallpaper-b", bitmap()))
        assertSame(good, subject.current)
    }

    @Test
    fun `a quantizer returning nothing is not published`() {
        val subject = publisher { null }

        assertFalse(subject.update("wallpaper-a", bitmap()))
        assertNull(subject.current)
    }

    @Test
    fun `clear forces the next identical source to recompute`() {
        val subject = publisher()
        subject.update("wallpaper-a", bitmap())

        subject.clear()
        assertNull(subject.current)
        assertTrue(subject.update("wallpaper-a", bitmap()))
        assertEquals(2, computeCount)
    }

    @Test
    fun `shader presets publish their authored palette without decoding anything`() {
        val subject = publisher()
        val preset = AgslShaderGallery.presets.first { it.id != AgslShaderGallery.NONE_ID }

        val changed = subject.updateFromColors(
            token = "shader:${preset.id}",
            primary = preset.fallbackStartColor,
            secondary = preset.fallbackEndColor,
            tertiary = preset.fallbackAccentColor,
        )

        assertTrue(changed)
        assertEquals(0, computeCount)
        assertNotNull(subject.current)
    }

    @Test
    fun `a repeated shader preset does not republish`() {
        val subject = publisher()
        subject.updateFromColors("shader:aurora", 0xFF112233.toInt(), 0xFF223344.toInt(), 0xFF334455.toInt())

        assertFalse(
            subject.updateFromColors("shader:aurora", 0xFF112233.toInt(), 0xFF223344.toInt(), 0xFF334455.toInt()),
        )
    }

    /** WallpaperColors rejects a transparent colour, and preset constants carry alpha. */
    @Test
    fun `transparent preset colors are made opaque rather than rejected`() {
        val subject = publisher()

        assertTrue(subject.updateFromColors("shader:ghost", 0x00112233, 0x00223344, 0x00334455))
        assertEquals(Color.valueOf(0xFF112233.toInt()), subject.current?.primaryColor)
    }
}
