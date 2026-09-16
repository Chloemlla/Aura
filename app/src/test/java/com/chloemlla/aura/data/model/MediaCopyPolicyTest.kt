package com.chloemlla.aura.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCopyPolicyTest {

    @Test
    fun `oversized wallpaper gets a separate apply copy`() {
        val decision = decideWallpaperOptimization(
            metadata = MediaTechnicalMetadata(width = 12_000, height = 8_000, mimeType = "image/jpeg"),
            targetWidth = 1_080,
            targetHeight = 2_340,
            hasPixelTransform = false,
        )

        assertTrue(decision.required)
        assertTrue(decision.reason.contains("Oversized"))
    }

    @Test
    fun `device sized wallpaper keeps its original bytes`() {
        assertFalse(
            decideWallpaperOptimization(
                metadata = MediaTechnicalMetadata(width = 1_440, height = 3_088, mimeType = "image/png"),
                targetWidth = 1_440,
                targetHeight = 3_088,
                hasPixelTransform = false,
            ).required,
        )
    }

    @Test
    fun `incompatible av1 webm video gets an h264 mp4 copy`() {
        val decision = decideVideoOptimization(
            metadata = MediaTechnicalMetadata(
                width = 1_920,
                height = 1_080,
                mimeType = "video/webm",
                codec = "AV1",
            ),
            fileExtension = "webm",
            targetWidth = 1_080,
            targetHeight = 2_340,
            fitRequested = false,
        )

        assertTrue(decision.required)
        assertTrue(decision.reason.contains("H.264"))
    }

    @Test
    fun `compatible h264 mp4 can be applied unchanged`() {
        assertFalse(
            decideVideoOptimization(
                metadata = MediaTechnicalMetadata(
                    width = 1_920,
                    height = 1_080,
                    mimeType = "video/mp4",
                    codec = "H264",
                ),
                fileExtension = "mp4",
                targetWidth = 1_080,
                targetHeight = 2_340,
                fitRequested = false,
            ).required,
        )
    }

    @Test
    fun `small gif stays original while oversized gif gets a bounded working copy`() {
        val small = decideVideoOptimization(
            metadata = MediaTechnicalMetadata(width = 720, height = 1_280, mimeType = "image/gif"),
            fileExtension = "gif",
            targetWidth = 1_080,
            targetHeight = 2_340,
            fitRequested = true,
        )
        val oversized = decideVideoOptimization(
            metadata = MediaTechnicalMetadata(width = 7_680, height = 4_320, mimeType = "image/gif"),
            fileExtension = "gif",
            targetWidth = 1_080,
            targetHeight = 2_340,
            fitRequested = true,
        )

        assertFalse(small.required)
        assertTrue(oversized.required)
        assertTrue(oversized.reason.contains("H.264"))
    }

    @Test
    fun `fit canvas and edited sound always use separate working copies`() {
        assertTrue(
            decideVideoOptimization(
                metadata = MediaTechnicalMetadata(mimeType = "video/mp4", codec = "H264"),
                fileExtension = "mp4",
                targetWidth = 1_080,
                targetHeight = 2_340,
                fitRequested = true,
            ).required,
        )
        assertTrue(
            decideSoundOptimization(
                metadata = MediaTechnicalMetadata(mimeType = "audio/mpeg", codec = "MP3"),
                edited = true,
            ).required,
        )
        assertTrue(
            decideSoundOptimization(
                metadata = MediaTechnicalMetadata(mimeType = "audio/x-wav", codec = "PCM"),
                edited = false,
            ).required,
        )
        assertTrue(
            decideSoundOptimization(
                metadata = MediaTechnicalMetadata(),
                edited = false,
            ).required,
        )
    }

    @Test
    fun `hdr original is preserved and an unavoidable transform is labeled sdr`() {
        val direct = decideWallpaperOptimization(
            metadata = MediaTechnicalMetadata(
                width = 1_440,
                height = 3_088,
                mimeType = "image/jpeg",
                isHdr = true,
            ),
            targetWidth = 1_440,
            targetHeight = 3_088,
            hasPixelTransform = false,
        )
        val transformed = decideWallpaperOptimization(
            metadata = MediaTechnicalMetadata(isHdr = true),
            targetWidth = 1_440,
            targetHeight = 3_088,
            hasPixelTransform = true,
        )

        assertFalse(direct.required)
        assertTrue(transformed.required)
        assertTrue(wallpaperOptimizationReason(transformed.reason, true).contains("SDR working copy"))
    }
}
