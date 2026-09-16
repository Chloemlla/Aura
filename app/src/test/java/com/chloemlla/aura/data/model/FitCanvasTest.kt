package com.chloemlla.aura.data.model

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCanvasTest {
    @Test
    fun `presentation only accepts fit explicitly`() {
        assertEquals(WALLPAPER_PRESENTATION_FIT, normalizeWallpaperPresentation(" FIT "))
        assertEquals(WALLPAPER_PRESENTATION_FILL, normalizeWallpaperPresentation("crop"))
        assertEquals(WALLPAPER_PRESENTATION_FILL, normalizeWallpaperPresentation(null))
    }

    @Test
    fun `custom colors are always opaque`() {
        val resolved = resolveFitCanvas(
            style = FitCanvasStyle(FitCanvasMode.CUSTOM_COLOR, 0x00112233),
            dominantColor = null,
            posterAvailable = false,
        )

        assertEquals(0xFF112233.toInt(), resolved.color)
    }

    @Test
    fun `dominant color falls back to amoled black when source has no color`() {
        val resolved = resolveFitCanvas(
            style = FitCanvasStyle(FitCanvasMode.DOMINANT_COLOR),
            dominantColor = 0,
            posterAvailable = false,
        )

        assertEquals(FitCanvasMode.AMOLED_BLACK, resolved.mode)
        assertEquals(Color.BLACK, resolved.color)
        assertEquals(FitCanvasFallbackReason.SOURCE_COLOR_UNAVAILABLE, resolved.fallbackReason)
    }

    @Test
    fun `missing video poster uses dominant fallback when available`() {
        val resolved = resolveFitCanvas(
            style = FitCanvasStyle(FitCanvasMode.BLURRED_EDGE),
            dominantColor = 0xFF234567.toInt(),
            posterAvailable = false,
        )

        assertEquals(FitCanvasMode.DOMINANT_COLOR, resolved.mode)
        assertEquals(0xFF234567.toInt(), resolved.color)
        assertEquals(FitCanvasFallbackReason.POSTER_UNAVAILABLE, resolved.fallbackReason)
    }

    @Test
    fun `hdr blur uses a deterministic solid fallback`() {
        val resolved = resolveFitCanvas(
            style = FitCanvasStyle(FitCanvasMode.BLURRED_EDGE),
            dominantColor = 0xFF654321.toInt(),
            posterAvailable = true,
            isHdr = true,
        )

        assertEquals(FitCanvasMode.DOMINANT_COLOR, resolved.mode)
        assertEquals(FitCanvasFallbackReason.HDR_BLUR_UNSUPPORTED, resolved.fallbackReason)
    }

    @Test
    fun `fit rectangle centers portrait landscape and ultrawide sources`() {
        assertEquals(FitCanvasRect(270f, 0f, 810f, 1920f), fitCanvasRect(1080, 3840, 1080, 1920))
        assertEquals(FitCanvasRect(0f, 656.25f, 1080f, 1263.75f), fitCanvasRect(1920, 1080, 1080, 1920))
        assertEquals(FitCanvasRect(0f, 825f, 1080f, 1095f), fitCanvasRect(3840, 960, 1080, 1920))
    }

    @Test
    fun `canvas dimensions stay inside memory pixel budget`() {
        val bounded = boundedFitCanvasSize(10_000, 10_000)
        val pathologicalUltrawide = boundedFitCanvasSize(100_000_000, 10)

        assertTrue(bounded.pixels <= 8_000_000L)
        assertTrue(bounded.width > 0)
        assertTrue(bounded.height > 0)
        assertTrue(pathologicalUltrawide.pixels <= 8_000_000L)
    }
}
