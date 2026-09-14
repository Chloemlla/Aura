package com.freevibe.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest
import java.util.Locale

const val ROTATION_MEDIA_WALLPAPER = "WALLPAPER"
const val ROTATION_MEDIA_VIDEO = "VIDEO"

private const val ROTATION_SOURCE_LOCAL_HASH = "LOCAL_HASH"
private const val ROTATION_SOURCE_LOCATOR = "LOCATOR"
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

@Immutable
data class RotationIdentity(
    val stableId: String,
    val mediaType: String,
    val source: String,
    val contentId: String,
    val contentHash: String = "",
    val title: String = "",
    val thumbnailUrl: String = "",
    val locatorDigest: String = "",
)

@Immutable
@Entity(
    tableName = "rotation_exclusions",
    indices = [Index("mediaType"), Index("source"), Index("contentHash"), Index("excludedAt")],
)
data class RotationExclusionEntity(
    @PrimaryKey val stableId: String,
    val mediaType: String,
    val source: String,
    val contentId: String,
    val contentHash: String = "",
    val title: String = "",
    val thumbnailUrl: String = "",
    val locatorDigest: String = "",
    val excludedAt: Long = System.currentTimeMillis(),
) {
    fun matches(identity: RotationIdentity): Boolean =
        stableId == identity.stableId ||
            (
                mediaType == identity.mediaType &&
                    contentHash.isNotBlank() &&
                    contentHash == identity.contentHash
            ) ||
            (
                mediaType == identity.mediaType &&
                    locatorDigest.isNotBlank() &&
                    locatorDigest == identity.locatorDigest
            )
}

/** Pre-indexed lookup keeps large exclusion libraries off quadratic feed paths. */
class RotationExclusionIndex(exclusions: List<RotationExclusionEntity>) {
    private val byStableId = exclusions.associateBy(RotationExclusionEntity::stableId)
    private val byContentHash = exclusions
        .filter { it.contentHash.isNotBlank() }
        .associateBy { exclusion -> exclusion.mediaType to exclusion.contentHash }
    private val byLocatorDigest = exclusions
        .filter { it.locatorDigest.isNotBlank() }
        .associateBy { exclusion -> exclusion.mediaType to exclusion.locatorDigest }

    fun find(identity: RotationIdentity): RotationExclusionEntity? =
        byStableId[identity.stableId]
            ?: identity.contentHash.takeIf(String::isNotBlank)?.let { hash ->
                byContentHash[identity.mediaType to hash]
            }
            ?: identity.locatorDigest.takeIf(String::isNotBlank)?.let { digest ->
                byLocatorDigest[identity.mediaType to digest]
            }

    fun contains(identity: RotationIdentity): Boolean = find(identity) != null
}

fun RotationIdentity.toRotationExclusion(excludedAt: Long = System.currentTimeMillis()) =
    RotationExclusionEntity(
        stableId = stableId,
        mediaType = mediaType,
        source = source,
        contentId = contentId,
        contentHash = contentHash,
        title = title,
        thumbnailUrl = thumbnailUrl,
        locatorDigest = locatorDigest,
        excludedAt = excludedAt,
    )

fun rotationIdentity(
    mediaType: String,
    source: String,
    contentId: String,
    contentHash: String = "",
    title: String = "",
    thumbnailUrl: String = "",
    locator: String = "",
    preferContentIdForLocal: Boolean = false,
): RotationIdentity {
    val normalizedType = normalizeRotationMediaType(mediaType)
    val normalizedSource = normalizeRotationSource(source)
    val normalizedHash = contentHash.trim().lowercase(Locale.ROOT).takeIf(SHA256_HEX::matches).orEmpty()
    val normalizedContentId = contentId.trim().ifBlank { rotationLocatorDigest(locator) }.take(2_048)
    val stableId = if (
        normalizedSource == ContentSource.LOCAL.name && normalizedHash.isNotBlank() &&
        !preferContentIdForLocal
    ) {
        "$normalizedType::$ROTATION_SOURCE_LOCAL_HASH::$normalizedHash"
    } else {
        "$normalizedType::$normalizedSource::$normalizedContentId"
    }
    return RotationIdentity(
        stableId = stableId,
        mediaType = normalizedType,
        source = normalizedSource,
        contentId = normalizedContentId,
        contentHash = normalizedHash,
        title = title.trim().take(256),
        thumbnailUrl = thumbnailUrl.trim().take(2_048),
        locatorDigest = rotationLocatorDigest(locator),
    )
}

fun rotationIdentityForLocator(
    locator: String,
    title: String = "",
): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = ROTATION_SOURCE_LOCATOR,
    contentId = rotationLocatorDigest(locator),
    title = title,
    locator = locator,
)

fun Wallpaper.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = source.name,
    contentId = id,
    contentHash = contentHash,
    title = category.ifBlank { uploaderName }.ifBlank { id },
    thumbnailUrl = thumbnailUrl,
    locator = fullUrl,
)

fun Wallpaper.rotationStableId(): String = rotationIdentity().stableId

fun FavoriteEntity.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = if (type.equals("VIDEO", ignoreCase = true)) ROTATION_MEDIA_VIDEO else ROTATION_MEDIA_WALLPAPER,
    source = source,
    contentId = id,
    title = name.ifBlank { id },
    thumbnailUrl = thumbnailUrl,
    locator = offlinePath.ifBlank { fullUrl },
)

fun WallpaperCollectionItemEntity.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = source,
    contentId = wallpaperId,
    title = wallpaperId,
    thumbnailUrl = thumbnailUrl,
    locator = fullUrl,
)

fun WallpaperCacheEntity.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = source,
    contentId = id,
    title = category.ifBlank { uploaderName }.ifBlank { id },
    thumbnailUrl = thumbnailUrl,
    locator = fullUrl,
)

fun WallpaperHistoryEntity.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = source,
    contentId = wallpaperId,
    title = wallpaperId,
    thumbnailUrl = thumbnailUrl,
    locator = fullUrl,
)

fun LocalWallpaperEntity.rotationIdentity(): RotationIdentity = rotationIdentity(
    mediaType = ROTATION_MEDIA_WALLPAPER,
    source = ContentSource.LOCAL.name,
    contentId = stableLocalMediaId(),
    contentHash = contentHash,
    title = displayName,
    thumbnailUrl = documentUri,
    locator = localRelinkIdentitySeed(),
    preferContentIdForLocal = true,
)

/** Path-independent fallback for a provider that temporarily refuses a full hash read. */
private fun LocalWallpaperEntity.localRelinkIdentitySeed(): String = listOf(
    "LOCAL_MEDIA",
    displayName.trim().lowercase(Locale.ROOT),
    mimeType.trim().lowercase(Locale.ROOT),
    sizeBytes.coerceAtLeast(0L).toString(),
    modifiedAt.coerceAtLeast(0L).toString(),
).joinToString("\u001f")

fun DownloadEntity.rotationIdentity(): RotationIdentity {
    val nestedIdentity = extractNestedWallpaperIdentity(id)
    return rotationIdentity(
        mediaType = if (type.equals("VIDEO", ignoreCase = true)) ROTATION_MEDIA_VIDEO else ROTATION_MEDIA_WALLPAPER,
        source = nestedIdentity?.first ?: source,
        contentId = nestedIdentity?.second ?: id.substringAfter(':', id),
        title = name.ifBlank { id },
        thumbnailUrl = localPath,
        locator = localPath,
    )
}

internal fun normalizeRotationMediaType(value: String): String =
    if (value.trim().equals(ROTATION_MEDIA_VIDEO, ignoreCase = true)) ROTATION_MEDIA_VIDEO else ROTATION_MEDIA_WALLPAPER

internal fun normalizeRotationSource(value: String): String = value
    .trim()
    .uppercase(Locale.ROOT)
    .filter { it.isLetterOrDigit() || it == '_' }
    .ifBlank { ROTATION_SOURCE_LOCATOR }
    .take(64)

private fun extractNestedWallpaperIdentity(value: String): Pair<String, String>? {
    val marker = "$ROTATION_MEDIA_WALLPAPER::"
    val nested = value.substringAfter(marker, missingDelimiterValue = "")
    if (nested.isBlank()) return null
    val parts = nested.split("::", limit = 2)
    if (parts.size != 2 || parts.any(String::isBlank)) return null
    return parts[0] to parts[1]
}

fun rotationLocatorDigest(locator: String): String {
    if (locator.isBlank()) return ""
    val bytes = MessageDigest.getInstance("SHA-256").digest(locator.trim().toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
