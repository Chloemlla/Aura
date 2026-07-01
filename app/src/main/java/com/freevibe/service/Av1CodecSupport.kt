package com.freevibe.service

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Av1CodecSupport @Inject constructor() {

    val hasHardwareAv1Decode: Boolean by lazy {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private val MediaCodecInfo.isHardwareAccelerated: Boolean
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isHardwareAccelerated
            } else {
                !isKnownSoftwareCodec(name)
            }
        } catch (_: Exception) {
            false
        }

    internal fun isKnownSoftwareCodec(codecName: String): Boolean {
        val normalizedName = codecName.lowercase(Locale.ROOT)
        return normalizedName.startsWith("omx.google.") ||
            normalizedName.startsWith("c2.android.") ||
            normalizedName.contains(".sw.") ||
            normalizedName.contains("software")
    }

    fun preferredVideoMimeTypes(): List<String> = if (hasHardwareAv1Decode) {
        listOf(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )
    } else {
        listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )
    }
}
