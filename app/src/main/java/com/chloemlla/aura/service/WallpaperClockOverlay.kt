package com.chloemlla.aura.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import com.chloemlla.aura.R
import java.util.Date
import kotlin.math.min

const val WALLPAPER_CLOCK_OVERLAY_PREFS_NAME = "freevibe_wallpaper_clock_overlay"
const val WALLPAPER_CLOCK_OVERLAY_ENABLED_PREF = "enabled"
const val WALLPAPER_CLOCK_OVERLAY_MODE_PREF = "mode"
const val WALLPAPER_CLOCK_OVERLAY_POSITION_PREF = "position"

enum class WallpaperClockOverlayMode(val preferenceValue: String) {
    TIME("time"),
    DATE("date"),
    TIME_AND_DATE("time_and_date");

    companion object {
        fun fromPreference(value: String?): WallpaperClockOverlayMode =
            entries.firstOrNull { it.preferenceValue == value } ?: TIME_AND_DATE
    }
}

enum class WallpaperClockOverlayPosition(val preferenceValue: String) {
    TOP_LEFT("top_left"),
    TOP_RIGHT("top_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_RIGHT("bottom_right");

    companion object {
        fun fromPreference(value: String?): WallpaperClockOverlayPosition =
            entries.firstOrNull { it.preferenceValue == value } ?: BOTTOM_RIGHT
    }
}

data class WallpaperClockOverlayConfig(
    val enabled: Boolean = false,
    val mode: WallpaperClockOverlayMode = WallpaperClockOverlayMode.TIME_AND_DATE,
    val position: WallpaperClockOverlayPosition = WallpaperClockOverlayPosition.BOTTOM_RIGHT,
)

fun readWallpaperClockOverlayConfig(context: Context): WallpaperClockOverlayConfig {
    val prefs = context.getSharedPreferences(WALLPAPER_CLOCK_OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
    return WallpaperClockOverlayConfig(
        enabled = prefs.getBoolean(WALLPAPER_CLOCK_OVERLAY_ENABLED_PREF, false),
        mode = WallpaperClockOverlayMode.fromPreference(
            prefs.getString(WALLPAPER_CLOCK_OVERLAY_MODE_PREF, null),
        ),
        position = WallpaperClockOverlayPosition.fromPreference(
            prefs.getString(WALLPAPER_CLOCK_OVERLAY_POSITION_PREF, null),
        ),
    )
}

fun isWallpaperClockOverlayEnabled(context: Context): Boolean =
    readWallpaperClockOverlayConfig(context).enabled

internal fun wallpaperClockOverlayLabel(
    context: Context,
    mode: WallpaperClockOverlayMode,
    now: Date,
): String {
    val time = DateFormat.getTimeFormat(context).format(now)
    val date = DateFormat.getDateFormat(context).format(now)
    return when (mode) {
        WallpaperClockOverlayMode.TIME -> time
        WallpaperClockOverlayMode.DATE -> date
        WallpaperClockOverlayMode.TIME_AND_DATE ->
            context.getString(R.string.wallpaper_clock_overlay_time_date, time, date)
    }
}

fun drawWallpaperClockOverlay(
    context: Context,
    canvas: Canvas,
    now: Date = Date(),
    config: WallpaperClockOverlayConfig = readWallpaperClockOverlayConfig(context),
) {
    if (!config.enabled || canvas.width <= 0 || canvas.height <= 0) return

    val density = context.resources.displayMetrics.density
    val shortestSide = min(canvas.width, canvas.height).toFloat()
    val textSize = (shortestSide * 0.045f).coerceIn(24f * density, 68f * density)
    val paddingX = 14f * density
    val paddingY = 9f * density
    val margin = (shortestSide * 0.055f).coerceAtLeast(18f * density)
    val label = wallpaperClockOverlayLabel(context, config.mode, now)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
    }
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8000000.toInt()
    }
    val textWidth = textPaint.measureText(label)
    val fontMetrics = textPaint.fontMetrics
    val textHeight = fontMetrics.bottom - fontMetrics.top
    val boxWidth = textWidth + paddingX * 2f
    val boxHeight = textHeight + paddingY * 2f
    val top = when (config.position) {
        WallpaperClockOverlayPosition.TOP_LEFT,
        WallpaperClockOverlayPosition.TOP_RIGHT -> margin
        WallpaperClockOverlayPosition.BOTTOM_LEFT,
        WallpaperClockOverlayPosition.BOTTOM_RIGHT -> canvas.height - margin - boxHeight
    }.coerceIn(0f, (canvas.height - boxHeight).coerceAtLeast(0f))
    val left = when (config.position) {
        WallpaperClockOverlayPosition.TOP_LEFT,
        WallpaperClockOverlayPosition.BOTTOM_LEFT -> margin
        WallpaperClockOverlayPosition.TOP_RIGHT,
        WallpaperClockOverlayPosition.BOTTOM_RIGHT -> canvas.width - margin - boxWidth
    }.coerceIn(0f, (canvas.width - boxWidth).coerceAtLeast(0f))
    val rect = RectF(left, top, left + boxWidth, top + boxHeight)

    canvas.drawRoundRect(rect, 12f * density, 12f * density, backgroundPaint)
    val baseline = rect.top + paddingY - fontMetrics.top
    textPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(label, rect.left + paddingX, baseline, textPaint)
}

/** Returns an owned copy when the overlay is enabled; the input remains caller-owned. */
fun bitmapWithWallpaperClockOverlay(
    context: Context,
    bitmap: Bitmap,
    now: Date = Date(),
): Bitmap {
    if (!isWallpaperClockOverlayEnabled(context)) return bitmap
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
    drawWallpaperClockOverlay(context, Canvas(copy), now)
    return copy
}

/**
 * Reusable clock-overlay painter for the live-wallpaper engines.
 *
 * The engines used to call [drawWallpaperClockOverlay] with its default arguments
 * on every frame, which re-read SharedPreferences, recreated two [DateFormat]s
 * (one of which queries Settings.System for the 12/24-hour clock) and allocated
 * two [Paint]s at 30 FPS. This renderer caches the config (the engine refreshes it
 * on visibility / surface changes, so a Settings toggle still takes effect promptly)
 * and the layout objects (keyed on canvas size), and re-formats the label at most
 * once per minute.
 */
class WallpaperClockOverlayRenderer {
    private var config = WallpaperClockOverlayConfig()
    private var layoutKey: String? = null
    private var textPaint: Paint? = null
    private var backgroundPaint: Paint? = null
    private var labelMinuteBucket: Long = Long.MIN_VALUE
    private var cachedLabel: String? = null

    /** True when the overlay is currently enabled per the last [refresh]. */
    val enabled: Boolean get() = config.enabled

    fun refresh(context: Context) {
        config = readWallpaperClockOverlayConfig(context)
    }

    fun draw(
        context: Context,
        canvas: Canvas,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!config.enabled || canvas.width <= 0 || canvas.height <= 0) return
        val now = Date(nowMs)
        val density = context.resources.displayMetrics.density
        val shortestSide = min(canvas.width, canvas.height).toFloat()
        val textSize = (shortestSide * 0.045f).coerceIn(24f * density, 68f * density)
        val key = "${config.mode.name}|${config.position.name}|${canvas.width}x${canvas.height}|$density"
        if (layoutKey != key) {
            layoutKey = key
            textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
            }
            backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB8000000.toInt()
            }
            cachedLabel = null
        }
        val minuteBucket = nowMs / 60_000L
        if (minuteBucket != labelMinuteBucket) {
            labelMinuteBucket = minuteBucket
            cachedLabel = wallpaperClockOverlayLabel(context, config.mode, now)
        }
        val label = cachedLabel ?: return
        val tp = textPaint ?: return
        val bp = backgroundPaint ?: return
        val paddingX = 14f * density
        val paddingY = 9f * density
        val margin = (shortestSide * 0.055f).coerceAtLeast(18f * density)
        val textWidth = tp.measureText(label)
        val fontMetrics = tp.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val boxWidth = textWidth + paddingX * 2f
        val boxHeight = textHeight + paddingY * 2f
        val top = when (config.position) {
            WallpaperClockOverlayPosition.TOP_LEFT,
            WallpaperClockOverlayPosition.TOP_RIGHT -> margin
            WallpaperClockOverlayPosition.BOTTOM_LEFT,
            WallpaperClockOverlayPosition.BOTTOM_RIGHT -> canvas.height - margin - boxHeight
        }.coerceIn(0f, (canvas.height - boxHeight).coerceAtLeast(0f))
        val left = when (config.position) {
            WallpaperClockOverlayPosition.TOP_LEFT,
            WallpaperClockOverlayPosition.BOTTOM_LEFT -> margin
            WallpaperClockOverlayPosition.TOP_RIGHT,
            WallpaperClockOverlayPosition.BOTTOM_RIGHT -> canvas.width - margin - boxWidth
        }.coerceIn(0f, (canvas.width - boxWidth).coerceAtLeast(0f))
        val rect = RectF(left, top, left + boxWidth, top + boxHeight)

        canvas.drawRoundRect(rect, 12f * density, 12f * density, bp)
        val baseline = rect.top + paddingY - fontMetrics.top
        tp.textAlign = Paint.Align.LEFT
        canvas.drawText(label, rect.left + paddingX, baseline, tp)
    }
}
