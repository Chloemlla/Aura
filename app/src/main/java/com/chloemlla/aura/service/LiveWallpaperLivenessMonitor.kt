package com.chloemlla.aura.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Aura believes about its own live wallpaper right now.
 *
 * [everApplied] is what separates "the user never wanted a live wallpaper" from
 * "the one they chose is gone". Without it a static wallpaper would look like a
 * failure on every fresh install.
 */
data class LiveWallpaperLivenessState(
    val result: LiveWallpaperLivenessResult,
    val everApplied: Boolean,
    val checkedUtc: String? = null,
) {
    /**
     * True only when Aura ran a live wallpaper at some point and definitely is
     * not running one now. An UNKNOWN reading never raises the warning.
     */
    val shouldWarn: Boolean get() = everApplied && result.needsReapply
}

/**
 * Notices when Aura's live wallpaper stops being the wallpaper.
 *
 * The engines themselves cannot answer this: an engine that was dropped after a
 * reboot, replaced by another app, or killed by an OEM battery manager is not
 * running to report anything. So the app process asks the system on resume, and
 * a worker asks again after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, which
 * are exactly the two moments the platform is known to drop it.
 *
 * `getWallpaperInfo()` is a binder call and is never made from a render thread.
 */
@Singleton
class LiveWallpaperLivenessMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val receiptStore: LiveWallpaperReceiptStore,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the current state and records it.
     *
     * Safe to call from the main thread — it is one binder call and a
     * SharedPreferences write — but callers on a hot path should still move it
     * off, which is why the boot path uses a worker.
     */
    fun refresh(): LiveWallpaperLivenessState {
        val result = readLiveWallpaperActivity(context)
        // An engine that has ever drawn is proof the user applied one of ours,
        // and it survives the wallpaper being replaced afterwards.
        val everApplied = prefs.getBoolean(KEY_EVER_APPLIED, false) ||
            result.activity == LiveWallpaperActivity.ACTIVE ||
            receiptStore.readAll().any { it.lastSurfaceCreatedUtc != null }
        if (everApplied && !prefs.getBoolean(KEY_EVER_APPLIED, false)) {
            prefs.edit().putBoolean(KEY_EVER_APPLIED, true).apply()
        }
        val state = LiveWallpaperLivenessState(
            result = result,
            everApplied = everApplied,
            checkedUtc = CrashDiagnosticsCollector.timestampWithZone(System.currentTimeMillis()),
        )
        prefs.edit()
            .putString(KEY_LAST_ACTIVITY, result.activity.name)
            .putString(KEY_LAST_PACKAGE, result.runningPackage)
            .putString(KEY_LAST_CHECKED, state.checkedUtc)
            .apply()
        return state
    }

    /** The last recorded state without asking the system again. */
    fun lastKnown(): LiveWallpaperLivenessState? {
        val activityName = prefs.getString(KEY_LAST_ACTIVITY, null) ?: return null
        val activity = runCatching { LiveWallpaperActivity.valueOf(activityName) }
            .getOrDefault(LiveWallpaperActivity.UNKNOWN)
        return LiveWallpaperLivenessState(
            result = LiveWallpaperLivenessResult(
                activity = activity,
                runningPackage = prefs.getString(KEY_LAST_PACKAGE, null),
            ),
            everApplied = prefs.getBoolean(KEY_EVER_APPLIED, false),
            checkedUtc = prefs.getString(KEY_LAST_CHECKED, null),
        )
    }

    /** Called when the user launches an Aura live-wallpaper apply. */
    fun recordApplyRequested() {
        prefs.edit().putBoolean(KEY_EVER_APPLIED, true).apply()
    }

    /**
     * The engine to offer re-applying: the one that most recently had a surface.
     *
     * Null when Aura has never run one, in which case there is nothing to restore
     * and the warning is not shown either.
     */
    fun lastRunEngine(): String? = receiptStore.readAll()
        .filter { it.lastSurfaceCreatedUtc != null }
        .maxByOrNull { it.lastSurfaceCreatedUtc.orEmpty() }
        ?.engine

    private companion object {
        const val PREFS_NAME = "live_wallpaper_liveness"
        const val KEY_EVER_APPLIED = "ever_applied"
        const val KEY_LAST_ACTIVITY = "last_activity"
        const val KEY_LAST_PACKAGE = "last_package"
        const val KEY_LAST_CHECKED = "last_checked"
    }
}
