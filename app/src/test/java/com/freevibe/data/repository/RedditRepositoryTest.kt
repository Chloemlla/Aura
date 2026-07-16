package com.freevibe.data.repository

import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.local.WallpaperCacheManager
import com.freevibe.data.model.Wallpaper
import com.freevibe.service.SourceMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditRepositoryTest {

    @Test
    fun `older cached request cannot rewind active cursor`() {
        assertEquals("t3_page2", advanceRedditCursor(null, null, "t3_page2"))
        assertEquals("t3_page3", advanceRedditCursor("t3_page2", "t3_page2", "t3_page3"))
        assertEquals("t3_page3", advanceRedditCursor("t3_page3", null, "t3_page2"))
        assertEquals("t3_page3", advanceRedditCursor("t3_page3", "t3_page2", "t3_page3"))
    }

    @Test
    fun `cooldown skips do not consume or renumber the raw cursor`() = runTest {
        val harness = Harness(atomPage(directImages = 99, rawTailId = "gallerytail"))
        val repository = harness.repository()

        val first = repository.getMultiSubreddit(page = 1)
        val skippedSecond = repository.getMultiSubreddit(page = 2)
        val skippedMuchLater = repository.getMultiSubreddit(page = 9)

        assertEquals(99, first.items.size)
        assertTrue(first.hasMore)
        assertTrue(skippedSecond.items.isEmpty())
        assertTrue(skippedSecond.hasMore)
        assertTrue(skippedMuchLater.items.isEmpty())
        assertTrue(skippedMuchLater.hasMore)
        assertEquals("t3_gallerytail", harness.pageMetadata.values.single())
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun `terminal metadata survives repository recreation without fabricated cursor`() = runTest {
        val harness = Harness(atomPage(directImages = 1))

        val first = harness.repository().getMultiSubreddit(page = 1)
        val relaunched = harness.repository().getMultiSubreddit(page = 1)

        assertEquals(1, first.items.size)
        assertFalse(first.hasMore)
        assertEquals(first.items.map { it.id }, relaunched.items.map { it.id })
        assertFalse(relaunched.hasMore)
        assertEquals("__END__", harness.pageMetadata.values.single())
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun `persisted cooldown prevents process relaunch request burst`() = runTest {
        val harness = Harness(atomPage(directImages = 100))
        harness.repository().getMultiSubreddit(page = 1)
        harness.cacheRows.clear()

        val relaunched = harness.repository().getMultiSubreddit(page = 1)

        assertTrue(relaunched.items.isEmpty())
        assertTrue(relaunched.hasMore)
        assertEquals(1, harness.requests.size)
        assertTrue(harness.nextAllowedAtMs > System.currentTimeMillis())
    }

    @Test
    fun `wallpaper feed honors configured subreddit list`() = runTest {
        val harness = Harness(
            xml = atomPage(directImages = 1),
            configuredSubreddits = "CustomWalls,OLED_Portraits",
        )

        harness.repository().getMultiSubreddit(page = 1)

        assertTrue(harness.requests.single().contains("/r/CustomWalls+OLED_Portraits/new/.rss"))
    }

    private class Harness(
        private val xml: String,
        configuredSubreddits: String = "iWallpaper",
    ) {
        val requests = mutableListOf<String>()
        val cacheRows = mutableMapOf<String, List<Wallpaper>>()
        val pageMetadata = mutableMapOf<Pair<Int, String?>, String>()
        var nextAllowedAtMs: Long = 0L

        private val preferences = mockk<PreferencesManager>().also { prefs ->
            every { prefs.redditProviderEnabled } returns flowOf(true)
            every { prefs.redditSubreddits } returns flowOf(configuredSubreddits)
            coEvery { prefs.getRedditRssNextCursor(any(), any()) } answers {
                pageMetadata[firstArg<Int>() to secondArg<String?>()].orEmpty()
            }
            coEvery { prefs.setRedditRssNextCursor(any(), any(), any()) } answers {
                pageMetadata[firstArg<Int>() to secondArg<String?>()] = thirdArg()
            }
            coEvery { prefs.getRedditRssNextAllowedAtMs() } answers { nextAllowedAtMs }
            coEvery { prefs.setRedditRssNextAllowedAtMs(any()) } answers {
                nextAllowedAtMs = firstArg()
            }
        }
        private val cacheManager = mockk<WallpaperCacheManager>().also { cache ->
            coEvery { cache.getStaleCached(any()) } answers { cacheRows[firstArg()] }
            coEvery { cache.getCached(any(), any()) } answers { cacheRows[firstArg()] }
            coEvery { cache.cache(any(), any()) } answers {
                cacheRows[firstArg()] = secondArg()
            }
        }
        private val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(xml.toResponseBody("application/atom+xml".toMediaType()))
                    .build()
            }
            .build()

        fun repository() = RedditRepository(
            okHttpClient = client,
            cacheManager = cacheManager,
            sourceMetrics = SourceMetrics(),
            prefs = preferences,
        )
    }

    private companion object {
        fun atomPage(directImages: Int, rawTailId: String? = null): String = buildString {
            append("<feed>")
            repeat(directImages) { index ->
                val id = "image${index + 1}"
                append("<entry><id>t3_$id</id><title>Wallpaper $id [1440x3200]</title>")
                append("<content type=\"html\">&lt;img src=&quot;https://i.redd.it/$id.jpg&quot; /&gt;</content></entry>")
            }
            rawTailId?.let { id ->
                append("<entry><id>t3_$id</id><title>Gallery tail</title>")
                append("<content type=\"html\">&lt;a href=&quot;https://www.reddit.com/gallery/$id&quot;&gt;gallery&lt;/a&gt;</content></entry>")
            }
            append("</feed>")
        }
    }
}
