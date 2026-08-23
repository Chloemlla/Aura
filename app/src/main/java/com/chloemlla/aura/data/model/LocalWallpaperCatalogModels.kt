package com.chloemlla.aura.data.model

import androidx.room.Entity
import androidx.room.Index
import java.util.Locale

object LocalWallpaperFolderScanStatus {
    const val NEVER_SCANNED = "NEVER_SCANNED"
    const val SCANNING = "SCANNING"
    const val READY = "READY"
    const val READY_LIMITED = "READY_LIMITED"
    const val READY_PARTIAL = "READY_PARTIAL"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val SCAN_FAILED = "SCAN_FAILED"
}

@Entity(tableName = "local_wallpaper_folders")
data class LocalWallpaperFolderEntity(
    @androidx.room.PrimaryKey val folderUri: String,
    val displayName: String,
    val target: String = WallpaperTarget.BOTH.name,
    val addedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val scanStatus: String = LocalWallpaperFolderScanStatus.NEVER_SCANNED,
    val lastError: String = "",
    val itemCount: Int = 0,
)

@Entity(
    tableName = "local_wallpapers",
    indices = [
        Index("folderUri"),
        Index("contentHash"),
        Index("displayName"),
        Index(value = ["folderUri", "lastSeenScanToken"]),
    ],
)
data class LocalWallpaperEntity(
    @androidx.room.PrimaryKey val documentUri: String,
    val folderUri: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val contentHash: String = "",
    val tags: String = "",
    val lastSeenScanToken: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

internal fun normalizeLocalWallpaperTags(raw: String): String =
    raw
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(32) }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(12)
        .joinToString(", ")

internal fun LocalWallpaperEntity.tagsList(): List<String> =
    tags.split(',').map(String::trim).filter(String::isNotBlank)
