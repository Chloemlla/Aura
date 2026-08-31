package com.chloemlla.aura.ui.screens.favorites

import com.chloemlla.aura.data.model.FavoriteEntity

/**
 * Picks what a favorite card should actually load.
 *
 * Wallpaper favorites are cached to a managed local file when they are saved, but
 * the grid still asked for the remote thumbnail — so in airplane mode, or on a
 * cold image cache, every card rendered broken even though the bytes were sitting
 * on disk. Preferring the local copy costs nothing (it is the same file, not a
 * second one) and makes the offline library actually work offline.
 *
 * Sound favorites are unaffected: their offline copy is audio, not an image.
 *
 * @param fileExists injected so the rule is unit-testable without touching disk.
 */
internal fun favoriteThumbnailModel(
    favorite: FavoriteEntity,
    fileExists: (String) -> Boolean,
): String {
    if (!favorite.type.equals("WALLPAPER", ignoreCase = true)) return favorite.thumbnailUrl
    val offlinePath = favorite.offlinePath.trim()
    if (offlinePath.isEmpty()) return favorite.thumbnailUrl
    // An evicted or externally deleted file falls back to the remote URL rather
    // than rendering nothing.
    if (!fileExists(offlinePath)) return favorite.thumbnailUrl
    return offlinePath
}
