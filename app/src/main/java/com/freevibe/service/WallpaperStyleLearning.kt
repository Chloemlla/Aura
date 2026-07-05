package com.freevibe.service

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.absoluteValue

enum class WallpaperStyleLearningSignal(internal val weight: Int) {
    APPLIED(3),
    FAVORITED(5),
    UNFAVORITED(-2),
    SKIPPED(-5),
}

@Serializable
data class WallpaperStyleLearningProfile(
    val signalCount: Int = 0,
    val termWeights: Map<String, Int> = emptyMap(),
    val sourceWeights: Map<String, Int> = emptyMap(),
    val orientationWeights: Map<String, Int> = emptyMap(),
    val toneWeights: Map<String, Int> = emptyMap(),
) {
    val canRank: Boolean get() = signalCount >= MIN_SIGNALS_FOR_RANKING

    fun record(wallpaper: Wallpaper, signal: WallpaperStyleLearningSignal): WallpaperStyleLearningProfile {
        val weight = signal.weight
        return copy(
            signalCount = (signalCount + 1).coerceAtMost(MAX_SIGNAL_COUNT),
            termWeights = addWeights(termWeights, wallpaper.learningTerms(), weight),
            sourceWeights = addWeights(sourceWeights, listOf(wallpaper.source.learningKey()), weight),
            orientationWeights = addWeights(orientationWeights, listOf(wallpaper.learningOrientation()), weight),
            toneWeights = addWeights(toneWeights, wallpaper.learningToneKeys(), weight),
        )
    }

    fun scoreFor(wallpaper: Wallpaper): Int {
        if (!canRank) return 0
        var score = 0
        wallpaper.learningTerms().forEach { score += termWeights[it] ?: 0 }
        score += (sourceWeights[wallpaper.source.learningKey()] ?: 0) * 2
        score += (orientationWeights[wallpaper.learningOrientation()] ?: 0) * 2
        wallpaper.learningToneKeys().forEach { score += toneWeights[it] ?: 0 }
        return score.coerceIn(-30, 30)
    }

    companion object {
        val EMPTY = WallpaperStyleLearningProfile()
        const val MIN_SIGNALS_FOR_RANKING = 3
        private const val MAX_SIGNAL_COUNT = 500
        private const val MAX_WEIGHT = 40
        private const val MAX_TERMS = 80

        fun parse(raw: String): WallpaperStyleLearningProfile =
            if (raw.isBlank()) {
                EMPTY
            } else {
                runCatching { wallpaperStyleLearningJson.decodeFromString<WallpaperStyleLearningProfile>(raw) }
                    .getOrDefault(EMPTY)
            }

        fun serialize(profile: WallpaperStyleLearningProfile): String =
            if (profile == EMPTY) "" else wallpaperStyleLearningJson.encodeToString(profile)

        private fun addWeights(
            current: Map<String, Int>,
            keys: List<String>,
            weight: Int,
        ): Map<String, Int> {
            if (keys.isEmpty()) return current
            val next = current.toMutableMap()
            keys.distinct().forEach { key ->
                val updated = ((next[key] ?: 0) + weight).coerceIn(-MAX_WEIGHT, MAX_WEIGHT)
                if (updated == 0) next.remove(key) else next[key] = updated
            }
            return next.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value.absoluteValue }.thenBy { it.key })
                .take(MAX_TERMS)
                .associate { it.key to it.value }
        }
    }
}

internal val wallpaperStyleLearningJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val WORD_SPLIT_REGEX = Regex("[^a-zA-Z0-9]+")

private fun Wallpaper.learningTerms(): List<String> =
    buildList {
        addAll(tags)
        addAll(category.split(WORD_SPLIT_REGEX))
        addAll(uploaderName.split(WORD_SPLIT_REGEX))
    }
        .map { it.normalizeLearningTerm() }
        .filter { it.length >= 3 && it !in LOW_VALUE_TERMS }
        .distinct()
        .take(12)

private fun ContentSource.learningKey(): String = name.lowercase(Locale.ROOT)

private fun Wallpaper.learningOrientation(): String = when {
    width <= 0 || height <= 0 -> "unknown"
    height >= width -> "portrait"
    else -> "wide"
}

private fun Wallpaper.learningToneKeys(): List<String> = buildList {
    val normalizedTags = tags.map { it.normalizeLearningTerm() }
    if (normalizedTags.any { it in DARK_TERMS }) add("dark")
    if (normalizedTags.any { it in BRIGHT_TERMS }) add("bright")
    colors.take(3).forEach { color ->
        color.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { rgb ->
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            when {
                r + g + b < 120 -> add("dark")
                r > g + 35 && r > b + 35 -> add("warm")
                b > r + 30 && b > g + 15 -> add("cool")
                g > r + 25 && g > b + 10 -> add("organic")
                r + g + b > 660 -> add("bright")
            }
        }
    }
}.distinct()

private fun String.normalizeLearningTerm(): String = lowercase(Locale.ROOT).trim()

private val LOW_VALUE_TERMS = setOf("wallpaper", "image", "photo", "picture", "free")
private val DARK_TERMS = setOf("amoled", "oled", "black", "dark", "night", "midnight", "shadow", "space")
private val BRIGHT_TERMS = setOf("bright", "sun", "sunny", "day", "white", "light")
