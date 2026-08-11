package com.chloemlla.aura.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only "this resource is gone" may permanently disable a saved item. Everything
 * else — refused, throttled, unreachable — is about the attempt, not the item, so
 * it must stay recoverable. Reddit's 403 used to be treated as a permanent
 * discontinuation, which stuck an unavailable badge on saved posts that were
 * fine.
 */
class SourceAvailabilityPolicyTest {

    private fun assess(source: ContentSource, message: String?) =
        assessSourceAvailability(source, IllegalStateException(message))

    @Test
    fun `sourceUnavailableReasonForFailure classifies explicit removed statuses`() {
        assertEquals(
            "Pexels media is unavailable or removed",
            sourceUnavailableReasonForFailure(
                ContentSource.PEXELS,
                IllegalStateException("Download failed: HTTP 404"),
            ),
        )
        assertEquals(
            "YouTube media is unavailable or removed (gone)",
            sourceUnavailableReasonForFailure(
                ContentSource.YOUTUBE,
                IllegalStateException("HTTP 410"),
            ),
        )
        assertEquals(
            "Source post is unavailable or removed",
            sourceUnavailableReasonForFailure(
                ContentSource.REDDIT,
                IllegalStateException("post was removed"),
            ),
        )
    }

    @Test
    fun `404 and 410 are permanent`() {
        listOf("HTTP 404 Not Found", "HTTP 410", "content not found", "post removed").forEach {
            val assessment = assess(ContentSource.PEXELS, it)
            assertEquals(it, SourceAvailabilityVerdict.PERMANENT, assessment.verdict)
            assertTrue(it, assessment.reason!!.isNotBlank())
        }
    }

    @Test
    fun `403 is transient and never persists an unavailable state`() {
        val assessment = assess(ContentSource.REDDIT, "HTTP 403 Forbidden")

        assertEquals(SourceAvailabilityVerdict.TRANSIENT, assessment.verdict)
        assertNull(assessment.reason)
        assertNull(
            sourceUnavailableReasonForFailure(ContentSource.REDDIT, IllegalStateException("HTTP 403")),
        )
    }

    @Test
    fun `sourceUnavailableReasonForFailure ignores transient failures`() {
        assertNull(sourceUnavailableReasonForFailure(ContentSource.PIXABAY, IllegalStateException("HTTP 500")))
        assertNull(sourceUnavailableReasonForFailure(ContentSource.PIXABAY, IllegalStateException("HTTP 403")))
        assertNull(sourceUnavailableReasonForFailure(ContentSource.PIXABAY, java.net.SocketTimeoutException("timeout")))
        assertNull(sourceUnavailableReasonForFailure(ContentSource.PIXABAY, null))
    }

    @Test
    fun `rate limits timeouts and server errors are transient`() {
        listOf(
            "HTTP 429 Too Many Requests",
            "HTTP 500",
            "HTTP 503 Service Unavailable",
            "Read timed out",
            "Connection reset",
        ).forEach {
            assertEquals(it, SourceAvailabilityVerdict.TRANSIENT, assess(ContentSource.WALLHAVEN, it).verdict)
            assertNull(it, sourceUnavailableReasonForFailure(ContentSource.WALLHAVEN, IllegalStateException(it)))
        }
    }

    @Test
    fun `an unrecognised failure is neither permanent nor transient`() {
        val assessment = assess(ContentSource.WALLHAVEN, "decode failed")

        assertEquals(SourceAvailabilityVerdict.UNKNOWN, assessment.verdict)
        assertNull(assessment.reason)
    }

    @Test
    fun `a removal claim wins over a transient code in the same message`() {
        assertEquals(
            SourceAvailabilityVerdict.PERMANENT,
            assess(ContentSource.YOUTUBE, "HTTP 403 after redirect: video removed").verdict,
        )
    }

    @Test
    fun `a null message is not treated as a removal`() {
        assertEquals(
            SourceAvailabilityVerdict.UNKNOWN,
            assessSourceAvailability(ContentSource.WALLHAVEN, null).verdict,
        )
    }

    @Test
    fun `reason text stays provider specific`() {
        assertEquals(
            "YouTube media is unavailable or removed",
            sourceUnavailableReasonForMessage(ContentSource.YOUTUBE.name, "HTTP 404"),
        )
        assertEquals(
            "Source content is unavailable or removed",
            sourceUnavailableReasonForMessage("UNKNOWN_SOURCE", "HTTP 404"),
        )
    }
}
