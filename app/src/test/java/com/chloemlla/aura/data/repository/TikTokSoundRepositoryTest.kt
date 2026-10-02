package com.chloemlla.aura.data.repository

import com.chloemlla.aura.data.legal.isProviderAvailableInCurrentArtifact
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.service.SourceMetrics
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

class TikTokSoundRepositoryTest {

    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var embedFails = false

    @Before
    fun requireTikTokInThisBuild() {
        assumeTrue(isProviderAvailableInCurrentArtifact(ContentSource.TIKTOK))
    }

    @Test
    fun `ringtones come back most played first with probed durations`() = runTest {
        val repository = repository()

        val sounds = repository.ringtones()

        assertEquals(listOf("tt_2000000000000000002", "tt_1000000000000000001"), sounds.map { it.id })
        assertEquals(listOf("Nokia Banger", "Reflections Ultra Remix"), sounds.map { it.name })
        assertEquals(listOf(24.0, 17.0), sounds.map { it.duration })
        assertTrue(sounds.all { it.source == ContentSource.TIKTOK && it.uploaderName == "Ringtones for iPhone" })
        assertEquals(1, requests.count { it.contains("/embed/@ringtonesforiphone") })
        assertEquals(2, requests.count { it.contains("tiktokcdn-us.com") })
    }

    @Test
    fun `the feed is cached and a failed refresh keeps the last good list`() = runTest {
        val repository = repository()
        val first = repository.ringtones()

        repository.ringtones()
        assertEquals(1, requests.count { it.contains("/embed/@") })

        embedFails = true
        val afterFailure = repository.ringtones(forceRefresh = true)

        assertEquals(first, afterFailure)
        assertEquals(2, requests.count { it.contains("/embed/@") })
        assertEquals(2, requests.count { it.contains("tiktokcdn-us.com") })
    }

    @Test(expected = IOException::class)
    fun `a first load failure surfaces`() = runTest {
        embedFails = true
        repository().ringtones()
    }

    @Test
    fun `a stored link is reused until it nears expiry, then the video embed supplies a new one`() = runTest {
        val repository = repository()
        val sound = repository.ringtones().first()
        val nowSec = System.currentTimeMillis() / 1000

        val fresh = sound.copy(previewUrl = cdnUrl(nowSec + 3_600), downloadUrl = cdnUrl(nowSec + 3_600))
        assertEquals(fresh.previewUrl, repository.playableMediaUrl(fresh))
        assertEquals(0, requests.count { it.contains("/embed/v2/") })

        val expired = sound.copy(previewUrl = cdnUrl(nowSec - 5), downloadUrl = cdnUrl(nowSec - 5))
        assertEquals(REFRESHED_URL, repository.playableMediaUrl(expired))
        assertEquals(1, requests.count { it.contains("/embed/v2/2000000000000000002") })

        assertEquals(REFRESHED_URL, repository.playableMediaUrl(fresh, forceFresh = true))
    }

    private fun repository() = TikTokSoundRepository(
        okHttpClient = OkHttpClient.Builder().addInterceptor(CannedTikTok()).build(),
        sourceMetrics = SourceMetrics(),
    )

    private inner class CannedTikTok : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val (code, body, type) = when {
                url.endsWith("/embed/@ringtonesforiphone") && embedFails -> Triple(503, ByteArray(0), "text/html")
                url.endsWith("/embed/@ringtonesforiphone") -> Triple(200, creatorEmbed().toByteArray(), "text/html")
                url.contains("/embed/v2/2000000000000000002") -> Triple(200, videoEmbed().toByteArray(), "text/html")
                url.contains("/clip-1/") -> Triple(206, mp4Head(17_000), "video/mp4")
                url.contains("/clip-2/") -> Triple(206, mp4Head(24_000), "video/mp4")
                else -> Triple(404, ByteArray(0), "text/plain")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }
    }

    private fun creatorEmbed(): String {
        val later = System.currentTimeMillis() / 1000 + 86_400
        val videos = listOf(
            """{"id":"1000000000000000001","desc":"Best iPhone Ringtone | Reflections Ultra Remix #viral","playAddr":"${cdnUrl(later, "clip-1")}","playCount":480}""",
            """{"id":"2000000000000000002","desc":"Best Ringtone | Nokia Banger #ringy","playAddr":"${cdnUrl(later, "clip-2")}","playCount":5214}""",
        )
        return """<html><script id="__FRONTITY_CONNECT_STATE__" type="application/json">""" +
            """{"source":{"data":{"/embed/@ringtonesforiphone":{"userInfo":{"uniqueId":"ringtonesforiphone","nickname":"Ringtones for iPhone"},""" +
            """"videoList":[${videos.joinToString(",")}]}}}}</script></html>"""
    }

    private fun videoEmbed(): String =
        """<html><script id="__FRONTITY_CONNECT_STATE__" type="application/json">""" +
            """{"source":{"data":{"/embed/v2/2000000000000000002":{"videoData":{"itemInfos":{"id":"2000000000000000002",""" +
            """"video":{"urls":["$REFRESHED_URL"],"videoMeta":{"duration":24}}}}}}}}</script></html>"""

    private fun cdnUrl(expiresAtSec: Long, clip: String = "clip-2"): String =
        "https://v16m.tiktokcdn-us.com/2efdb7eb5474a4275cb2492f10c5c70a/${expiresAtSec.toString(16).padStart(8, '0')}/video/tos/$clip/"

    private fun mp4Head(durationMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(value: Long) = repeat(4) { i -> out.write(((value shr (24 - 8 * i)) and 0xFF).toInt()) }
        u32(24); out.write("ftypisom".toByteArray()); u32(512); out.write("isomiso2".toByteArray())
        u32(108); out.write("moov".toByteArray())
        u32(100); out.write("mvhd".toByteArray()); out.write(0); out.write(byteArrayOf(0, 0, 0))
        u32(0); u32(0); u32(1_000); u32(durationMs)
        return out.toByteArray()
    }

    private companion object {
        const val REFRESHED_URL = "https://v16m.tiktokcdn-us.com/ffffffffffffffffffffffffffffffff/7fffffff/video/tos/refreshed/"
    }
}
