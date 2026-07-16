package com.freevibe.service

import android.content.Context
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.SOURCE_AVAILABILITY_AVAILABLE
import com.freevibe.data.model.Sound
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

internal const val SOUND_FEED_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
internal const val SOUND_PREVIEW_URL_TTL_MS = 4L * 60L * 60L * 1000L
private const val SOUND_FEED_CACHE_PREFS = "freevibe_sound_feed_cache"
private const val MAX_CACHED_SOUNDS = 40
private const val LIST_SEPARATOR = "\u001f"

internal data class CachedSoundFeed(
    val sounds: List<Sound>,
    val cachedAtMs: Long,
)

internal fun soundFeedCacheKey(tabName: String, query: String): String =
    "sound_feed_${tabName.lowercase()}_${query.trim().lowercase().hashCode()}"

internal fun encodeSoundFeedCache(cached: CachedSoundFeed): String {
    val properties = Properties()
    properties.setProperty("cachedAtMs", cached.cachedAtMs.toString())
    val sounds = cached.sounds.take(MAX_CACHED_SOUNDS)
    properties.setProperty("count", sounds.size.toString())
    sounds.forEachIndexed { index, sound ->
        val prefix = "item.$index."
        properties.setProperty("${prefix}id", sound.id)
        properties.setProperty("${prefix}source", sound.source.name)
        properties.setProperty("${prefix}name", sound.name)
        properties.setProperty("${prefix}description", sound.description)
        properties.setProperty("${prefix}previewUrl", sound.previewUrl)
        properties.setProperty("${prefix}downloadUrl", sound.downloadUrl)
        properties.setProperty("${prefix}duration", sound.duration.toString())
        properties.setProperty("${prefix}sampleRate", sound.sampleRate.toString())
        properties.setProperty("${prefix}fileType", sound.fileType)
        properties.setProperty("${prefix}fileSize", sound.fileSize.toString())
        properties.setProperty("${prefix}tags", sound.tags.joinToString(LIST_SEPARATOR))
        properties.setProperty("${prefix}license", sound.license)
        properties.setProperty("${prefix}uploaderName", sound.uploaderName)
        properties.setProperty("${prefix}sourcePageUrl", sound.sourcePageUrl)
        properties.setProperty("${prefix}sourceAvailability", sound.sourceAvailability)
        properties.setProperty("${prefix}sourceAvailabilityReason", sound.sourceAvailabilityReason)
        properties.setProperty("${prefix}communityUploaderId", sound.communityUploaderId)
    }
    return ByteArrayOutputStream().use { output ->
        properties.store(output, null)
        output.toString(StandardCharsets.ISO_8859_1.name())
    }
}

internal fun decodeSoundFeedCache(raw: String?, nowMs: Long): CachedSoundFeed? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val properties = Properties().apply {
            load(ByteArrayInputStream(raw.toByteArray(StandardCharsets.ISO_8859_1)))
        }
        val cachedAtMs = properties.getProperty("cachedAtMs")?.toLongOrNull() ?: return null
        if (cachedAtMs <= 0L || nowMs - cachedAtMs > SOUND_FEED_CACHE_TTL_MS) return null
        val keepPreviewUrls = nowMs - cachedAtMs <= SOUND_PREVIEW_URL_TTL_MS
        val count = properties.getProperty("count")?.toIntOrNull()?.coerceIn(0, MAX_CACHED_SOUNDS) ?: 0
        val sounds = buildList {
            repeat(count) { index ->
                val prefix = "item.$index."
                val id = properties.getProperty("${prefix}id").orEmpty()
                val source = runCatching {
                    ContentSource.valueOf(properties.getProperty("${prefix}source").orEmpty())
                }.getOrNull()
                if (id.isBlank() || source == null) return@repeat
                val cachedPreview = properties.getProperty("${prefix}previewUrl").orEmpty()
                add(
                    Sound(
                        id = id,
                        source = source,
                        name = properties.getProperty("${prefix}name").orEmpty(),
                        description = properties.getProperty("${prefix}description").orEmpty(),
                        previewUrl = if (source == ContentSource.YOUTUBE && !keepPreviewUrls) "" else cachedPreview,
                        downloadUrl = properties.getProperty("${prefix}downloadUrl").orEmpty(),
                        duration = properties.getProperty("${prefix}duration")?.toDoubleOrNull() ?: 0.0,
                        sampleRate = properties.getProperty("${prefix}sampleRate")?.toIntOrNull() ?: 0,
                        fileType = properties.getProperty("${prefix}fileType").orEmpty(),
                        fileSize = properties.getProperty("${prefix}fileSize")?.toLongOrNull() ?: 0L,
                        tags = properties.getProperty("${prefix}tags").orEmpty()
                            .split(LIST_SEPARATOR)
                            .filter { it.isNotBlank() },
                        license = properties.getProperty("${prefix}license").orEmpty(),
                        uploaderName = properties.getProperty("${prefix}uploaderName").orEmpty(),
                        sourcePageUrl = properties.getProperty("${prefix}sourcePageUrl").orEmpty(),
                        sourceAvailability = properties.getProperty("${prefix}sourceAvailability")
                            ?.takeIf { it.isNotBlank() } ?: SOURCE_AVAILABILITY_AVAILABLE,
                        sourceAvailabilityReason = properties.getProperty("${prefix}sourceAvailabilityReason").orEmpty(),
                        communityUploaderId = properties.getProperty("${prefix}communityUploaderId").orEmpty(),
                    ),
                )
            }
        }
        CachedSoundFeed(sounds = sounds, cachedAtMs = cachedAtMs).takeIf { it.sounds.isNotEmpty() }
    }.getOrNull()
}

@Singleton
internal class SoundFeedCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(SOUND_FEED_CACHE_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun read(key: String, nowMs: Long = System.currentTimeMillis()): CachedSoundFeed? =
        decodeSoundFeedCache(prefs.getString(key, null), nowMs)

    @Synchronized
    fun write(key: String, sounds: List<Sound>, nowMs: Long = System.currentTimeMillis()) {
        if (sounds.isEmpty()) return
        prefs.edit()
            .putString(key, encodeSoundFeedCache(CachedSoundFeed(sounds, nowMs)))
            .apply()
    }
}
