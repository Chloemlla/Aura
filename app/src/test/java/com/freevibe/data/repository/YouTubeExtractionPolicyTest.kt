package com.freevibe.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeExtractionPolicyTest {

    @Test
    fun `yt-dlp serves when NewPipe is killed`() = runTest {
        val result = executeYouTubeFailover(
            primaryEngine = YouTubeExtractionEngine.NEWPIPE,
            fallbackEngine = YouTubeExtractionEngine.YT_DLP,
            primary = { throw IllegalStateException("NewPipe test hook") },
            fallback = { "https://media.example/audio.m4a" },
            isUsable = String::isNotBlank,
        )

        assertEquals("https://media.example/audio.m4a", result.value)
        assertEquals(YouTubeExtractionEngine.YT_DLP, result.engine)
        assertTrue(result.usedFallback)
        assertTrue(result.primaryError is IllegalStateException)
        assertEquals(YouTubeExtractionMode.BACKUP_ACTIVE, result.toExtractionStatus().mode)
    }

    @Test
    fun `NewPipe serves when yt-dlp is killed`() = runTest {
        val result = executeYouTubeFailover(
            primaryEngine = YouTubeExtractionEngine.YT_DLP,
            fallbackEngine = YouTubeExtractionEngine.NEWPIPE,
            primary = { throw IllegalStateException("yt-dlp test hook") },
            fallback = { "https://media.example/audio.webm" },
            isUsable = String::isNotBlank,
        )

        assertEquals("https://media.example/audio.webm", result.value)
        assertEquals(YouTubeExtractionEngine.NEWPIPE, result.engine)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `both killed produces an unavailable result with both failures`() = runTest {
        val result = executeYouTubeFailover<String>(
            primaryEngine = YouTubeExtractionEngine.NEWPIPE,
            fallbackEngine = YouTubeExtractionEngine.YT_DLP,
            primary = { null },
            fallback = { throw IllegalStateException("yt-dlp test hook") },
            isUsable = String::isNotBlank,
        )

        assertNull(result.value)
        assertNull(result.engine)
        assertTrue(result.primaryError is YouTubeExtractorReturnedNoResult)
        assertTrue(result.fallbackError is IllegalStateException)
        assertEquals(YouTubeExtractionMode.UNAVAILABLE, result.toExtractionStatus().mode)
        assertTrue(
            YouTubeExtractionUnavailableException(result.primaryError, result.fallbackError)
                .message
                .orEmpty()
                .contains("YouTube changed something"),
        )
    }
}
