package com.freevibe.ui.screens.videowallpapers

import com.freevibe.data.legal.ProviderMediaType
import com.freevibe.data.legal.currentProviderPriorityBonus
import com.freevibe.data.legal.orderedCurrentProviderCapabilities
import com.freevibe.data.model.ContentSource

enum class VideoFocusFilter { BEST, LOOP_SAFE, LOW_BATTERY, PHONE_FIT }

internal fun rankVideoWallpapers(
    items: List<VideoWallpaperItem>,
    filter: VideoFocusFilter,
    orientation: OrientationFilter,
): List<VideoWallpaperItem> {
    val availableSources = orderedCurrentProviderCapabilities(ProviderMediaType.VIDEO)
        .mapTo(mutableSetOf()) { it.source }
    val availableItems = items.filter { it.providerSource() in availableSources }
    val filtered = if (filter == VideoFocusFilter.BEST) {
        availableItems
    } else {
        availableItems.filter { it.matchesFilter(filter, orientation) }
    }
    val rankedBase = filtered.ifEmpty { availableItems }
    val curated = applyVideoQualityFloor(
        rankedBase
            .distinctBy { it.id }
            .map { item -> item to videoQualityScore(item, filter, orientation) }
            .sortedByDescending { it.second }
    ).map { it.first }
    val grouped = curated
        .distinctBy { it.id }
        .groupBy { it.providerSource() }
        .mapValues { (_, sourceItems) ->
            sourceItems.sortedByDescending { videoQualityScore(it, filter, orientation) }.toMutableList()
        }
        .toMutableMap()
    val mixed = mutableListOf<VideoWallpaperItem>()
    val sourceMix = videoProviderMix(grouped.keys)
    while (grouped.values.any { it.isNotEmpty() }) {
        sourceMix.forEach { key ->
            grouped[key]?.let { sourceItems ->
                if (sourceItems.isNotEmpty()) {
                    mixed += sourceItems.removeAt(0)
                }
            }
        }
        grouped.keys
            .filterNot { it in sourceMix }
            .sortedBy { it.name }
            .forEach { key ->
                grouped[key]?.let { sourceItems ->
                    if (sourceItems.isNotEmpty()) {
                        mixed += sourceItems.removeAt(0)
                    }
                }
            }
    }
    return mixed
}

internal fun VideoWallpaperItem.previewAspectRatio(): Float = when {
    hasDimensions -> (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.56f, 1.8f)
    else -> 9f / 16f
}

private fun videoQualityScore(
    item: VideoWallpaperItem,
    filter: VideoFocusFilter,
    orientation: OrientationFilter,
): Int {
    var score = 35
    score += currentProviderPriorityBonus(item.providerSource(), ProviderMediaType.VIDEO)
    score += when {
        item.duration in 6..18 -> 16
        item.duration in 4..30 -> 10
        item.duration in 31..50 -> 4
        else -> -8
    }
    score += when (batteryTierOf(item)) {
        BatteryTier.LOW -> 12
        BatteryTier.MEDIUM -> 6
        BatteryTier.HIGH -> -6
    }
    if (item.isLoopFriendly()) score += 12
    if (!item.hasDimensions && item.source == "YouTube") score -= 4
    score += when {
        !item.hasDimensions -> 4
        orientation == OrientationFilter.PORTRAIT && item.isPortrait -> 18
        orientation == OrientationFilter.LANDSCAPE && item.isLandscape -> 18
        orientation == OrientationFilter.ALL && item.isPortrait -> 8
        else -> -10
    }
    score += when (filter) {
        VideoFocusFilter.BEST -> 0
        VideoFocusFilter.LOOP_SAFE -> if (item.isLoopFriendly()) 20 else -10
        VideoFocusFilter.LOW_BATTERY -> when (batteryTierOf(item)) {
            BatteryTier.LOW -> 20
            BatteryTier.MEDIUM -> 8
            BatteryTier.HIGH -> -12
        }
        VideoFocusFilter.PHONE_FIT -> when {
            !item.hasDimensions -> 12
            orientation == OrientationFilter.LANDSCAPE && item.isLandscape -> 16
            orientation != OrientationFilter.LANDSCAPE && item.isPortrait -> 16
            else -> -10
        }
    }
    score += minOf(12, (item.popularity / 5_000L).toInt())
    return score
}

private fun applyVideoQualityFloor(
    scored: List<Pair<VideoWallpaperItem, Int>>,
): List<Pair<VideoWallpaperItem, Int>> {
    if (scored.size < 5) return scored
    val topScore = scored.first().second
    val qualityFloor = maxOf(50, topScore - 28)
    val curated = scored.filterIndexed { index, (item, score) ->
        // Reddit Atom entries often omit duration and dimensions. Those unknowns
        // must not make the primary community inventory disappear at this stage.
        index < 3 || item.source == "Reddit" || score >= qualityFloor
    }.toMutableList()
    scored
        .groupBy { it.first.providerSource() }
        .values
        .mapNotNull { sourceItems -> sourceItems.firstOrNull() }
        .forEach { bestForSource ->
            if (
                bestForSource.second >= qualityFloor - 12 &&
                curated.none { it.first.id == bestForSource.first.id }
            ) {
                curated += bestForSource
            }
        }
    curated.sortByDescending { it.second }
    return if (curated.size >= minOf(scored.size, 4)) curated else scored
}

private fun VideoWallpaperItem.matchesFilter(filter: VideoFocusFilter, orientation: OrientationFilter): Boolean = when (filter) {
    VideoFocusFilter.BEST -> true
    VideoFocusFilter.LOOP_SAFE -> isLoopFriendly()
    VideoFocusFilter.LOW_BATTERY -> batteryTier() != BatteryTier.HIGH
    VideoFocusFilter.PHONE_FIT -> when {
        !hasDimensions -> true
        orientation == OrientationFilter.LANDSCAPE -> isLandscape
        else -> isPortrait
    }
}

internal fun VideoWallpaperItem.isLoopFriendly(): Boolean {
    val title = title.lowercase(java.util.Locale.ROOT)
    return LOOP_TERMS.any { it in title } ||
        duration in 4..18 ||
        source == "Pixabay"
}

internal fun VideoWallpaperItem.batteryTier(): BatteryTier = batteryTierOf(this)

private fun batteryTierOf(item: VideoWallpaperItem): BatteryTier {
    val pixels = item.videoWidth.toLong() * item.videoHeight.toLong()
    return when {
        pixels >= 4_500_000L || item.duration > 30 -> BatteryTier.HIGH
        pixels >= 2_000_000L || item.duration > 15 -> BatteryTier.MEDIUM
        else -> BatteryTier.LOW
    }
}

internal enum class BatteryTier { LOW, MEDIUM, HIGH }

private val LOOP_TERMS = setOf(
    "loop", "cinemagraph", "ambient", "waves", "rain", "particles", "abstract", "clouds", "neon",
)

/**
 * Build the live source cycle from the provider manifest order. Reddit keeps the
 * majority of each cycle while every eligible provider retains one slot.
 */
internal fun videoProviderMix(available: Set<ContentSource>): List<ContentSource> {
    val ordered = orderedCurrentProviderCapabilities(ProviderMediaType.VIDEO)
        .map { it.source }
        .filter { it in available }
        .toMutableList()
    ordered += available.filterNot { it in ordered }.sortedBy { it.name }
    val primary = ordered.firstOrNull() ?: return emptyList()
    if (primary != ContentSource.REDDIT) return ordered
    val secondary = ordered.drop(1)
    if (secondary.isEmpty()) return listOf(primary)
    return buildList {
        repeat(3) { add(primary) }
        repeat(3) { index ->
            add(secondary[index % secondary.size])
            if (index < 2) add(primary)
        }
    }
}

internal fun VideoWallpaperItem.providerSource(): ContentSource = when (source.lowercase(java.util.Locale.ROOT)) {
    "reddit" -> ContentSource.REDDIT
    "youtube" -> ContentSource.YOUTUBE
    "pexels" -> ContentSource.PEXELS
    "pixabay" -> ContentSource.PIXABAY
    "klipy" -> ContentSource.KLIPY
    else -> contentSource
}
