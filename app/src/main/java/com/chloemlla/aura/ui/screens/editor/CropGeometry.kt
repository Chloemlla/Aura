package com.chloemlla.aura.ui.screens.editor

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The crop frame's shape. Free uses the whole viewport, the others the largest centered frame of that ratio. */
enum class CropAspect(val ratio: Float?, val label: String?) {
    FREE(null, null),
    PORTRAIT(9f / 16f, "9:16"),
    LANDSCAPE(16f / 9f, "16:9"),
    SQUARE(1f, "1:1"),
}

/** Where the image sits: [scale] is relative to the image fitted in the viewport, offsets are viewport pixels. */
data class CropTransform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

data class CropFrame(val left: Float, val top: Float, val width: Float, val height: Float)

data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun cropFrame(viewportWidth: Int, viewportHeight: Int, aspect: CropAspect): CropFrame {
    val width = viewportWidth.toFloat()
    val height = viewportHeight.toFloat()
    val ratio = aspect.ratio ?: return CropFrame(0f, 0f, width, height)
    val (frameWidth, frameHeight) = if (width / height > ratio) height * ratio to height else width to width / ratio
    return CropFrame((width - frameWidth) / 2f, (height - frameHeight) / 2f, frameWidth, frameHeight)
}

/** Viewport pixels per source pixel at scale 1, matching ContentScale.Fit. */
internal fun cropFitScale(bitmapWidth: Int, bitmapHeight: Int, viewportWidth: Int, viewportHeight: Int): Float =
    min(viewportWidth.toFloat() / bitmapWidth, viewportHeight.toFloat() / bitmapHeight)

/**
 * Re-frames for [aspect] around the image point now under the frame's center: zooms in only as far as
 * the image needs to cover the new frame, then slides it just enough to keep the frame on the image.
 * Free keeps the transform as it is, since any part of the image can be exported.
 */
internal fun transformForAspect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    aspect: CropAspect,
    current: CropTransform,
): CropTransform {
    if (aspect.ratio == null || viewportWidth <= 0 || viewportHeight <= 0) return current
    val fit = cropFitScale(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight)
    val frame = cropFrame(viewportWidth, viewportHeight, aspect)
    val currentTotal = fit * current.scale
    val total = max(currentTotal, max(frame.width / bitmapWidth, frame.height / bitmapHeight))
    // The frame is centered in the viewport, so its center sits at the image's center plus the offset.
    val centerX = keepFrameOnImage(bitmapWidth / 2f - current.offsetX / currentTotal, frame.width / (2f * total), bitmapWidth)
    val centerY = keepFrameOnImage(bitmapHeight / 2f - current.offsetY / currentTotal, frame.height / (2f * total), bitmapHeight)
    return CropTransform(
        scale = total / fit,
        offsetX = total * (bitmapWidth / 2f - centerX),
        offsetY = total * (bitmapHeight / 2f - centerY),
    )
}

private fun keepFrameOnImage(center: Float, half: Float, size: Int): Float =
    if (half * 2f >= size) size / 2f else center.coerceIn(half, size - half)

/**
 * The source pixels under the crop frame, kept on the image. A fixed ratio is trimmed around the
 * center of what's visible, so the export is within a pixel of it even when the image is zoomed out.
 */
internal fun cropSourceRect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    aspect: CropAspect,
    transform: CropTransform,
): CropRect {
    if (viewportWidth <= 0 || viewportHeight <= 0) return CropRect(0, 0, bitmapWidth, bitmapHeight)
    val total = cropFitScale(bitmapWidth, bitmapHeight, viewportWidth, viewportHeight) * transform.scale
    val frame = cropFrame(viewportWidth, viewportHeight, aspect)
    val imageLeft = viewportWidth / 2f + transform.offsetX - bitmapWidth * total / 2f
    val imageTop = viewportHeight / 2f + transform.offsetY - bitmapHeight * total / 2f
    val left = ((frame.left - imageLeft) / total).coerceIn(0f, bitmapWidth.toFloat())
    val top = ((frame.top - imageTop) / total).coerceIn(0f, bitmapHeight.toFloat())
    val right = ((frame.left + frame.width - imageLeft) / total).coerceIn(0f, bitmapWidth.toFloat())
    val bottom = ((frame.top + frame.height - imageTop) / total).coerceIn(0f, bitmapHeight.toFloat())
    val visibleWidth = right - left
    val visibleHeight = bottom - top
    val ratio = aspect.ratio
    val (width, height) = when {
        ratio == null -> wholePixels(visibleWidth) to wholePixels(visibleHeight)
        visibleWidth / visibleHeight >= ratio -> wholePixels(visibleHeight).let { (it * ratio).roundToInt() to it }
        else -> wholePixels(visibleWidth).let { it to (it / ratio).roundToInt() }
    }.let { (w, h) -> w.coerceIn(1, bitmapWidth) to h.coerceIn(1, bitmapHeight) }
    return CropRect(
        left = ((left + right) / 2f - width / 2f).roundToInt().coerceIn(0, bitmapWidth - width),
        top = ((top + bottom) / 2f - height / 2f).roundToInt().coerceIn(0, bitmapHeight - height),
        width = width,
        height = height,
    )
}

/** Float math can land a hair under a whole pixel (2399.9998 for 2400), which floor alone would drop. */
private fun wholePixels(value: Float): Int = floor(value + PIXEL_EPSILON).toInt()

private const val PIXEL_EPSILON = 0.001f
