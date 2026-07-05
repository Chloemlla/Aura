package com.freevibe.ui.screens.sounds

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound

internal fun Sound.youtubeVideoId(): String? =
    takeIf { source == ContentSource.YOUTUBE }
        ?.id
        ?.removePrefix("yt_")
        ?.takeIf { it.isNotBlank() && it != id }

internal fun matchesSoundIdentity(
    sound: Sound,
    id: String,
    source: ContentSource? = null,
    previewUrl: String? = null,
    downloadUrl: String? = null,
): Boolean {
    if (sound.id != id) return false
    if (source != null && sound.source != source) return false
    if (!previewUrl.isNullOrBlank() && sound.previewUrl != previewUrl) return false
    if (!downloadUrl.isNullOrBlank() && sound.downloadUrl != downloadUrl) return false
    return true
}

internal fun youtubeDisabledMessage(): String = "YouTube features are disabled in Settings"

internal fun categorizeSoundError(error: Exception): String = when (error) {
    is java.net.UnknownHostException -> "No internet connection"
    is java.net.SocketTimeoutException -> "Connection timed out - try again"
    is java.net.ConnectException -> "Could not connect to server"
    else -> error.message ?: "Something went wrong"
}
