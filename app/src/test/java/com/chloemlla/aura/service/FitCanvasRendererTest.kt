package com.chloemlla.aura.service

import android.graphics.Bitmap
import android.graphics.Color
import com.chloemlla.aura.data.model.FitCanvasMode
import com.chloemlla.aura.data.model.FitCanvasStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FitCanvasRendererTest {
    @Test
    fun `transparent portrait source keeps custom canvas visible`() {
        val source = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            setPixel(0, 1, Color.WHITE)
            setPixel(1, 1, Color.WHITE)
        }

        val result = FitCanvasRenderer.render(
            source = source,
            targetWidth = 8,
            targetHeight = 8,
            style = FitCanvasStyle(FitCanvasMode.CUSTOM_COLOR, 0xFF123456.toInt()),
        )

        assertEquals(0xFF123456.toInt(), result.bitmap.getPixel(0, 0))
        assertEquals(8, result.bitmap.width)
        assertEquals(8, result.bitmap.height)
        source.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `dominant canvas derives an opaque fallback from source pixels`() {
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF336699.toInt())
        }

        val result = FitCanvasRenderer.renderBackground(
            source = source,
            targetWidth = 20,
            targetHeight = 40,
            style = FitCanvasStyle(FitCanvasMode.DOMINANT_COLOR),
        )

        assertRgbNear(0xFF336699.toInt(), result.resolvedCanvas.color)
        assertTrue(Color.alpha(result.bitmap.getPixel(0, 0)) == 255)
        source.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `dominant canvas uses the largest palette population instead of averaging colors`() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            setPixel(0, 0, Color.RED)
            setPixel(1, 0, Color.RED)
            setPixel(2, 0, Color.RED)
        }

        val result = FitCanvasRenderer.renderBackground(
            source = source,
            targetWidth = 20,
            targetHeight = 40,
            style = FitCanvasStyle(FitCanvasMode.DOMINANT_COLOR),
        )

        val resolved = result.resolvedCanvas.color
        assertTrue(Color.blue(resolved) >= 240)
        assertTrue(Color.red(resolved) <= 16)
        assertTrue(Color.green(resolved) <= 16)
        source.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `transparent pixels do not influence the palette canvas`() {
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0x00FF0000)
            repeat(10) { x -> setPixel(x, 0, Color.BLUE) }
        }

        val result = FitCanvasRenderer.renderBackground(
            source = source,
            targetWidth = 20,
            targetHeight = 40,
            style = FitCanvasStyle(FitCanvasMode.DOMINANT_COLOR),
        )

        val resolved = result.resolvedCanvas.color
        assertTrue(Color.blue(resolved) >= 240)
        assertTrue(Color.red(resolved) <= 16)
        source.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `blurred canvas keeps its working allocation bounded`() {
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }

        val result = FitCanvasRenderer.renderBackground(
            source = source,
            targetWidth = 10_000,
            targetHeight = 10_000,
            style = FitCanvasStyle(FitCanvasMode.BLURRED_EDGE),
        )

        assertTrue(result.bitmap.width.toLong() * result.bitmap.height <= FitCanvasRenderer.MAX_OUTPUT_PIXELS)
        source.recycle()
        result.bitmap.recycle()
    }

    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int = 8) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }
}
