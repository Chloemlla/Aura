package com.freevibe.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesManagerRedditMetadataTest {

    @Test
    fun `page metadata is globally bounded and evicts the oldest cursor`() {
        var raw = ""
        repeat(MAX_REDDIT_RSS_PAGE_METADATA_ENTRIES + 5) { index ->
            raw = updateRedditRssPageMetadata(
                raw = raw,
                feedHash = index,
                requestAfter = "t3_request$index",
                nextCursor = "t3_next$index",
            )
        }

        val decoded = decodeRedditRssPageMetadata(raw)
        assertEquals(MAX_REDDIT_RSS_PAGE_METADATA_ENTRIES, decoded.size)
        assertNull(decoded.find { it.feedHash == 0 })
        assertEquals("t3_next68", decoded.last().nextCursor)
    }

    @Test
    fun `rewriting a cursor replaces and refreshes its recency entry`() {
        var raw = updateRedditRssPageMetadata("", 42, null, "t3_first")
        raw = updateRedditRssPageMetadata(raw, 7, null, "__END__")
        raw = updateRedditRssPageMetadata(raw, 42, null, "t3_second")

        val decoded = decodeRedditRssPageMetadata(raw)
        assertEquals(2, decoded.size)
        assertEquals(42, decoded.last().feedHash)
        assertEquals("t3_second", decoded.last().nextCursor)
    }

    @Test
    fun `corrupt or oversized cursor rows are discarded`() {
        val oversized = "a".repeat(65)
        val decoded = decodeRedditRssPageMetadata(
            "42\troot\tt3_valid\n" +
                "not-an-int\troot\tt3_next\n" +
                "7\t$oversized\tt3_next\n" +
                "8\troot\thttps://unsafe.example",
        )

        assertEquals(listOf(RedditRssPageMetadataEntry(42, "root", "t3_valid")), decoded)
    }

    @Test
    fun `legacy dynamic keys are removed without touching current preferences`() {
        val firstLegacy = stringPreferencesKey("reddit_rss_page_v2_42_root")
        val secondLegacy = stringPreferencesKey("reddit_rss_page_v2_42_abc")
        val current = stringPreferencesKey("reddit_rss_page_metadata_v3")
        val unrelated = stringPreferencesKey("wallpaper_source")
        val preferences = mutablePreferencesOf(
            firstLegacy to "t3_abc",
            secondLegacy to "__END__",
            current to "42\troot\tt3_abc",
            unrelated to "reddit",
        )

        assertEquals(2, removeLegacyRedditRssPageMetadata(preferences))
        assertFalse(preferences.contains(firstLegacy))
        assertFalse(preferences.contains(secondLegacy))
        assertTrue(preferences.contains(current))
        assertTrue(preferences.contains(unrelated))
    }
}
