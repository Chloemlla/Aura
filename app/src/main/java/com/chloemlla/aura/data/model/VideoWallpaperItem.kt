package com.chloemlla.aura.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class VideoWallpaperItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val source: String,
    val duration: Long = 0,
    val uploaderName: String = "",
    val videoId: String = "",
    val popularity: Long = 0, // Views (YouTube), upvotes (Reddit), or 0 (Pexels)
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoRotationDegrees: Int = 0,
    val videoMimeType: String = "",
    val videoCodec: String = "",
    val contentSource: ContentSource = ContentSource.LOCAL,
    val license: String = "",
    val sourcePageUrl: String = "",
) {
    val isPortrait: Boolean get() = videoHeight > videoWidth
    val isLandscape: Boolean get() = videoWidth > videoHeight
    val hasDimensions: Boolean get() = videoWidth > 0 && videoHeight > 0
}
