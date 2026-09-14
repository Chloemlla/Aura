package com.freevibe.service

import android.content.Context
import com.freevibe.data.model.DownloadEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoWallpaperStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `gif selections are detected by mime type and file name`() {
        assertTrue(isGifVideoWallpaperSelection("image/gif", "wallpaper.bin"))
        assertTrue(isGifVideoWallpaperSelection(null, "wallpaper.GIF"))
        assertFalse(isGifVideoWallpaperSelection("video/mp4", "wallpaper.mp4"))
    }

    @Test
    fun `video wallpaper extension prefers known video formats`() {
        assertEquals("gif", resolveVideoWallpaperExtension("image/gif", "clip.bin"))
        assertEquals("webm", resolveVideoWallpaperExtension("video/webm", "clip.mp4"))
        assertEquals("3gp", resolveVideoWallpaperExtension(null, "clip.3gp"))
        assertEquals("mov", resolveVideoWallpaperExtension("video/mp4", "clip.mov"))
        assertEquals("mkv", resolveVideoWallpaperExtension("video/x-matroska", "clip.mp4"))
        assertEquals("mp4", resolveVideoWallpaperExtension("video/mp4", "clip.bin"))
    }

    @Test
    fun `video wallpaper picker accepts video and gif mime types`() {
        assertEquals(listOf("video/*", "image/gif"), videoWallpaperMimeTypes().toList())
    }

    @Test
    fun `stored motion extension follows verified bytes instead of a stale url suffix`() {
        assertEquals(
            "mp4",
            resolvedMotionExtension(
                SniffedMediaType(MediaFamily.CONTAINER, "video/mp4", "mp4"),
                "webm",
            ),
        )
        assertEquals(
            "gif",
            resolvedMotionExtension(
                SniffedMediaType(MediaFamily.IMAGE, "image/gif", "gif"),
                "mp4",
            ),
        )
    }

    @Test
    fun `download history identity is stable across feed reapply`() {
        assertEquals(
            "video:reddit:rd_abc123",
            downloadedVideoHistoryId("Reddit", "rd_abc123"),
        )
    }

    @Test
    fun `saved feed original is reused only while its bytes still match`() = runTest {
        val filesDir = temporaryFolder.newFolder("video-reapply")
        val original = File(filesDir, "media_originals/saved.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val record = DownloadEntity(
            id = downloadedVideoHistoryId("Reddit", "rd_saved"),
            source = "Reddit",
            type = "VIDEO",
            localPath = original.absolutePath,
            originalSha256 = sha256File(original),
            originalSizeBytes = original.length(),
        )
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val copyStore = mockk<MediaCopyStore>()
        coEvery { copyStore.findOriginal(record.id, "") } returns (record to original.absolutePath)
        val storage = VideoWallpaperStorage(context, mockk(), copyStore)

        assertEquals(original.canonicalPath, storage.findSavedDownloadedVideo("rd_saved", "Reddit")?.canonicalPath)

        original.writeBytes(byteArrayOf(4, 3, 2, 1))
        assertNull(storage.findSavedDownloadedVideo("rd_saved", "Reddit"))
    }

    @Test
    fun `video wallpaper scale mode defaults safely`() {
        assertEquals(VIDEO_WALLPAPER_SCALE_MODE_FIT, normalizeVideoWallpaperScaleMode(" FIT "))
        assertEquals(VIDEO_WALLPAPER_SCALE_MODE_ZOOM, normalizeVideoWallpaperScaleMode("crop"))
        assertEquals(VIDEO_WALLPAPER_SCALE_MODE_ZOOM, normalizeVideoWallpaperScaleMode(null))
    }

    @Test
    fun `video probe accepts real motion files`() {
        val failure = videoWallpaperProbeFailure(
            VideoWallpaperProbe(
                hasVideo = true,
                durationMs = 5_000L,
                width = 1080,
                height = 1920,
                mimeType = "video/mp4",
            ),
        )

        assertEquals(null, failure)
    }

    @Test
    fun `video probe rejects audio-only files`() {
        val failure = videoWallpaperProbeFailure(
            VideoWallpaperProbe(
                hasVideo = false,
                durationMs = 5_000L,
                width = 0,
                height = 0,
                mimeType = "audio/mp4",
            ),
        )

        assertEquals("Selected file does not contain a video track", failure)
    }

    @Test
    fun `video probe rejects unreadable dimensions`() {
        val failure = videoWallpaperProbeFailure(
            VideoWallpaperProbe(
                hasVideo = true,
                durationMs = 5_000L,
                width = 0,
                height = 1920,
                mimeType = "video/mp4",
            ),
        )

        assertEquals("Selected video dimensions could not be read", failure)
    }

    @Test
    fun `video probe rejects too-short clips`() {
        val failure = videoWallpaperProbeFailure(
            VideoWallpaperProbe(
                hasVideo = true,
                durationMs = 250L,
                width = 1080,
                height = 1920,
                mimeType = "video/mp4",
            ),
        )

        assertEquals("Selected video is too short", failure)
    }

    @Test
    fun `gif header validation accepts gif signatures only`() {
        assertTrue(hasValidGifHeader("GIF87a".toByteArray()))
        assertTrue(hasValidGifHeader("GIF89a".toByteArray()))
        assertFalse(hasValidGifHeader("GIF00a".toByteArray()))
        assertFalse(hasValidGifHeader("MP4....".toByteArray()))
        assertFalse(hasValidGifHeader(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `gif structure validation accepts complete animated image data`() {
        assertTrue(GifStructureValidator.isValid(validAnimatedGif()))
    }

    @Test
    fun `gif structure validation rejects frames that decode too few pixels`() {
        val malformed = validAnimatedGif().also { bytes ->
            bytes[32] = 0x02
            bytes[34] = 0x02
        }

        assertFalse(GifStructureValidator.isValid(malformed))
    }

    @Test
    fun `gif structure validation rejects truncated and trailing data`() {
        val valid = validAnimatedGif()

        assertFalse(GifStructureValidator.isValid(valid.copyOf(valid.size - 1)))
        assertFalse(GifStructureValidator.isValid(valid + 0x00))
    }

    private fun validAnimatedGif(): ByteArray = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
        0x02, 0x00, 0x02, 0x00,
        0xF0.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x44, 0x01, 0x00,
        0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x4C, 0x01, 0x00,
        0x3B,
    )
}
