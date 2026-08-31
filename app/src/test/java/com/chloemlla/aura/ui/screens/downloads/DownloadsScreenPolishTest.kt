package com.chloemlla.aura.ui.screens.downloads

import android.content.res.Resources
import com.chloemlla.aura.data.model.DownloadEntity
import com.chloemlla.aura.service.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
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
}
