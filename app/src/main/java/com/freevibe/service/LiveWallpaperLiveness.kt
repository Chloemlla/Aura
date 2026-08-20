package com.freevibe.service

import android.app.WallpaperManager
import android.content.Context

/**
 * Which live wallpaper the system is actually running.
 *
 * Aura ships three `WallpaperService` implementations and never asked. A service
 * dropped after a reboot, replaced by another app, or killed by an OEM battery
 * manager was indistinguishable from a working one: the user saw a stock
 * wallpaper while Aura's settings still read "on".
 */
enum class LiveWallpaperActivity {
    /** One of Aura's engines is the running live wallpaper. */
    ACTIVE,

    /** A live wallpaper is running, but it belongs to another app. */
    REPLACED_BY_OTHER_APP,

    /** No live wallpaper at all — a static image, or the OEM default. */
    STATIC,

    /**
     * The platform would not say.
     *
     * Reported rather than guessed: treating "cannot tell" as "not active" would
     * nag users whose wallpaper is working, which is the fastest way to have the
     * warning ignored when it is real.
     */
    UNKNOWN,
}

data class LiveWallpaperLivenessResult(
    val activity: LiveWallpaperActivity,
    /** Package of the running live wallpaper, when there is one. */
    val runningPackage: String? = null,
    /** Class name of the running Aura engine, when [activity] is ACTIVE. */
    val runningService: String? = null,
) {
    /** True only when Aura is definitely not the wallpaper the user is looking at. */
    val needsReapply: Boolean
        get() = activity == LiveWallpaperActivity.REPLACED_BY_OTHER_APP ||
            activity == LiveWallpaperActivity.STATIC
}

/**
 * Classifies a `WallpaperInfo` reading against Aura's own package.
 *
 * Split from the platform call so the three cases that matter — ours, someone
 * else's, none at all — are testable without a live `WallpaperManager`.
 *
 * @param runningPackage package of the active live wallpaper, or null when the
 *   system reports no live wallpaper.
 * @param runningService class name of the active live wallpaper service.
 */
fun classifyLiveWallpaperActivity(
    runningPackage: String?,
    runningService: String?,
    ownPackage: String,
): LiveWallpaperLivenessResult = when {
    runningPackage == null -> LiveWallpaperLivenessResult(LiveWallpaperActivity.STATIC)
    runningPackage == ownPackage -> LiveWallpaperLivenessResult(
        activity = LiveWallpaperActivity.ACTIVE,
        runningPackage = runningPackage,
        runningService = runningService,
    )
    else -> LiveWallpaperLivenessResult(
        activity = LiveWallpaperActivity.REPLACED_BY_OTHER_APP,
        runningPackage = runningPackage,
    )
}

/**
 * Asks the system which live wallpaper is running.
 *
 * `getWallpaperInfo()` is a binder call. It must never be made from a render
 * thread — a wallpaper engine asking the window manager about itself mid-draw is
 * how frame deadlines get missed — so this is called from the app process on
 * resume and from a worker after boot, never from an engine.
 */
fun readLiveWallpaperActivity(context: Context): LiveWallpaperLivenessResult = try {
    val info = WallpaperManager.getInstance(context).wallpaperInfo
    classifyLiveWallpaperActivity(
        runningPackage = info?.packageName,
        runningService = info?.serviceName,
        ownPackage = context.packageName,
    )
} catch (_: Throwable) {
    // Some OEM builds throw rather than answer, and a few disable live wallpapers
    // entirely. Neither is evidence that Aura's wallpaper stopped working.
    LiveWallpaperLivenessResult(LiveWallpaperActivity.UNKNOWN)
}
