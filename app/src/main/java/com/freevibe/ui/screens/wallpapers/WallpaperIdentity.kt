package com.freevibe.ui.screens.wallpapers

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.sanitizeCommunityOwnerKey
import com.freevibe.data.model.stableKey
import java.util.Locale

internal fun reportSourceUrl(primary: String, fallback: String): String =
    listOf(primary, fallback)
        .firstOrNull { it.startsWith("https://", ignoreCase = true) }
        .orEmpty()

internal fun Wallpaper.matchesCommunityUploader(uploaderId: String): Boolean =
    sanitizeCommunityOwnerKey(communityUploaderId).let { it.isNotBlank() && it == sanitizeCommunityOwnerKey(uploaderId) }

internal fun matchesWallpaperIdentity(
    wallpaper: Wallpaper,
    id: String,
    source: ContentSource? = null,
    fullUrl: String? = null,
): Boolean {
    if (wallpaper.id != id) return false
    if (source != null && wallpaper.source != source) return false
    return fullUrl.isNullOrBlank() || wallpaper.fullUrl == fullUrl
}

internal fun buildWallpaperDownloadFileName(
    wallpaper: Wallpaper,
    extension: String,
): String = "Aura_${wallpaper.source.name.lowercase(Locale.ROOT)}_${wallpaper.id}.$extension"

internal fun extractWallpaperLookupIds(voteKey: String): List<String> {
    if ("::" in voteKey && !voteKey.startsWith("WALLPAPER::")) return emptyList()
    val rawId = parseWallpaperVoteRawId(voteKey) ?: voteKey
    return listOf(rawId, rawId.replace("_", "."), rawId.replace("_", "/")).distinct()
}

internal fun parseWallpaperVoteRawId(voteKey: String): String? {
    val parts = voteKey.split("::", limit = 3)
    return if (parts.size == 3 && parts[0] == "WALLPAPER") parts[2] else null
}

internal fun resolveWallpaperVoteCount(
    wallpaper: Wallpaper,
    voteMap: Map<String, Int>,
    ambiguousLegacyIds: Set<String>,
    sanitizeKey: (String) -> String,
): Int? {
    val stableCandidates = listOf(wallpaper.stableKey(), sanitizeKey(wallpaper.stableKey())).distinct()
    stableCandidates.firstNotNullOfOrNull(voteMap::get)?.let { return it }

    if (wallpaper.id in ambiguousLegacyIds) return null

    val legacyCandidates = listOf(wallpaper.id, sanitizeKey(wallpaper.id)).distinct()
    return legacyCandidates.firstNotNullOfOrNull(voteMap::get)
}
