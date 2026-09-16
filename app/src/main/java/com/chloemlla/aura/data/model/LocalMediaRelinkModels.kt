package com.chloemlla.aura.data.model

object LocalMediaStatus {
    const val AVAILABLE = "AVAILABLE"
    const val MISSING = "MISSING"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val CORRUPT = "CORRUPT"

    val knownValues = setOf(AVAILABLE, MISSING, PERMISSION_REVOKED, CORRUPT)
}

fun normalizeLocalMediaStatus(value: String?): String =
    value?.trim()?.uppercase(java.util.Locale.ROOT)
        ?.takeIf(LocalMediaStatus.knownValues::contains)
        ?: LocalMediaStatus.AVAILABLE

fun FavoriteEntity.needsLocalMediaRelink(): Boolean =
    normalizeLocalMediaStatus(localMediaStatus) != LocalMediaStatus.AVAILABLE

fun DownloadEntity.needsLocalMediaRelink(): Boolean =
    localPath.isNotBlank() && normalizeLocalMediaStatus(localMediaStatus) != LocalMediaStatus.AVAILABLE

fun LocalWallpaperEntity.stableLocalMediaId(): String = stableId.ifBlank { documentUri }

fun LocalWallpaperEntity.needsLocalMediaRelink(): Boolean =
    normalizeLocalMediaStatus(localMediaStatus) != LocalMediaStatus.AVAILABLE
