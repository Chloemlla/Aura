package com.freevibe.data.remote.tiktok

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TikTokEmbedTest {

    @Test
    fun `creator embed yields public videos and the creator name`() {
        val html = creatorEmbedHtml(
            handle = "ringtonesforiphone",
            videos = listOf(
                videoJson("7689085301091781901", "Best Ringtone | Nokia Banger \\uD83D\\uDCF2 #ringy #viral", 521_400),
                videoJson("7652088196167191822", "Private clip", 10, privateItem = true),
                """{"id":"7679776973631114510","desc":"No link","playAddr":"http://insecure.example/clip","playCount":5}""",
                """{"id":"not-an-id","desc":"Bad id","playAddr":"$PLAY_ADDR","playCount":5}""",
            ),
        )

        val feed = parseTikTokCreatorEmbed(html, "ringtonesforiphone")!!

        assertEquals("Ringtones for iPhone", feed.user.nickname)
        assertEquals(listOf("7689085301091781901"), feed.videos.map { it.id })
        assertEquals("Best Ringtone | Nokia Banger 📲 #ringy #viral", feed.videos.single().desc)
        assertEquals(521_400L, feed.videos.single().playCount)
    }

    @Test
    fun `creator embed without its state script or route yields nothing`() {
        assertNull(parseTikTokCreatorEmbed("<html><body>Rate limited</body></html>", "ringtonesforiphone"))
        assertNull(parseTikTokCreatorEmbed(creatorEmbedHtml("someoneelse", emptyList()), "ringtonesforiphone"))
        assertNull(parseTikTokCreatorEmbed(stateHtml("{not json"), "ringtonesforiphone"))
    }

    @Test
    fun `video embed hands out a fresh link and duration for that video only`() {
        val html = stateHtml(
            """{"source":{"data":{"/embed/v2/7689085301091781901":{"videoData":{"itemInfos":{"id":"7689085301091781901",""" +
                """"video":{"urls":["$PLAY_ADDR"],"videoMeta":{"width":720,"height":1280,"duration":17}}}}}}}}""",
        )

        val media = parseTikTokVideoEmbed(html, "7689085301091781901")!!

        assertEquals(PLAY_ADDR, media.url)
        assertEquals(17.0, media.durationSeconds, 0.0)
        assertNull(parseTikTokVideoEmbed(html, "7652088196167191822"))
    }

    @Test
    fun `signed cdn links expose their expiry`() {
        assertEquals(0x6AC03799L, tiktokMediaUrlExpiresAtSec(PLAY_ADDR))
        assertEquals(
            1_790_982_000L,
            tiktokMediaUrlExpiresAtSec("https://p16-common-sign.tiktokcdn-us.com/tos/abc~tplv.image?dr=9636&x-expires=1790982000&x-signature=x"),
        )
        assertNull(tiktokMediaUrlExpiresAtSec("https://example.com/clip.mp4"))
    }

    @Test
    fun `a link is usable only while it has ten minutes left`() {
        val expiresAt = 0x6AC03799L
        assertTrue(isTikTokMediaUrlUsable(PLAY_ADDR, nowSec = expiresAt - 601))
        assertFalse(isTikTokMediaUrlUsable(PLAY_ADDR, nowSec = expiresAt - 599))
        assertFalse(isTikTokMediaUrlUsable("", nowSec = 0))
        assertFalse(isTikTokMediaUrlUsable("http://v16m.tiktokcdn-us.com/clip", nowSec = 0))
        assertTrue(isTikTokMediaUrlUsable("https://v16m.tiktokcdn-us.com/unsigned/clip", nowSec = expiresAt))
    }

    @Test
    fun `captions from the ringtone creator become clean names`() {
        val phone = "📲"
        val expected = mapOf(
            "Best iPhone Ringtone | Reflections Ultra Remix $phone #ringtone #viral #fyp #ringy" to "Reflections Ultra Remix",
            "iPhone Ringtone 2026 #ringtones #ringtone #sonnerie #sonnerieiphone #tonodellamada" to "iPhone Ringtone 2026",
            "Afrobeat Chill Ringtone $phone  We took the classic iPhone ringtone and flipped it. #ringtone #iphone " to "Afrobeat Chill Ringtone",
            "Best Ringtone | Joslide Reflections $phone #ringy #joslide #shoulderbop #ringtone #ringtones " to "Joslide Reflections",
            "Best Ringtone | Nokia Banger $phone  #ringy #ringtone #viral #nostalgia #jerseyclubremixs " to "Nokia Banger",
            "Silk ringtone (joslide remix) #joslide #jerseyclubremixs #iphone #ringtone #fyp " to "Silk ringtone (joslide remix)",
            "Sprinkles Remix | new iphone ringtone $phone #iphoneringtone #ringtones #newringtone " to "Sprinkles Remix | new iphone ringtone",
            "80s Ringtone Remix | Chopped Up Reflections $phone  #80smusic #fyp " to "80s Ringtone Remix | Chopped Up Reflections",
            "#ringtone #fyp" to "TikTok ringtone",
        )

        expected.forEach { (caption, title) -> assertEquals(caption, title, cleanTikTokTitle(caption)) }
    }

    @Test
    fun `caption prose and hashtags fill description and tags`() {
        val caption = "Afrobeat Chill Ringtone 📲  We took the classic iPhone ringtone and flipped it. " +
            "#ringtone #iphone #fyp #Afrobeats #viral #ringtone"

        assertEquals("We took the classic iPhone ringtone and flipped it.", tiktokCaptionDetail(caption))
        assertEquals(listOf("ringtone", "iphone", "afrobeats"), tiktokCaptionTags(caption))
        assertEquals("", tiktokCaptionDetail("Silk ringtone (joslide remix) #joslide"))
    }

    @Test
    fun `mp4 head gives the clip duration`() {
        assertEquals(17.0, parseMp4DurationSeconds(mp4Head(version = 0, timescale = 1_000, duration = 17_000))!!, 0.0001)
        assertEquals(24.5, parseMp4DurationSeconds(mp4Head(version = 1, timescale = 44_100, duration = 1_080_450))!!, 0.0001)
        assertNull(parseMp4DurationSeconds(mp4Head(version = 0, timescale = 0, duration = 17_000)))
        assertNull(parseMp4DurationSeconds(ByteArray(64)))
        // Cut inside the duration field (it spans bytes 56..59 of this head).
        assertNull(parseMp4DurationSeconds(mp4Head(version = 0, timescale = 1_000, duration = 17_000).copyOf(58)))
    }

    @Test
    fun `a creator clip maps to an attributed ringtone`() {
        val video = TikTokEmbedVideo(
            id = "7688705749270252814",
            desc = "Best Ringtone | Nokia Banger 📲  #ringy #ringtone #viral",
            playAddr = PLAY_ADDR,
            playCount = 521_400,
        )

        val sound = video.toSound(TikTokEmbedUser(uniqueId = "ringtonesforiphone", nickname = "Ringtones for iPhone"), 20.0)

        assertEquals("tt_7688705749270252814", sound.id)
        assertEquals(ContentSource.TIKTOK, sound.source)
        assertEquals("Nokia Banger", sound.name)
        assertEquals("by Ringtones for iPhone", sound.description)
        assertEquals(PLAY_ADDR, sound.previewUrl)
        assertEquals(PLAY_ADDR, sound.downloadUrl)
        assertEquals("audio/m4a", sound.fileType)
        assertEquals("TikTok", sound.license)
        assertEquals("Ringtones for iPhone", sound.uploaderName)
        assertEquals("https://www.tiktok.com/@ringtonesforiphone/video/7688705749270252814", sound.sourcePageUrl)
        assertEquals(listOf("ringy", "ringtone"), sound.tags)
        assertEquals(20.0, sound.duration, 0.0)
        assertEquals("7688705749270252814", sound.tiktokVideoId())
    }

    @Test
    fun `only tiktok sounds carry a tiktok video id`() {
        val youtube = Sound(id = "tt_7688705749270252814", source = ContentSource.YOUTUBE, name = "x", previewUrl = "", downloadUrl = "")
        val malformed = youtube.copy(source = ContentSource.TIKTOK, id = "tt_abc")

        assertNull(youtube.tiktokVideoId())
        assertNull(malformed.tiktokVideoId())
    }

    private fun creatorEmbedHtml(handle: String, videos: List<String>): String = stateHtml(
        """{"source":{"data":{"/embed/@$handle":{"userInfo":{"uniqueId":"$handle","nickname":"Ringtones for iPhone"},""" +
            """"videoList":[${videos.joinToString(",")}]}}}}""",
    )

    private fun stateHtml(state: String): String =
        """<html><head><script id="__FRONTITY_CONNECT_STATE__" type="application/json">$state</script></head></html>"""

    private fun videoJson(id: String, desc: String, playCount: Long, privateItem: Boolean = false): String =
        """{"id":"$id","desc":"$desc","playAddr":"$PLAY_ADDR","playCount":$playCount,"privateItem":$privateItem,"authorUniqueId":"ringtonesforiphone"}"""

    private fun mp4Head(version: Int, timescale: Long, duration: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(value: Long) = repeat(4) { i -> out.write(((value shr (24 - 8 * i)) and 0xFF).toInt()) }
        fun u64(value: Long) {
            u32(value ushr 32)
            u32(value and 0xFFFF_FFFFL)
        }
        u32(24)
        out.write("ftypisom".toByteArray())
        u32(512)
        out.write("isomiso2".toByteArray())
        u32(0)
        out.write("moov".toByteArray())
        u32(0)
        out.write("mvhd".toByteArray())
        out.write(version)
        out.write(byteArrayOf(0, 0, 0))
        if (version == 0) {
            u32(0); u32(0); u32(timescale); u32(duration)
        } else {
            u64(0); u64(0); u32(timescale); u64(duration)
        }
        u32(0x0001_0000)
        return out.toByteArray()
    }

    private companion object {
        const val PLAY_ADDR =
            "https://v16m.tiktokcdn-us.com/2efdb7eb5474a4275cb2492f10c5c70a/6ac03799/video/tos/useast5/tos-useast5-ve-0068c003-tx/clip/"
    }
}
