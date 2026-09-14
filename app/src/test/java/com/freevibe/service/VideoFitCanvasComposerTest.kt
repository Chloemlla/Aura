package com.freevibe.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFitCanvasComposerTest {
    @Test
    fun `poster sampling bounds landscape portrait and ultrawide frames`() {
        assertEquals(512 to 288, boundedVideoFrameSize(7680, 4320))
        assertEquals(288 to 512, boundedVideoFrameSize(2160, 3840))
        assertEquals(512 to 128, boundedVideoFrameSize(7680, 1920))
        assertEquals(64, boundedBitmapSampleSize(32_768, 16_384))
    }

    @Test
    fun `even video dimensions stay inside the pixel budget`() {
        val normal = boundedVideoCanvasSize(1_440, 3_088, 8_000_000L)
        val extreme = boundedVideoCanvasSize(Int.MAX_VALUE, 1, 8_000_000L)

        assertEquals(0, normal.width % 2)
        assertEquals(0, normal.height % 2)
        assertTrue(normal.pixels <= 8_000_000L)
        assertEquals(0, extreme.width % 2)
        assertEquals(0, extreme.height % 2)
        assertTrue(extreme.pixels <= 8_000_000L)
    }

    @Test
    fun `video canvas uses one cached background and never blurs every frame`() {
        val args = videoFitCanvasFfmpegArgs(
            ffmpegPath = "/ffmpeg",
            backgroundPath = "/background.png",
            inputPath = "/video.mp4",
            outputPath = "/output.mp4",
            width = 1080,
            height = 2400,
        )
        val command = args.joinToString(" ")

        assertTrue(command.contains("-loop 1 -framerate 30 -i /background.png"))
        assertTrue(command.contains("-i /video.mp4"))
        assertTrue(command.contains("force_original_aspect_ratio=decrease"))
        assertTrue(command.contains("overlay=(W-w)/2:(H-h)/2"))
        assertFalse(command.contains("gblur"))
        assertFalse(command.contains("boxblur"))
    }
}
