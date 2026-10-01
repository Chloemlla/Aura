package com.freevibe.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import com.freevibe.data.remote.tiktok.TIKTOK_SOUND_ID_PREFIX
import com.freevibe.data.repository.TikTokSoundRepository
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TikTokAudioExtractorCleanupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `half-written files from a run that died are swept before the next download`() = runBlocking {
        val directory = File(context.cacheDir, "tiktok-audio").apply { mkdirs() }
        val staleVideo = File(directory, "tiktok_111111111.mp4.part").apply { writeBytes(ByteArray(64)) }
        val staleAudio = File(directory, "tiktok_111111111.m4a.part").apply { writeBytes(ByteArray(8)) }
        val kept = File(directory, "tiktok_222222222.m4a").apply { writeBytes(ByteArray(8)) }
        val repository = mockk<TikTokSoundRepository> {
            coEvery { playableMediaUrl(any(), any()) } returns null
        }
        val extractor = TikTokAudioExtractor(context, mockk(relaxed = true), repository)

        val error = runCatching { extractor.extract(tiktokSound("7300000000000000001")) }.exceptionOrNull()

        assertTrue("$error", error is IOException)
        assertFalse(staleVideo.exists())
        assertFalse(staleAudio.exists())
        assertTrue("finished extractions stay", kept.exists())
    }

    @Test
    fun `a stop that throws after a failed remux still releases both and keeps the first error`() {
        val calls = mutableListOf<String>()
        val failure = IOException("TikTok clip sound track is empty")

        closeRemuxResources(
            failure = failure,
            stopMuxer = { calls += "stop"; throw IllegalStateException("muxer stopped with nothing written") },
            releaseMuxer = { calls += "releaseMuxer" },
            releaseExtractor = { calls += "releaseExtractor" },
        )

        assertEquals(listOf("stop", "releaseMuxer", "releaseExtractor"), calls)
        assertEquals("muxer stopped with nothing written", failure.suppressed.single().message)
    }

    @Test
    fun `a stop that throws after a good remux is reported once both are released`() {
        val calls = mutableListOf<String>()
        val stopError = IllegalStateException("stop failed")

        val thrown = runCatching {
            closeRemuxResources(
                failure = null,
                stopMuxer = { throw stopError },
                releaseMuxer = { calls += "releaseMuxer" },
                releaseExtractor = { calls += "releaseExtractor" },
            )
        }.exceptionOrNull()

        assertSame(stopError, thrown)
        assertEquals(listOf("releaseMuxer", "releaseExtractor"), calls)
    }

    @Test
    fun `the extractor is released even when releasing the muxer throws`() {
        var extractorReleased = false

        runCatching {
            closeRemuxResources(
                failure = null,
                stopMuxer = null,
                releaseMuxer = { throw IllegalStateException("release failed") },
                releaseExtractor = { extractorReleased = true },
            )
        }

        assertTrue(extractorReleased)
    }

    private fun tiktokSound(videoId: String) = Sound(
        id = "$TIKTOK_SOUND_ID_PREFIX$videoId",
        source = ContentSource.TIKTOK,
        name = "Clip",
        previewUrl = "https://example.com/clip.mp4",
        downloadUrl = "https://example.com/clip.mp4",
    )
}
