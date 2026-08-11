package com.chloemlla.aura.data.repository

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditRssParserTest {

    @Test
    fun `parses direct image and attribution from encoded atom content`() {
        val entries = parseRedditRssMedia(
            xml = """
                <feed xmlns:media="http://search.yahoo.com/mrss/">
                  <entry>
                    <id>t3_abc123</id>
                    <title>Quiet coast [1440x3200]</title>
                    <author><name>/u/pixelmaker</name></author>
                    <category term="r/MobileWallpaper" />
                    <link href="https://www.reddit.com/r/MobileWallpaper/comments/abc123/quiet_coast/" />
                    <media:thumbnail url="https://preview.redd.it/abc123.jpg?width=640&amp;amp;crop=smart" />
                    <content type="html">&lt;a href=&quot;https://i.redd.it/abc123.jpg&quot;&gt;image&lt;/a&gt;</content>
                  </entry>
                </feed>
            """.trimIndent(),
            fallbackSubreddit = "wallpapers",
        )

        assertEquals(1, entries.size)
        with(entries.single()) {
            assertEquals("abc123", id)
            assertEquals("Quiet coast [1440x3200]", title)
            assertEquals("pixelmaker", author)
            assertEquals("MobileWallpaper", subreddit)
            assertEquals("https://i.redd.it/abc123.jpg", mediaUrl)
            assertEquals(1440, width)
            assertEquals(3200, height)
            assertFalse(isAnimated)
        }
    }

    @Test
    fun `keeps animated direct media for the video source`() {
        val entries = parseRedditRssMedia(
            xml = """
                <feed>
                  <entry>
                    <id>t3_loop42</id>
                    <title>Rainy window loop</title>
                    <author><name>/u/loops</name></author>
                    <link href="https://www.reddit.com/r/Cinemagraphs/comments/loop42/rain/" />
                    <content type="html">&lt;a href=&quot;https://i.redd.it/loop42.gif&quot;&gt;loop&lt;/a&gt;</content>
                  </entry>
                </feed>
            """.trimIndent(),
            fallbackSubreddit = "Cinemagraphs",
        )

        assertEquals("https://i.redd.it/loop42.gif", entries.single().mediaUrl)
        assertEquals("Cinemagraphs", entries.single().subreddit)
        assertTrue(entries.single().isAnimated)
    }

    @Test
    fun `titles remain paired with their entries when non media posts are interleaved`() {
        val entries = parseRedditRssMedia(
            xml = """
                <feed>
                  <entry>
                    <id>t3_first</id>
                    <title>First loop</title>
                    <content type="html">&lt;a href=&quot;https://i.redd.it/first.gif&quot;&gt;first&lt;/a&gt;</content>
                  </entry>
                  <entry>
                    <id>t3_text</id>
                    <title>Discussion without media</title>
                    <content type="html">&lt;a href=&quot;https://www.reddit.com/r/Cinemagraphs/comments/text/post/&quot;&gt;text&lt;/a&gt;</content>
                  </entry>
                  <entry>
                    <id>t3_second</id>
                    <title>Second loop</title>
                    <content type="html">&lt;a href=&quot;https://v.redd.it/second&quot;&gt;second&lt;/a&gt;</content>
                  </entry>
                </feed>
            """.trimIndent(),
            fallbackSubreddit = "Cinemagraphs",
        )

        assertEquals(
            listOf(
                Triple("first", "First loop", "https://i.redd.it/first.gif"),
                Triple("second", "Second loop", "https://v.redd.it/second"),
            ),
            entries.map { Triple(it.id, it.title, it.mediaUrl) },
        )
    }

    @Test
    fun `page cursor comes from final raw entry even when it has no direct media`() {
        val page = parseRedditRssPage(
            xml = """
                <feed>
                  <entry>
                    <id>t3_image1</id>
                    <title>Direct original</title>
                    <content type="html">&lt;img src=&quot;https://i.redd.it/image1.jpg&quot; /&gt;</content>
                  </entry>
                  <entry>
                    <id>t3_gallery2</id>
                    <title>Gallery tail</title>
                    <media:thumbnail url="https://preview.redd.it/gallery2.jpg?width=140" />
                    <content type="html">&lt;a href=&quot;https://www.reddit.com/gallery/gallery2&quot;&gt;gallery&lt;/a&gt;</content>
                  </entry>
                </feed>
            """.trimIndent(),
            fallbackSubreddit = "MobileWallpaper",
        )

        assertEquals(2, page.rawEntryCount)
        assertEquals("t3_gallery2", page.nextAfter)
        assertEquals(listOf("image1"), page.entries.map { it.id })
    }

    @Test
    fun `preview-only gallery thumbnail is not treated as applyable wallpaper`() {
        val entries = parseRedditRssMedia(
            xml = """
                <feed xmlns:media="http://search.yahoo.com/mrss/">
                  <entry>
                    <id>t3_tiny</id>
                    <title>Gallery</title>
                    <media:thumbnail url="https://preview.redd.it/tiny.jpg?width=140&amp;height=140" />
                    <content type="html">&lt;a href=&quot;https://www.reddit.com/gallery/tiny&quot;&gt;gallery&lt;/a&gt;</content>
                  </entry>
                </feed>
            """.trimIndent(),
            fallbackSubreddit = "phonewallpapers",
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `rss url carries public atom cursor and large page limit`() {
        val url = redditRssUrl(
            subreddits = "iWallpaper+MobileWallpaper",
            sort = "new",
            timeRange = "all",
            after = "t3_abc123",
            count = 100,
        )

        assertEquals(
            "https://www.reddit.com/r/iWallpaper+MobileWallpaper/new/.rss?limit=100&count=100&after=t3_abc123",
            url,
        )
    }

    @Test
    fun `reddit retry delay honors rate limit headers and conservative floor`() {
        assertEquals(
            90_000L,
            redditRetryAfterMs(Headers.headersOf("Retry-After", "90")),
        )
        assertEquals(
            60_000L,
            redditRetryAfterMs(Headers.headersOf("X-Ratelimit-Reset", "12.5")),
        )
        assertEquals(60_000L, redditRetryAfterMs(Headers.headersOf()))
    }
}
