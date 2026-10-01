package com.freevibe.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropGeometryTest {

    private val bitmapWidth = 1080
    private val bitmapHeight = 2400
    private val viewportWidth = 1080
    private val viewportHeight = 1700
    private val ratioAspects = CropAspect.entries.filter { it.ratio != null }

    @Test
    fun `each ratio frame is the largest centered frame of that ratio`() {
        assertEquals(CropFrame(0f, 0f, 1080f, 1700f), cropFrame(viewportWidth, viewportHeight, CropAspect.FREE))
        for (aspect in ratioAspects) {
            val frame = cropFrame(viewportWidth, viewportHeight, aspect)
            assertEquals(aspect.name, aspect.ratio!!, frame.width / frame.height, 0.001f)
            assertTrue(aspect.name, frame.width == 1080f || frame.height == 1700f)
            assertEquals(aspect.name, viewportWidth - frame.width, 2 * frame.left, 0.01f)
            assertEquals(aspect.name, viewportHeight - frame.height, 2 * frame.top, 0.01f)
        }
    }

    @Test
    fun `the default Free crop exports the picture as it is shown`() {
        // Fitted at scale 1 the whole picture is on screen, so the whole picture is exported.
        assertEquals(
            CropRect(0, 0, bitmapWidth, bitmapHeight),
            cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.FREE, CropTransform()),
        )
    }

    @Test
    fun `every ratio preset changes the Free crop and exports within a pixel of its ratio`() {
        val free = cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.FREE, CropTransform())
        for (aspect in ratioAspects) {
            val transform = transformForAspect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, aspect, CropTransform())
            val rect = cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, aspect, transform)
            assertNotEquals(aspect.name, free, rect)
            assertWithinOnePixel(aspect, rect)
            assertCoversFrame(aspect, transform, rect)
        }
    }

    @Test
    fun `a ratio frame keeps the point under it when the picture already covers it`() {
        val zoomed = CropTransform(scale = 2f, offsetX = -100f, offsetY = 200f)

        val transform = transformForAspect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, zoomed)

        assertEquals(zoomed.scale, transform.scale, 0.0001f)
        assertEquals(zoomed.offsetX, transform.offsetX, 0.01f)
        assertEquals(zoomed.offsetY, transform.offsetY, 0.01f)
    }

    @Test
    fun `a ratio frame off the edge of the picture slides back onto it`() {
        val pushedRight = CropTransform(scale = 1f, offsetX = 2000f, offsetY = 0f)

        val transform = transformForAspect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, pushedRight)
        val rect = cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, transform)

        assertEquals(0, rect.left)
        assertWithinOnePixel(CropAspect.SQUARE, rect)
        assertCoversFrame(CropAspect.SQUARE, transform, rect)
    }

    @Test
    fun `panning moves the export by the distance shown on screen`() {
        val start = transformForAspect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, CropTransform(scale = 2f))
        val panned = start.copy(offsetX = start.offsetX - 100f)
        val total = cropFitScale(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight) * start.scale

        val before = cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, start)
        val after = cropSourceRect(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight, CropAspect.SQUARE, panned)

        assertEquals(100f / total, (after.left - before.left).toFloat(), 1f)
    }

    @Test
    fun `exports stay on the picture and within a pixel of the ratio across sizes, zooms and pans`() {
        val bitmaps = listOf(1080 to 2400, 4096 to 2304, 1000 to 1000, 333 to 4096, 4096 to 37)
        val viewports = listOf(1080 to 1700, 1700 to 1080, 720 to 720, 411 to 1999)
        val transforms = listOf(
            CropTransform(),
            CropTransform(scale = 0.5f),
            CropTransform(scale = 3f, offsetX = 250f, offsetY = -400f),
            CropTransform(scale = 1.3f, offsetX = -5000f, offsetY = 5000f),
        )
        for ((bw, bh) in bitmaps) for ((vw, vh) in viewports) for (transform in transforms) {
            for (aspect in CropAspect.entries) {
                val reframed = transformForAspect(bw, bh, vw, vh, aspect, transform)
                for (candidate in listOf(transform, reframed)) {
                    val rect = cropSourceRect(bw, bh, vw, vh, aspect, candidate)
                    val case = "$aspect ${bw}x$bh in ${vw}x$vh at $candidate -> $rect"
                    assertTrue(case, rect.left >= 0 && rect.top >= 0 && rect.width >= 1 && rect.height >= 1)
                    assertTrue(case, rect.left + rect.width <= bw && rect.top + rect.height <= bh)
                    if (aspect.ratio != null) assertWithinOnePixel(aspect, rect, case)
                }
            }
        }
    }

    private fun assertWithinOnePixel(aspect: CropAspect, rect: CropRect, case: String = "$aspect -> $rect") {
        val ratio = aspect.ratio!!
        assertTrue(case, abs(rect.width - rect.height * ratio) <= 1f || abs(rect.height - rect.width / ratio) <= 1f)
    }

    /** Nothing was trimmed: the export is the whole frame, mapped back to the picture. */
    private fun assertCoversFrame(aspect: CropAspect, transform: CropTransform, rect: CropRect) {
        val frame = cropFrame(viewportWidth, viewportHeight, aspect)
        val total = cropFitScale(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight) * transform.scale
        assertEquals(aspect.name, frame.width / total, rect.width.toFloat(), 1f)
        assertEquals(aspect.name, frame.height / total, rect.height.toFloat(), 1f)
    }
}
