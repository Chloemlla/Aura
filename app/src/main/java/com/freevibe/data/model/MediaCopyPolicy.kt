package com.freevibe.data.model

import java.security.MessageDigest
import java.util.Locale

const val MEDIA_TYPE_WALLPAPER = "WALLPAPER"
const val MEDIA_TYPE_VIDEO = "VIDEO"
const val MEDIA_TYPE_SOUND = "SOUND"

/** Technical facts shown beside an original or optimized working copy. */
data class MediaTechnicalMetadata(
    val mimeType: String = "",
    val codec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val isHdr: Boolean = false,
)

data class MediaOptimizationDecision(
    val required: Boolean,
    val reason: String = "",
)

fun DownloadEntity.originalTechnicalMetadata(): MediaTechnicalMetadata = MediaTechnicalMetadata(
    mimeType = originalMimeType,
    codec = originalCodec,
    width = originalWidth,
    height = originalHeight,
    durationMs = originalDurationMs,
    sizeBytes = originalSizeBytes,
    isHdr = originalHdr,
)

fun DownloadEntity.optimizedTechnicalMetadata(): MediaTechnicalMetadata = MediaTechnicalMetadata(
    mimeType = optimizedMimeType,
    codec = optimizedCodec,
    width = optimizedWidth,
    height = optimizedHeight,
    durationMs = optimizedDurationMs,
    sizeBytes = optimizedSizeBytes,
    isHdr = optimizedHdr,
)

/** Static wallpaper transforms flatten Ultra HDR until the gainmap work item lands. */
fun decideWallpaperOptimization(
    metadata: MediaTechnicalMetadata,
    targetWidth: Int,
    targetHeight: Int,
    hasPixelTransform: Boolean,
): MediaOptimizationDecision {
    if (hasPixelTransform) {
        return MediaOptimizationDecision(true, "Wallpaper transform")
    }
    val width = metadata.width.coerceAtLeast(0)
    val height = metadata.height.coerceAtLeast(0)
    if (width == 0 || height == 0 || targetWidth <= 0 || targetHeight <= 0) {
        return MediaOptimizationDecision(false)
    }
    val targetLongEdge = maxOf(targetWidth, targetHeight).toLong()
    val sourceLongEdge = maxOf(width, height).toLong()
    val sourcePixels = width.toLong() * height.toLong()
    return if (
        sourcePixels > MAX_STATIC_APPLY_PIXELS ||
        sourceLongEdge > targetLongEdge * MAX_SOURCE_TO_TARGET_LONG_EDGE_MULTIPLIER
    ) {
        MediaOptimizationDecision(true, "Oversized wallpaper")
    } else {
        MediaOptimizationDecision(false)
    }
}

fun decideVideoOptimization(
    metadata: MediaTechnicalMetadata,
    fileExtension: String,
    targetWidth: Int,
    targetHeight: Int,
    fitRequested: Boolean,
): MediaOptimizationDecision {
    val isGif = fileExtension.equals("gif", ignoreCase = true)
    val width = metadata.width.coerceAtLeast(0)
    val height = metadata.height.coerceAtLeast(0)
    val sourcePixels = width.toLong() * height.toLong()
    val targetLongEdge = maxOf(targetWidth, targetHeight).coerceAtLeast(1).toLong()
    val oversized = sourcePixels > MAX_VIDEO_APPLY_PIXELS ||
        maxOf(width, height).toLong() > targetLongEdge * MAX_SOURCE_TO_TARGET_LONG_EDGE_MULTIPLIER
    if (isGif) {
        return if (oversized) {
            MediaOptimizationDecision(true, "Device-sized H.264 MP4")
        } else {
            MediaOptimizationDecision(false)
        }
    }
    if (fitRequested) {
        return MediaOptimizationDecision(true, "Fit Canvas video")
    }
    val normalizedMime = metadata.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val normalizedCodec = metadata.codec.substringBefore(';').trim().lowercase(Locale.ROOT)
    val compatibleContainer = fileExtension.equals("mp4", ignoreCase = true) &&
        (normalizedMime.isBlank() || normalizedMime == "video/mp4")
    val compatibleCodec = normalizedCodec in VIDEO_APPLY_CODECS
    if (!compatibleContainer || !compatibleCodec) {
        return MediaOptimizationDecision(true, "Compatible H.264 MP4")
    }
    if (oversized) {
        return MediaOptimizationDecision(true, "Device-sized H.264 MP4")
    }
    return MediaOptimizationDecision(false)
}

fun decideSoundOptimization(
    metadata: MediaTechnicalMetadata,
    edited: Boolean,
): MediaOptimizationDecision {
    if (edited) return MediaOptimizationDecision(true, "Edited sound")
    val mime = metadata.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    return if (mime in SOUND_APPLY_MIME_TYPES) {
        MediaOptimizationDecision(false)
    } else {
        MediaOptimizationDecision(true, "Compatible M4A sound")
    }
}

fun mediaOptimizationKey(vararg parts: Any?): String {
    val normalized = parts.joinToString("\u001f") { it?.toString()?.trim().orEmpty() }
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

private const val MAX_STATIC_APPLY_PIXELS = 8_000_000L
private const val MAX_VIDEO_APPLY_PIXELS = 8_000_000L
private const val MAX_SOURCE_TO_TARGET_LONG_EDGE_MULTIPLIER = 2L

private val VIDEO_APPLY_CODECS = setOf(
    "avc",
    "h264",
    "video/avc",
)

fun wallpaperOptimizationReason(baseReason: String, originalIsHdr: Boolean): String {
    val reason = baseReason.ifBlank { "Wallpaper transform" }
    return if (originalIsHdr) "$reason (SDR working copy)" else reason
}

private val SOUND_APPLY_MIME_TYPES = setOf(
    "audio/aac",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
)
