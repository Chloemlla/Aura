package com.freevibe.service

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt

/** SharedPreferences key every engine reads to decide whether to publish colors. */
const val LIVE_WALLPAPER_COLORS_ENABLED_PREF = "live_wallpaper_colors_enabled"

/**
 * The other two engines' runtime preference files.
 *
 * Each engine reads its own file, so a setting that spans all three has to be
 * written to all three. [WEATHER_WALLPAPER_PREFS_NAME] is the third.
 */
const val PARALLAX_WALLPAPER_PREFS_NAME = "freevibe_parallax"
const val VIDEO_WALLPAPER_PREFS_NAME = "freevibe_live_wp"

/** Publishing is on by default — a live wallpaper that themes nothing is the surprising case. */
const val LIVE_WALLPAPER_COLORS_ENABLED_DEFAULT = true

/**
 * Holds the [WallpaperColors] one live-wallpaper engine returns from
 * `Engine.onComputeColors()`.
 *
 * Without it the system has nothing to derive Material You theming from while an
 * Aura live wallpaper is active, so the launcher, quick settings, and every app
 * following the wallpaper palette fall back to a default that has no relationship
 * to what is on screen.
 *
 * Two invariants the engines depend on:
 *
 *  - Colors are recomputed only when the **source** changes, keyed by a caller
 *    supplied token, never per frame. Quantizing a full-screen bitmap at 30 FPS
 *    would cost more than the render it is describing.
 *  - Nothing here retains a [Bitmap]. [WallpaperColors] copies out the values it
 *    needs, so a caller is free to recycle the source the moment [update]
 *    returns. The live-wallpaper soak asserts `imageBuffers` does not grow, and
 *    this class is why that stays true after wiring colors in.
 *
 * Fields are `@Volatile` because the engines compute on their decode thread and
 * the platform calls `onComputeColors()` on the main thread. A torn read between
 * [sourceToken] and [colors] costs at most one redundant recompute, which is why
 * a lock would buy nothing here.
 */
internal class LiveWallpaperColorPublisher(
    private val computeColors: (Bitmap) -> WallpaperColors? = ::defaultComputeColors,
) {

    @Volatile private var enabled: Boolean = LIVE_WALLPAPER_COLORS_ENABLED_DEFAULT

    @Volatile private var sourceToken: String? = null

    @Volatile private var colors: WallpaperColors? = null

    /**
     * What `onComputeColors()` should return.
     *
     * Null when the user suppressed publication or no source has been seen yet;
     * returning null tells the system to keep whatever it already had rather than
     * theming from an empty palette.
     */
    val current: WallpaperColors?
        get() = if (enabled) colors else null

    /**
     * Applies the user's publication setting.
     *
     * @return true when the engine should call `notifyColorsChanged()`, which is
     *   only worth doing when the visible answer actually changed.
     */
    fun setEnabled(value: Boolean): Boolean {
        if (enabled == value) return false
        enabled = value
        return colors != null
    }

    /**
     * Recomputes from [bitmap] when [token] identifies a source different from the
     * one the cached colors came from. Passing a null token forces a recompute,
     * which is what an engine does when it cannot name its source.
     *
     * [bitmap] is read and not retained; the caller may recycle it on return.
     *
     * @return true when the engine should call `notifyColorsChanged()`.
     */
    fun update(token: String?, bitmap: Bitmap): Boolean {
        if (token != null && token == sourceToken && colors != null) return false
        if (bitmap.isRecycled) return false
        val computed = try {
            computeColors(bitmap)
        } catch (_: Throwable) {
            // A quantizer failure must not take down a decode thread; the engine
            // keeps publishing whatever it had.
            null
        } ?: return false
        return store(token, computed)
    }

    /**
     * Publishes colors an engine already knows without decoding anything — the
     * shader presets carry their own palette, so a shader-backed wallpaper can
     * theme the system even though it never loads a bitmap.
     *
     * @return true when the engine should call `notifyColorsChanged()`.
     */
    fun updateFromColors(
        token: String?,
        @ColorInt primary: Int,
        @ColorInt secondary: Int,
        @ColorInt tertiary: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
        if (token != null && token == sourceToken && colors != null) return false
        val computed = try {
            WallpaperColors(
                Color.valueOf(opaque(primary)),
                Color.valueOf(opaque(secondary)),
                Color.valueOf(opaque(tertiary)),
            )
        } catch (_: Throwable) {
            null
        } ?: return false
        return store(token, computed)
    }

    /** Drops the cache so the next source, even a repeat of the last one, recomputes. */
    fun clear() {
        sourceToken = null
        colors = null
    }

    private fun store(token: String?, computed: WallpaperColors): Boolean {
        sourceToken = token
        colors = computed
        return enabled
    }

    /**
     * WallpaperColors rejects a fully transparent color, and a shader preset's
     * fallback constants are authored for blending rather than for describing a
     * palette, so alpha is forced before handing them over.
     */
    @ColorInt
    private fun opaque(@ColorInt color: Int): Int = color or 0xFF000000.toInt()
}

/**
 * Real quantization. Split out so [LiveWallpaperColorPublisher] can be driven by a
 * stub in JVM tests: the caching policy is the part with logic in it, and it should
 * not need a working framework quantizer to be provable.
 */
private fun defaultComputeColors(bitmap: Bitmap): WallpaperColors? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        WallpaperColors.fromBitmap(bitmap)
    } else {
        // WallpaperColors and Engine.onComputeColors() both arrived in API 27, and
        // minSdk is 26 — on 26 the platform never asks, so there is nothing to build.
        null
    }
