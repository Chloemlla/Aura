package com.freevibe.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.palette.graphics.Palette
import com.freevibe.data.model.FitCanvasMode
import com.freevibe.data.model.FitCanvasStyle
import com.freevibe.data.model.ResolvedFitCanvas
import com.freevibe.data.model.boundedFitCanvasSize
import com.freevibe.data.model.fitCanvasRect
import com.freevibe.data.model.resolveFitCanvas
import kotlin.math.max

data class FitCanvasRenderResult(
    val bitmap: Bitmap,
    val resolvedCanvas: ResolvedFitCanvas,
)

object FitCanvasRenderer {
    const val MAX_OUTPUT_PIXELS = 8_000_000L
    private const val BLUR_SAMPLE_LONG_EDGE = 96

    fun render(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        style: FitCanvasStyle,
        dominantColor: Int? = null,
        isHdr: Boolean = source.config == Bitmap.Config.RGBA_F16,
    ): FitCanvasRenderResult {
        require(!source.isRecycled)
        val size = boundedFitCanvasSize(targetWidth, targetHeight, MAX_OUTPUT_PIXELS)
        val sourceColor = dominantColor ?: dominantOpaqueColor(source)
        val resolved = resolveFitCanvas(
            style = style,
            dominantColor = sourceColor,
            posterAvailable = true,
            isHdr = isHdr,
        )
        val output = Bitmap.createBitmap(
            size.width,
            size.height,
            if (isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        drawBackground(canvas, source, resolved)
        drawFittedForeground(canvas, source)
        return FitCanvasRenderResult(output, resolved)
    }

    fun renderBackground(
        source: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        style: FitCanvasStyle,
        dominantColor: Int? = source?.let(::dominantOpaqueColor),
        isHdr: Boolean = source?.config == Bitmap.Config.RGBA_F16,
    ): FitCanvasRenderResult {
        val size = boundedFitCanvasSize(targetWidth, targetHeight, MAX_OUTPUT_PIXELS)
        val resolved = resolveFitCanvas(
            style = style,
            dominantColor = dominantColor,
            posterAvailable = source != null,
            isHdr = isHdr,
        )
        val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        drawBackground(Canvas(output), source, resolved)
        return FitCanvasRenderResult(output, resolved)
    }

    private fun drawBackground(canvas: Canvas, source: Bitmap?, resolved: ResolvedFitCanvas) {
        canvas.drawColor(resolved.color)
        if (resolved.mode != FitCanvasMode.BLURRED_EDGE || source == null) return

        val longest = max(source.width, source.height).coerceAtLeast(1)
        val sampleScale = minOf(1f, BLUR_SAMPLE_LONG_EDGE.toFloat() / longest)
        val sampleWidth = (source.width * sampleScale).toInt().coerceAtLeast(1)
        val sampleHeight = (source.height * sampleScale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, sampleWidth, sampleHeight, true)
        try {
            drawCenterCrop(canvas, small)
            canvas.drawColor(Color.argb(42, 0, 0, 0))
        } finally {
            if (small !== source && !small.isRecycled) small.recycle()
        }
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap) {
        val scale = max(
            canvas.width.toFloat() / bitmap.width.coerceAtLeast(1),
            canvas.height.toFloat() / bitmap.height.coerceAtLeast(1),
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (canvas.width - width) / 2f
        val top = (canvas.height - height) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + width, top + height),
            smoothPaint(),
        )
    }

    private fun drawFittedForeground(canvas: Canvas, bitmap: Bitmap) {
        val rect = fitCanvasRect(bitmap.width, bitmap.height, canvas.width, canvas.height)
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(rect.left, rect.top, rect.right, rect.bottom),
            smoothPaint(),
        )
    }

    internal fun averageOpaqueColor(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
        val stepX = max(1, (bitmap.width + PALETTE_SAMPLE_AXIS - 1) / PALETTE_SAMPLE_AXIS)
        val stepY = max(1, (bitmap.height + PALETTE_SAMPLE_AXIS - 1) / PALETTE_SAMPLE_AXIS)
        val row = IntArray(bitmap.width)
        var weightedRed = 0L
        var weightedGreen = 0L
        var weightedBlue = 0L
        var alphaTotal = 0L
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val color = row[x]
                val alpha = Color.alpha(color)
                if (alpha > 0) {
                    weightedRed += Color.red(color).toLong() * alpha
                    weightedGreen += Color.green(color).toLong() * alpha
                    weightedBlue += Color.blue(color).toLong() * alpha
                    alphaTotal += alpha
                }
                x += stepX
            }
            y += stepY
        }
        if (alphaTotal == 0L) return null
        return Color.rgb(
            (weightedRed / alphaTotal).toInt(),
            (weightedGreen / alphaTotal).toInt(),
            (weightedBlue / alphaTotal).toInt(),
        )
    }

    internal fun dominantOpaqueColor(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
        val colors = sampledOpaqueColors(bitmap)
        if (colors.isEmpty()) return null
        val sample = Bitmap.createBitmap(colors.size, 1, Bitmap.Config.ARGB_8888).apply {
            setPixels(colors, 0, colors.size, 0, 0, colors.size, 1)
        }
        val paletteColor = try {
            runCatching {
                Palette.from(sample)
                    .maximumColorCount(16)
                    .generate()
                    .dominantSwatch
                    ?.rgb
            }.getOrNull()
        } finally {
            sample.recycle()
        }
            ?.takeUnless { it == 0 }
            ?.or(0xFF000000.toInt())
        return paletteColor ?: averageOpaqueColor(bitmap)
    }

    private fun sampledOpaqueColors(bitmap: Bitmap): IntArray {
        val stepX = max(1, (bitmap.width + PALETTE_SAMPLE_AXIS - 1) / PALETTE_SAMPLE_AXIS)
        val stepY = max(1, (bitmap.height + PALETTE_SAMPLE_AXIS - 1) / PALETTE_SAMPLE_AXIS)
        val sampledWidth = (bitmap.width + stepX - 1) / stepX
        val sampledHeight = (bitmap.height + stepY - 1) / stepY
        val colors = IntArray(sampledWidth * sampledHeight)
        val row = IntArray(bitmap.width)
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val color = row[x]
                if (Color.alpha(color) > 0) {
                    colors[count++] = color or 0xFF000000.toInt()
                }
                x += stepX
            }
            y += stepY
        }
        return colors.copyOf(count)
    }

    private fun smoothPaint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private const val PALETTE_SAMPLE_AXIS = 64
}
