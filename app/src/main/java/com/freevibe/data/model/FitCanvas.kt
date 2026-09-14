package com.freevibe.data.model

import java.util.Locale

const val WALLPAPER_PRESENTATION_FILL = "fill"
const val WALLPAPER_PRESENTATION_FIT = "fit"
const val DEFAULT_FIT_CANVAS_COLOR = 0xFF101014.toInt()

enum class FitCanvasMode(val preferenceValue: String) {
    AMOLED_BLACK("amoled_black"),
    CUSTOM_COLOR("custom_color"),
    DOMINANT_COLOR("dominant_color"),
    BLURRED_EDGE("blurred_edge"),
    ;

    companion object {
        fun fromPreference(value: String?): FitCanvasMode =
            entries.firstOrNull {
                it.preferenceValue == value?.trim()?.lowercase(Locale.ROOT)
            } ?: AMOLED_BLACK
    }
}

data class FitCanvasStyle(
    val mode: FitCanvasMode = FitCanvasMode.AMOLED_BLACK,
    val customColor: Int = DEFAULT_FIT_CANVAS_COLOR,
) {
    fun normalized(): FitCanvasStyle = copy(customColor = customColor or 0xFF000000.toInt())
}

data class FitCanvasPreferences(
    val staticPresentation: String = WALLPAPER_PRESENTATION_FILL,
    val staticStyle: FitCanvasStyle = FitCanvasStyle(),
    val videoPresentation: String = WALLPAPER_PRESENTATION_FILL,
    val videoStyle: FitCanvasStyle = FitCanvasStyle(),
) {
    fun normalized(): FitCanvasPreferences = copy(
        staticPresentation = normalizeWallpaperPresentation(staticPresentation),
        staticStyle = staticStyle.normalized(),
        videoPresentation = normalizeWallpaperPresentation(videoPresentation),
        videoStyle = videoStyle.normalized(),
    )
}

data class ResolvedFitCanvas(
    val mode: FitCanvasMode,
    val color: Int,
    val fallbackReason: FitCanvasFallbackReason? = null,
)

enum class FitCanvasFallbackReason {
    SOURCE_COLOR_UNAVAILABLE,
    POSTER_UNAVAILABLE,
    HDR_BLUR_UNSUPPORTED,
}

fun normalizeWallpaperPresentation(value: String?): String =
    if (value?.trim()?.lowercase(Locale.ROOT) == WALLPAPER_PRESENTATION_FIT) {
        WALLPAPER_PRESENTATION_FIT
    } else {
        WALLPAPER_PRESENTATION_FILL
    }

fun resolveFitCanvas(
    style: FitCanvasStyle,
    dominantColor: Int?,
    posterAvailable: Boolean,
    isHdr: Boolean = false,
): ResolvedFitCanvas {
    val opaqueDominant = dominantColor
        ?.takeUnless { it == 0 }
        ?.or(0xFF000000.toInt())
    return when (style.mode) {
        FitCanvasMode.AMOLED_BLACK -> ResolvedFitCanvas(
            mode = FitCanvasMode.AMOLED_BLACK,
            color = android.graphics.Color.BLACK,
        )

        FitCanvasMode.CUSTOM_COLOR -> ResolvedFitCanvas(
            mode = FitCanvasMode.CUSTOM_COLOR,
            color = style.normalized().customColor,
        )

        FitCanvasMode.DOMINANT_COLOR -> if (opaqueDominant != null) {
            ResolvedFitCanvas(FitCanvasMode.DOMINANT_COLOR, opaqueDominant)
        } else {
            ResolvedFitCanvas(
                mode = FitCanvasMode.AMOLED_BLACK,
                color = android.graphics.Color.BLACK,
                fallbackReason = FitCanvasFallbackReason.SOURCE_COLOR_UNAVAILABLE,
            )
        }

        FitCanvasMode.BLURRED_EDGE -> when {
            isHdr && opaqueDominant != null -> ResolvedFitCanvas(
                mode = FitCanvasMode.DOMINANT_COLOR,
                color = opaqueDominant,
                fallbackReason = FitCanvasFallbackReason.HDR_BLUR_UNSUPPORTED,
            )

            isHdr -> ResolvedFitCanvas(
                mode = FitCanvasMode.AMOLED_BLACK,
                color = android.graphics.Color.BLACK,
                fallbackReason = FitCanvasFallbackReason.HDR_BLUR_UNSUPPORTED,
            )

            posterAvailable -> ResolvedFitCanvas(
                mode = FitCanvasMode.BLURRED_EDGE,
                color = opaqueDominant ?: android.graphics.Color.BLACK,
            )

            opaqueDominant != null -> ResolvedFitCanvas(
                mode = FitCanvasMode.DOMINANT_COLOR,
                color = opaqueDominant,
                fallbackReason = FitCanvasFallbackReason.POSTER_UNAVAILABLE,
            )

            else -> ResolvedFitCanvas(
                mode = FitCanvasMode.AMOLED_BLACK,
                color = android.graphics.Color.BLACK,
                fallbackReason = FitCanvasFallbackReason.POSTER_UNAVAILABLE,
            )
        }
    }
}

data class FitCanvasRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

fun fitCanvasRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): FitCanvasRect {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(targetWidth > 0 && targetHeight > 0)
    val scale = minOf(
        targetWidth.toFloat() / sourceWidth,
        targetHeight.toFloat() / sourceHeight,
    )
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (targetWidth - width) / 2f
    val top = (targetHeight - height) / 2f
    return FitCanvasRect(left, top, left + width, top + height)
}

data class FitCanvasSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

fun boundedFitCanvasSize(
    requestedWidth: Int,
    requestedHeight: Int,
    maxPixels: Long = 8_000_000L,
): FitCanvasSize {
    require(maxPixels > 0)
    val width = requestedWidth.coerceAtLeast(1)
    val height = requestedHeight.coerceAtLeast(1)
    val pixels = width.toLong() * height
    if (pixels <= maxPixels) return FitCanvasSize(width, height)
    val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat()
    var scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    var scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    // Coercing an extremely thin edge to one pixel can push the rounded result
    // back over the budget. Clamp the longer edge once more so even pathological
    // ultrawide dimensions keep the same hard allocation ceiling.
    if (scaledWidth.toLong() * scaledHeight > maxPixels) {
        if (scaledWidth >= scaledHeight) {
            scaledWidth = (maxPixels / scaledHeight).toInt().coerceAtLeast(1)
        } else {
            scaledHeight = (maxPixels / scaledWidth).toInt().coerceAtLeast(1)
        }
    }
    return FitCanvasSize(width = scaledWidth, height = scaledHeight)
}
