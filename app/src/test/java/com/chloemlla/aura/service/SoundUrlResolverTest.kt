package com.chloemlla.aura.service

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.repository.YouTubeRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class SoundUrlResolverTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resolve returns fresh YouTube stream url`() = runTest(dispatcher) {
        val youtubeRepo = mockk<YouTubeRepository>()
        coEvery { youtubeRepo.getAudioStreamUrl("focus12345") } returns "https://example.com/fresh-focus.mp3"

        val resolver = SoundUrlResolver(
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            youtubeRepo = youtubeRepo,
            tiktokAudioExtractor = mockk(relaxed = true),
        )

        val resolved = resolver.resolve(
            Sound(
                id = "yt_focus12345",
                source = ContentSource.YOUTUBE,
                name = "Focus Loop",
                previewUrl = "",
                downloadUrl = "",
                sourcePageUrl = "https://www.youtube.com/watch?v=focus12345",
            )
        )

        assertEquals("https://example.com/fresh-focus.mp3", resolved)
    }

    @Test
    fun `resolve returns packaged sound locator without an HTTP probe`() = runTest(dispatcher) {
        val resolver = SoundUrlResolver(
            okHttpClient = mockk(relaxed = true),
            youtubeRepo = mockk(relaxed = true),
            tiktokAudioExtractor = mockk(relaxed = true),
        )
        val locator = "rawresource:///12345"

        val resolved = resolver.resolve(
            Sound(
                id = "bundled_ding",
                source = ContentSource.BUNDLED,
                name = "Gentle Ding",
                previewUrl = locator,
                downloadUrl = locator,
                license = "CC0 1.0",
            ),
        )

        assertEquals(locator, resolved)
    }

    @Test
    fun `tiktok sounds resolve to the extracted sound track, never the video link`() = runTest(dispatcher) {
        val extractor = mockk<TikTokAudioExtractor>()
        val extracted = "file:///data/user/0/com.chloemlla.aura/cache/tiktok-audio/tiktok_7688705749270252814.m4a"
        coEvery { extractor.extract(any()) } returns extracted
        val resolver = SoundUrlResolver(
            okHttpClient = mockk(relaxed = true),
            youtubeRepo = mockk(relaxed = true),
            tiktokAudioExtractor = extractor,
        )
        val video = "https://v16m.tiktokcdn-us.com/2efdb7eb5474a4275cb2492f10c5c70a/6ac03799/video/tos/clip/"
        val sound = Sound(
            id = "tt_7688705749270252814",
            source = ContentSource.TIKTOK,
            name = "Nokia Banger",
            previewUrl = video,
            downloadUrl = video,
            license = "TikTok",
        )

        assertEquals(extracted, resolver.resolve(sound))

        coEvery { extractor.extract(any()) } returns null
        assertEquals(null, resolver.resolve(sound))
    }

    @Test
    fun `a failed tiktok extraction resolves to null instead of escaping to the caller`() = runTest(dispatcher) {
        val extractor = mockk<TikTokAudioExtractor>()
        val resolver = SoundUrlResolver(
            okHttpClient = mockk(relaxed = true),
            youtubeRepo = mockk(relaxed = true),
            tiktokAudioExtractor = extractor,
        )
        val sound = Sound(
            id = "tt_7688705749270252814",
            source = ContentSource.TIKTOK,
            name = "Nokia Banger",
            previewUrl = "https://v16m.tiktokcdn-us.com/a/6ac03799/video/tos/clip/",
            downloadUrl = "https://v16m.tiktokcdn-us.com/a/6ac03799/video/tos/clip/",
            license = "TikTok",
        )

        coEvery { extractor.extract(any()) } throws IOException("TikTok video download failed (HTTP 403)")
        assertEquals(null, resolver.resolve(sound))

        coEvery { extractor.extract(any()) } throws CancellationException("left the screen")
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.resolve(sound) }
        }
    }
}
