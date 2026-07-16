package com.freevibe.service

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFeedCacheTest {

    @Test
    fun `round trip keeps feed metadata and fresh preview urls`() {
        val now = 10_000_000L
        val sounds = listOf(sound("yt_fast", ContentSource.YOUTUBE), sound("local", ContentSource.BUNDLED))

        val decoded = decodeSoundFeedCache(
            encodeSoundFeedCache(CachedSoundFeed(sounds, now)),
            nowMs = now + 1_000L,
        )

        assertNotNull(decoded)
        assertEquals(sounds, decoded?.sounds)
    }

    @Test
    fun `expired signed urls are stripped while feed and stable urls remain warm`() {
        val now = 10_000_000L
        val sounds = listOf(sound("yt_fast", ContentSource.YOUTUBE), sound("bundled", ContentSource.BUNDLED))

        val decoded = decodeSoundFeedCache(
            encodeSoundFeedCache(CachedSoundFeed(sounds, now)),
            nowMs = now + SOUND_PREVIEW_URL_TTL_MS + 1L,
        )

        assertEquals("", decoded?.sounds?.first()?.previewUrl)
        assertEquals("https://cdn.example.com/bundled.mp3", decoded?.sounds?.last()?.previewUrl)
    }

    @Test
    fun `stale feed expires and cache keys separate tabs and queries`() {
        val now = 10_000_000L
        val raw = encodeSoundFeedCache(CachedSoundFeed(listOf(sound("one", ContentSource.YOUTUBE)), now))

        assertNull(decodeSoundFeedCache(raw, now + SOUND_FEED_CACHE_TTL_MS + 1L))
        assertTrue(soundFeedCacheKey("RINGTONES", "") != soundFeedCacheKey("ALARMS", ""))
        assertTrue(soundFeedCacheKey("SEARCH", "rain") != soundFeedCacheKey("SEARCH", "ocean"))
    }

    @Test
    fun `write sweep removes expired entries and least recently used overflow`() {
        val now = 100_000_000L
        fun entry(key: String, cachedAtMs: Long, lastAccessedAtMs: Long) = SoundFeedCacheEntry(
            key = key,
            raw = encodeSoundFeedCache(
                CachedSoundFeed(listOf(sound(key, ContentSource.BUNDLED)), cachedAtMs),
            ),
            lastAccessedAtMs = lastAccessedAtMs,
        )

        val removals = soundFeedCacheKeysToRemove(
            entries = listOf(
                entry("expired", now - SOUND_FEED_CACHE_TTL_MS - 1L, now - 1L),
                entry("least-recent", now - 4_000L, now - 3_000L),
                entry("middle", now - 3_000L, now - 2_000L),
                entry("recent", now - 2_000L, now - 1_000L),
            ),
            nowMs = now,
            maxKeys = 2,
        )

        assertEquals(setOf("expired", "least-recent"), removals)
    }

    private fun sound(id: String, source: ContentSource) = Sound(
        id = id,
        source = source,
        name = "Sound $id",
        previewUrl = "https://cdn.example.com/$id.mp3",
        downloadUrl = "https://cdn.example.com/$id.mp3",
        duration = 8.0,
        tags = listOf("clean", "short"),
        uploaderName = "Aura",
    )
}
