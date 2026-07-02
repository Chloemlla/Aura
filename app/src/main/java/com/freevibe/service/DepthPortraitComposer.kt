package com.freevibe.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.freevibe.util.rethrowIfCancelled
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

enum class DepthBackgroundStyle {
    BLUR,
    TINT,
    AMOLED,
}

enum class DepthFrameStyle {
    NONE,
    SOFT_HALO,
    POSTER_BORDER,
}

data class DepthPortraitOptions(
    val backgroundStyle: DepthBackgroundStyle = DepthBackgroundStyle.BLUR,
    val frameStyle: DepthFrameStyle = DepthFrameStyle.NONE,
    val subjectScale: Float = 1f,
    val verticalOffset: Float = 0f,
)

data class DepthPortraitResult(
    val bitmap: Bitmap,
    val segmentationApplied: Boolean,
)

@Singleton
class DepthPortraitComposer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun compose(
        source: Bitmap,
        options: DepthPortraitOptions = DepthPortraitOptions(),
    ): DepthPortraitResult = withContext(Dispatchers.Default) {
        val base = centerCropToPortrait(source)
        val mask = try {
            segmentMask(base)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            null
        }
        if (mask == null) {
            return@withContext DepthPortraitResult(base, segmentationApplied = false)
        }

        var background: Bitmap? = null
        var foreground: Bitmap? = null
        var keepBase = false
        try {
            background = renderBackground(base, options.backgroundStyle)
            foreground = renderForeground(base, mask)
            val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(background, 0f, 0f, null)
            drawFrameUnderSubject(canvas, result, mask.bounds, options.frameStyle)
            drawForeground(canvas, foreground, options)
            drawFrameOverSubject(canvas, result, options.frameStyle)
            DepthPortraitResult(result, segmentationApplied = true)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            keepBase = true
            DepthPortraitResult(base, segmentationApplied = false)
        } finally {
            background?.takeIf { it !== base && !it.isRecycled }?.recycle()
            foreground?.takeIf { !it.isRecycled }?.recycle()
            if (!keepBase && !base.isRecycled) {
                base.recycle()
            }
        }
    }

    suspend fun exportToGallery(
        bitmap: Bitmap,
        displayName: String = "Aura_Depth_${System.currentTimeMillis()}.jpg",
    ): Result<android.net.Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = normalizeJpegName(displayName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aura")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create gallery item")
            var success = false
            try {
                val stream = resolver.openOutputStream(uri)
                    ?: throw IOException("Failed to open gallery item")
                stream.use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, EXPORT_JPEG_QUALITY, output)) {
                        throw IOException("Failed to encode depth portrait")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                success = true
                uri
            } finally {
                if (!success) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }.onFailure { it.rethrowIfCancelled() }
    }

    private fun centerCropToPortrait(source: Bitmap): Bitmap {
        val metrics = context.resources.displayMetrics
        val targetWidth = metrics.widthPixels.coerceIn(MIN_EXPORT_WIDTH, MAX_EXPORT_WIDTH)
        val screenAspect = (metrics.heightPixels.toFloat() / metrics.widthPixels.coerceAtLeast(1).toFloat())
            .coerceIn(MIN_PORTRAIT_ASPECT, MAX_PORTRAIT_ASPECT)
        val targetHeight = (targetWidth * screenAspect).roundToInt()
            .coerceIn(MIN_EXPORT_HEIGHT, MAX_EXPORT_HEIGHT)
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val scale = max(
            targetWidth.toFloat() / source.width.coerceAtLeast(1).toFloat(),
            targetHeight.toFloat() / source.height.coerceAtLeast(1).toFloat(),
        )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val dst = RectF(
            (targetWidth - drawWidth) / 2f,
            (targetHeight - drawHeight) / 2f,
            (targetWidth + drawWidth) / 2f,
            (targetHeight + drawHeight) / 2f,
        )
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), dst, smoothPaint())
        return result
    }

    private suspend fun segmentMask(bitmap: Bitmap): SubjectMask? {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        return try {
            val result = suspendCancellableCoroutine<Any?> { cont ->
                segmenter.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
                    .addOnFailureListener { _ -> if (cont.isActive) cont.resume(null) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { }
            } ?: return null
            val maskGetter = result.javaClass.getMethod("getForegroundConfidenceMask")
            val buffer = maskGetter.invoke(result) as? java.nio.FloatBuffer ?: return null
            buffer.rewind()
            val width = bitmap.width
            val height = bitmap.height
            val values = FloatArray(width * height)
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var foregroundCount = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val confidence = if (buffer.hasRemaining()) buffer.get() else 0f
                    values[index] = confidence
                    if (confidence >= SUBJECT_THRESHOLD) {
                        foregroundCount += 1
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
            val minPixels = max(MIN_SUBJECT_PIXELS, (values.size * MIN_SUBJECT_RATIO).roundToInt())
            if (foregroundCount < minPixels) {
                null
            } else {
                SubjectMask(
                    confidence = values,
                    bounds = Rect(
                        minX.coerceAtLeast(0),
                        minY.coerceAtLeast(0),
                        (maxX + 1).coerceAtMost(width),
                        (maxY + 1).coerceAtMost(height),
                    ),
                )
            }
        } finally {
            try {
                segmenter.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun renderBackground(base: Bitmap, style: DepthBackgroundStyle): Bitmap {
        return when (style) {
            DepthBackgroundStyle.BLUR -> stackBlur(base, radius = 22).also { blurred ->
                Canvas(blurred).drawColor(Color.argb(70, 0, 0, 0))
            }
            DepthBackgroundStyle.TINT -> stackBlur(base, radius = 12).also { blurred ->
                val color = averageColor(base)
                val paint = Paint().apply {
                    this.color = Color.argb(
                        132,
                        (Color.red(color) * 0.8f).roundToInt().coerceIn(0, 255),
                        (Color.green(color) * 0.8f).roundToInt().coerceIn(0, 255),
                        (Color.blue(color) * 0.8f).roundToInt().coerceIn(0, 255),
                    )
                }
                Canvas(blurred).drawRect(0f, 0f, blurred.width.toFloat(), blurred.height.toFloat(), paint)
            }
            DepthBackgroundStyle.AMOLED -> {
                val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                canvas.drawColor(Color.BLACK)
                val blurred = stackBlur(base, radius = 18)
                try {
                    canvas.drawBitmap(blurred, 0f, 0f, Paint().apply { alpha = 72 })
                } finally {
                    blurred.recycle()
                }
                result
            }
        }
    }

    private fun renderForeground(base: Bitmap, mask: SubjectMask): Bitmap {
        val pixels = IntArray(base.width * base.height)
        base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        for (i in pixels.indices) {
            val confidence = mask.confidence.getOrElse(i) { 0f }
            val alpha = (((confidence - SUBJECT_EDGE_START) / (1f - SUBJECT_EDGE_START)) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(pixels, base.width, base.height, Bitmap.Config.ARGB_8888)
    }

    private fun drawForeground(canvas: Canvas, foreground: Bitmap, options: DepthPortraitOptions) {
        val scale = options.subjectScale.coerceIn(MIN_SUBJECT_SCALE, MAX_SUBJECT_SCALE)
        val verticalOffset = options.verticalOffset.coerceIn(MIN_VERTICAL_OFFSET, MAX_VERTICAL_OFFSET)
        val width = foreground.width * scale
        val height = foreground.height * scale
        val dst = RectF(
            (foreground.width - width) / 2f,
            (foreground.height - height) / 2f + foreground.height * verticalOffset,
            (foreground.width + width) / 2f,
            (foreground.height + height) / 2f + foreground.height * verticalOffset,
        )
        canvas.drawBitmap(foreground, Rect(0, 0, foreground.width, foreground.height), dst, smoothPaint())
    }

    private fun drawFrameUnderSubject(
        canvas: Canvas,
        output: Bitmap,
        bounds: Rect,
        style: DepthFrameStyle,
    ) {
        if (style != DepthFrameStyle.SOFT_HALO) return
        val pad = max(output.width, output.height) * 0.045f
        val halo = RectF(
            (bounds.left - pad).coerceAtLeast(0f),
            (bounds.top - pad).coerceAtLeast(0f),
            (bounds.right + pad).coerceAtMost(output.width.toFloat()),
            (bounds.bottom + pad).coerceAtMost(output.height.toFloat()),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 255, 255, 255)
            maskFilter = BlurMaskFilter(max(output.width, output.height) * 0.025f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(halo, paint)
    }

    private fun drawFrameOverSubject(canvas: Canvas, output: Bitmap, frameStyle: DepthFrameStyle) {
        if (frameStyle != DepthFrameStyle.POSTER_BORDER) return
        val strokeWidth = (output.width * 0.026f).coerceAtLeast(12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        canvas.drawRect(
            strokeWidth / 2f,
            strokeWidth / 2f,
            output.width - strokeWidth / 2f,
            output.height - strokeWidth / 2f,
            paint,
        )
    }

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val scale = 1f / (1 + radius * 0.16f)
        val smallW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val smallH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== result) small.recycle()
        return result
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val step = max(1, minOf(bitmap.width, bitmap.height) / 48)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val row = IntArray(bitmap.width)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val color = row[x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count += 1
                x += step
            }
            y += step
        }
        if (count == 0L) return Color.DKGRAY
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun normalizeJpegName(name: String): String {
        val sanitized = name
            .trim()
            .ifBlank { "Aura_Depth_${System.currentTimeMillis()}" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (sanitized.lowercase(Locale.ROOT).endsWith(".jpg") ||
            sanitized.lowercase(Locale.ROOT).endsWith(".jpeg")
        ) {
            sanitized
        } else {
            "$sanitized.jpg"
        }
    }

    private fun smoothPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private data class SubjectMask(
        val confidence: FloatArray,
        val bounds: Rect,
    )

    private companion object {
        private const val MIN_EXPORT_WIDTH = 720
        private const val MAX_EXPORT_WIDTH = 1440
        private const val MIN_EXPORT_HEIGHT = 1280
        private const val MAX_EXPORT_HEIGHT = 3200
        private const val MIN_PORTRAIT_ASPECT = 1.6f
        private const val MAX_PORTRAIT_ASPECT = 2.4f
        private const val SUBJECT_THRESHOLD = 0.50f
        private const val SUBJECT_EDGE_START = 0.28f
        private const val MIN_SUBJECT_PIXELS = 2400
        private const val MIN_SUBJECT_RATIO = 0.004f
        private const val MIN_SUBJECT_SCALE = 0.92f
        private const val MAX_SUBJECT_SCALE = 1.18f
        private const val MIN_VERTICAL_OFFSET = -0.16f
        private const val MAX_VERTICAL_OFFSET = 0.16f
        private const val EXPORT_JPEG_QUALITY = 94
    }
}
