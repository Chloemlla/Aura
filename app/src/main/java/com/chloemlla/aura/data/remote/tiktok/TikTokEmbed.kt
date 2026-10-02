package com.chloemlla.aura.data.remote.tiktok

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Sound
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * TikTok's public embed pages (the ones its own "Embed" share button produces)
 * carry their data as JSON in a `__FRONTITY_CONNECT_STATE__` script. The creator
 * embed lists a profile's latest videos with direct CDN MP4 links; the per-video
 * embed hands out a fresh link once a stored one has expired.
 */
@Serializable
internal data class TikTokEmbedVideo(
    val id: String = "",
    val desc: String = "",
    val playAddr: String = "",
    val playCount: Long = 0,
    val privateItem: Boolean = false,
)

@Serializable
internal data class TikTokEmbedUser(
    val uniqueId: String = "",
    val nickname: String = "",
)

internal data class TikTokCreatorFeed(
    val user: TikTokEmbedUser,
    val videos: List<TikTokEmbedVideo>,
)

internal data class TikTokVideoMedia(
    val url: String,
    val durationSeconds: Double,
)

private val tiktokJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val EMBED_STATE_REGEX = Regex(
    """<script[^>]*id="__FRONTITY_CONNECT_STATE__"[^>]*>(.*?)</script>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val TIKTOK_VIDEO_ID = Regex("""\d{8,24}""")
private val CDN_EXPIRY_SEGMENT = Regex("""://[^/]+/[0-9a-f]{32}/([0-9a-f]{8})/""")
private val EXPIRES_QUERY = Regex("""[?&]x-expires=(\d{9,11})""")
private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")
private val MARKETING_PREFIX = Regex("""^(?:best\s+)?(?:iphone\s+)?ringtones?\s*[|:]\s*""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("""\s+""")
private val NOISE_TAGS = setOf("fyp", "fy", "foryou", "foryoupage", "viral", "trending", "xyzbca")

internal fun tiktokEmbedState(html: String): JsonObject? {
    val raw = EMBED_STATE_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        tiktokJson.parseToJsonElement(raw).jsonObject
    } catch (_: IllegalArgumentException) {
        // SerializationException and a non-object root both land here.
        null
    }
}

private fun JsonObject.embedRoute(route: String): JsonObject? {
    val data = (this["source"] as? JsonObject)?.get("data") as? JsonObject ?: return null
    val key = data.keys.firstOrNull { it.equals(route, ignoreCase = true) } ?: return null
    return data[key] as? JsonObject
}

private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun parseTikTokCreatorEmbed(html: String, handle: String): TikTokCreatorFeed? {
    val page = tiktokEmbedState(html)?.embedRoute("/embed/@$handle") ?: return null
    val videoList = page["videoList"] as? JsonArray ?: return null
    val user = page.child("userInfo")?.let { decodeOrNull<TikTokEmbedUser>(it) } ?: TikTokEmbedUser()
    val videos = videoList
        .mapNotNull { element -> (element as? JsonObject)?.let { decodeOrNull<TikTokEmbedVideo>(it) } }
        .filter { video -> TIKTOK_VIDEO_ID.matches(video.id) && video.playAddr.startsWith("https://") && !video.privateItem }
        .distinctBy { it.id }
    return TikTokCreatorFeed(
        user = user.copy(uniqueId = user.uniqueId.ifBlank { handle }),
        videos = videos,
    )
}

internal fun parseTikTokVideoEmbed(html: String, videoId: String): TikTokVideoMedia? {
    val video = tiktokEmbedState(html)
        ?.embedRoute("/embed/v2/$videoId")
        ?.child("videoData")
        ?.child("itemInfos")
        ?.takeIf { it.string("id") == videoId }
        ?.child("video")
        ?: return null
    val url = (video["urls"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { url -> url.startsWith("https://") } }
        ?: return null
    val duration = (video.child("videoMeta")?.get("duration") as? JsonPrimitive)?.doubleOrNull ?: 0.0
    return TikTokVideoMedia(url = url, durationSeconds = duration.coerceAtLeast(0.0))
}

private inline fun <reified T> decodeOrNull(element: JsonObject): T? = try {
    tiktokJson.decodeFromJsonElement<T>(element)
} catch (_: IllegalArgumentException) {
    null
}

/** Epoch seconds a signed TikTok CDN link stops working, or null when the link carries no expiry. */
internal fun tiktokMediaUrlExpiresAtSec(url: String): Long? {
    CDN_EXPIRY_SEGMENT.find(url)?.groupValues?.get(1)?.toLongOrNull(16)?.let { return it }
    return EXPIRES_QUERY.find(url)?.groupValues?.get(1)?.toLongOrNull()
}

/** True when [url] is a TikTok media link that will still work for at least [marginSec]. */
internal fun isTikTokMediaUrlUsable(url: String, nowSec: Long, marginSec: Long = 600): Boolean {
    if (!url.startsWith("https://")) return false
    val expiresAt = tiktokMediaUrlExpiresAtSec(url) ?: return true
    return expiresAt - marginSec > nowSec
}

/**
 * Captions read like "Best Ringtone | Nokia Banger 📲 #ringy #ringtone". The
 * name is the text before the first emoji or hashtag, without the channel's
 * marketing prefix.
 */
internal fun cleanTikTokTitle(caption: String): String {
    val head = caption.substringBefore('#').let { it.substring(0, firstSymbolIndex(it)) }
    val trimmed = head.replace(WHITESPACE, " ").trim().trimEnd('|', '-', ':').trim()
    val withoutPrefix = MARKETING_PREFIX.replace(trimmed, "").trim()
    return withoutPrefix.ifBlank { trimmed }.take(80).ifBlank { "TikTok ringtone" }
}

/** Caption prose after the title, without hashtags or emoji. */
internal fun tiktokCaptionDetail(caption: String): String {
    val withoutTags = HASHTAG.replace(caption, " ")
    val afterTitle = withoutTags.substring(firstSymbolIndex(withoutTags))
    return afterTitle.filterNot { Character.isSurrogate(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        .replace(WHITESPACE, " ")
        .trim()
        .take(240)
}

internal fun tiktokCaptionTags(caption: String): List<String> =
    HASHTAG.findAll(caption)
        .map { it.value.removePrefix("#").lowercase(Locale.ROOT) }
        .filterNot { it in NOISE_TAGS }
        .distinct()
        .take(8)
        .toList()

private fun firstSymbolIndex(text: String): Int {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint > 0xFFFF || Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()) return index
        index += Character.charCount(codePoint)
    }
    return text.length
}

/**
 * Reads the movie duration from the `mvhd` box at the head of a progressive MP4.
 * TikTok writes `moov` first, so the first kilobyte is enough.
 */
internal fun parseMp4DurationSeconds(bytes: ByteArray): Double? {
    val tag = "mvhd".toByteArray(Charsets.US_ASCII)
    val at = (0..bytes.size - tag.size).firstOrNull { i -> tag.indices.all { bytes[i + it] == tag[it] } } ?: return null
    val version = bytes.getOrNull(at + 4)?.toInt() ?: return null
    val (timescale, duration) = when (version) {
        0 -> readUInt(bytes, at + 16) to readUInt(bytes, at + 20)
        1 -> readUInt(bytes, at + 24) to readULong(bytes, at + 28)
        else -> return null
    }
    if (timescale == null || duration == null || timescale <= 0L || duration <= 0L) return null
    if (version == 0 && duration == 0xFFFF_FFFFL) return null
    return duration.toDouble() / timescale
}

private fun readUInt(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xFF) }
}

private fun readULong(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 8 > bytes.size) return null
    return (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xFF) }
}

const val TIKTOK_SOUND_ID_PREFIX = "tt_"

fun Sound.tiktokVideoId(): String? =
    takeIf { source == ContentSource.TIKTOK }
        ?.id
        ?.removePrefix(TIKTOK_SOUND_ID_PREFIX)
        ?.takeIf { TIKTOK_VIDEO_ID.matches(it) }

internal fun TikTokEmbedVideo.toSound(user: TikTokEmbedUser, durationSeconds: Double): Sound {
    val handle = user.uniqueId
    val creator = user.nickname.ifBlank { "@$handle" }
    return Sound(
        id = "$TIKTOK_SOUND_ID_PREFIX$id",
        source = ContentSource.TIKTOK,
        name = cleanTikTokTitle(desc),
        description = tiktokCaptionDetail(desc).ifBlank { "by $creator" },
        previewUrl = playAddr,
        downloadUrl = playAddr,
        duration = durationSeconds,
        fileType = "audio/m4a",
        tags = tiktokCaptionTags(desc),
        license = "TikTok",
        uploaderName = creator,
        sourcePageUrl = "https://www.tiktok.com/@$handle/video/$id",
    )
}
