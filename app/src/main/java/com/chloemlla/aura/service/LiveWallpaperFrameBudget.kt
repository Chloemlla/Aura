package com.chloemlla.aura.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Battery-aware frame budget shared by the Canvas-rendered live-wallpaper engines.
 *
 * Enforces ARCHITECTURE.md's live-wallpaper discipline 2 — cap FPS to 30 by default,
 * drop to 15 when the battery is below 15% and not charging — for
 * [ParallaxWallpaperService] and [WeatherWallpaperService], which previously hard-coded
 * 33 ms and never reacted to battery state. (The video engine has its own battery logic
 * in [VideoBatteryProfile] because its render loop is MediaPlayer/GIF based.)
 */
object LiveWallpaperFrameBudget {

    /** ~30 FPS. */
    const val NORMAL_FRAME_INTERVAL_MS = 33L

    /** ~15 FPS. */
    const val LOW_BATTERY_FRAME_INTERVAL_MS = 66L

    private const val LOW_BATTERY_PERCENT = 15

    /** Frame interval for a given battery reading. */
    fun frameIntervalMs(battery: LiveWallpaperBatterySnapshot): Long =
        if (battery.isCharging || battery.percent == null || battery.percent >= LOW_BATTERY_PERCENT) {
            NORMAL_FRAME_INTERVAL_MS
        } else {
            LOW_BATTERY_FRAME_INTERVAL_MS
        }

    /**
     * Filter for the broadcasts an engine must register to keep its frame budget fresh:
     * sticky battery level changes plus system battery-saver toggles. Registering for
     * [Intent.ACTION_BATTERY_CHANGED] delivers the current state immediately on
     * registration, so the engine gets a correct initial interval without polling.
     */
    fun batteryBroadcastFilter(): IntentFilter =
        IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
}

/** A point-in-time battery reading used by [LiveWallpaperFrameBudget]. */
data class LiveWallpaperBatterySnapshot(
    val percent: Int?,
    val isCharging: Boolean,
)

/** Reads the current battery level / charging state via the sticky broadcast. */
fun readLiveWallpaperBatterySnapshot(context: Context): LiveWallpaperBatterySnapshot {
    val intent = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Exception) {
        null
    }
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).toInt().coerceIn(0, 100)
    } else {
        null
    }
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    return LiveWallpaperBatterySnapshot(
        percent = percent,
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0,
    )
}
