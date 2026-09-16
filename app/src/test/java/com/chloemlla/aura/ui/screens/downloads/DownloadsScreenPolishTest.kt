package com.chloemlla.aura.ui.screens.downloads

import android.content.res.Resources
import com.chloemlla.aura.data.model.DownloadEntity
import com.chloemlla.aura.data.model.MediaTechnicalMetadata
import com.chloemlla.aura.service.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadsScreenPolishTest {

    private val resources: Resources
        get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `download history summary includes file health and date`() {
        val download = DownloadEntity(
            id = "wall-1",
            source = "WALLHAVEN",
            type = "WALLPAPER",
            localPath = "",
            name = "Night grid",
        )

        assertEquals(
            "Night grid. File missing. Downloaded Jun 12, 9:30 AM.",
            downloadHistorySummary(
                download = download,
                broken = true,
                sourceUnavailable = false,
                downloadedAtLabel = "Jun 12, 9:30 AM",
                resources = resources,
            ),
        )
        assertEquals("Review missing file", downloadOpenActionLabel(download, broken = true, resources = resources))
    }

    @Test
    fun `active download status labels expose progress and failures`() {
        assertEquals(
            "42 percent downloaded",
            downloadProgressStatusLabel(
                DownloadProgress(
                    id = "sound-1",
                    fileName = "tone.mp3",
                    progress = 0.42f,
                    totalBytes = 1000L,
                    downloadedBytes = 420L,
                ),
                resources,
            ),
        )
        assertEquals(
            "Download failed: Network timeout",
            downloadProgressStatusLabel(
                DownloadProgress(
                    id = "sound-2",
                    fileName = "tone.mp3",
                    progress = 0.1f,
                    totalBytes = 1000L,
                    downloadedBytes = 100L,
                    error = "Network timeout",
                ),
                resources,
            ),
        )
    }

    @Test
    fun `video rows open with video mime and expose copy metadata`() {
        val video = DownloadEntity(
            id = "video-1",
            source = "REDDIT",
            type = "VIDEO",
            localPath = "/path/loop.webm",
            name = "Night loop",
        )

        assertEquals("video/*", downloadOpenMimeType(video))
        assertEquals("Video", downloadHealthLabel(video, broken = false, sourceUnavailable = false, resources = resources))
        assertEquals(
            "3840×2160 · H264 · 1:02 · 12 MB · HDR",
            formatMediaTechnicalMetadata(
                MediaTechnicalMetadata(
                    codec = "H264",
                    width = 3840,
                    height = 2160,
                    durationMs = 62_000,
                    sizeBytes = 12L * 1024L * 1024L,
                    isHdr = true,
                ),
            ),
        )
        assertEquals(
            "Night loop. Video. Downloaded Jun 12, 9:30 AM. Original: 1920×1080 · H264 · 8 MB. Optimized copy: 1080×1920 · H264 · 3 MB.",
            downloadHistorySummary(
                download = video,
                broken = false,
                sourceUnavailable = false,
                downloadedAtLabel = "Jun 12, 9:30 AM",
                copyDetails = listOf(
                    "Original" to "1920×1080 · H264 · 8 MB",
                    "Optimized copy" to "1080×1920 · H264 · 3 MB",
                ),
                resources = resources,
            ),
        )
    }

    @Test
    fun `absolute and file locators are checked while content uris stay provider backed`() {
        assertEquals(
            "loop.mp4",
            downloadLocalFile("/data/user/0/com.chloemlla.aura/files/media_originals/loop.mp4")?.name,
        )
        assertEquals(
            "loop.mp4",
            downloadLocalFile("file:///data/user/0/com.chloemlla.aura/files/media_originals/loop.mp4")?.name,
        )
        assertNull(downloadLocalFile("content://media/external/video/media/42"))
    }
}
