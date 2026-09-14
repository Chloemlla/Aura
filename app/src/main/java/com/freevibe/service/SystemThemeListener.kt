package com.freevibe.service

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.ROTATION_MEDIA_WALLPAPER
import com.freevibe.data.model.rotationIdentity
import com.freevibe.data.model.rotationIdentityForLocator
import com.freevibe.data.repository.RotationExclusionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors system dark mode changes and auto-applies the corresponding wallpaper when
 * the user has dark/light mode auto-switch enabled.
 *
 * Implementation note: this uses [ComponentCallbacks.onConfigurationChanged] (an event)
 * rather than the original 500 ms polling loop. The polling design had three real
 * problems:
 *  1. It never stopped — toggling the feature off in Settings left the loop running.
 *  2. It woke the CPU twice a second forever, which is noticeable on battery dashboards.
 *  3. The collect-then-while(true) structure trapped the outer Flow collector so further
 *     pref emissions couldn't propagate.
 * Configuration changes are delivered to ComponentCallbacks regardless of which Activity
 * is foreground, so this works even while the app is fully backgrounded.
 */
@Singleton
class SystemThemeListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val wallpaperApplier: WallpaperApplier,
    private val rotationExclusions: RotationExclusionRepository,
    private val receiptStore: BackgroundWorkReceiptStore,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    @Volatile private var darkLightPairEnabled = false
    @Volatile private var nightVariantEnabled = false
    @Volatile private var lastNightMode = isNightMode()
    private var prefJob: Job? = null

    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (!darkLightPairEnabled && !nightVariantEnabled) return
            val isNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (isNight == lastNightMode) return
            lastNightMode = isNight
            scope.launch { applyForMode(isNight) }
        }

        override fun onLowMemory() {}
    }

    fun startListening() {
        // Track both theme-driven features without a polling loop.
        prefJob?.cancel()
        prefJob = scope.launch {
            combine(
                prefs.darkModeAutoSwitch,
                prefs.autoWallpaperNightVariantEnabled,
            ) { pairEnabled, variantEnabled -> pairEnabled to variantEnabled }
                .distinctUntilChanged()
                .collect { (pairEnabled, variantEnabled) ->
                    val variantWasEnabled = nightVariantEnabled
                    darkLightPairEnabled = pairEnabled
                    nightVariantEnabled = variantEnabled
                    if (pairEnabled || variantEnabled) {
                        // Resync the baseline so we don't re-apply on the very next config change
                        // when the user enables the toggle while already in (say) dark mode.
                        lastNightMode = isNightMode()
                    }
                    when {
                        variantEnabled && !variantWasEnabled -> applyForMode(lastNightMode)
                        !variantEnabled && variantWasEnabled && pairEnabled -> applyForMode(lastNightMode)
                        !variantEnabled && variantWasEnabled -> applyLastNightVariantWallpaper(isNight = false)
                    }
                }
        }
        // ComponentCallbacks registration is idempotent if we ever wire startListening
        // to be re-entrant; Application.registerComponentCallbacks tolerates a re-add
        // but we keep one registration for the singleton's life regardless.
        runCatching { context.applicationContext.registerComponentCallbacks(callbacks) }
    }

    /** Test/debug entrypoint — apply whichever wallpaper matches the requested mode. */
    suspend fun applyForMode(isNight: Boolean) {
        try {
            val pairEnabled = prefs.darkModeAutoSwitch.first()
            val variantEnabled = prefs.autoWallpaperNightVariantEnabled.first()
            if (pairEnabled) {
                val wallpaperId = if (isNight) {
                    prefs.darkModeWallpaperId.first()
                } else {
                    prefs.lightModeWallpaperId.first()
                }
                if (wallpaperId.isBlank()) return
                applyStoredWallpaper(wallpaperId, nightVariant = isNight && variantEnabled)
            } else if (variantEnabled) {
                applyLastNightVariantWallpaper(isNight)
            } else {
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            // Auto-apply must not crash the host app. The Settings UI surfaces the
            // current selection so the user can re-pick if a stored URL is no longer valid.
            receiptStore.recordFailure(
                uniqueWorkName = WORK_NAME,
                errorClass = error.javaClass.simpleName,
                deferralReason = "Theme wallpaper switching failed. Open its source and try a manual apply.",
            )
        }
    }

    private suspend fun applyStoredWallpaper(wallpaperId: String, nightVariant: Boolean) {
        // Wallpaper ID format: "source|id|url" (stored when user applies a wallpaper).
        // Split with limit=3 so URLs that happen to contain "|" stay intact.
        val parts = wallpaperId.split("|", limit = 3)
        if (parts.size < 3) return
        val url = parts[2]
        if (url.isBlank()) return
        val identity = rotationIdentity(
            mediaType = ROTATION_MEDIA_WALLPAPER,
            source = parts[0],
            contentId = parts[1],
            locator = url,
        )
        if (rotationExclusions.isExcluded(identity)) {
            recordExcluded("The selected theme wallpaper")
            return
        }
        // applyByLocator handles http(s) URLs, file:// URIs (AI-generated / parallax-cached),
        // and content:// URIs (uploads / gallery picks). Earlier revisions called
        // applyFromUrl which only spoke HTTP and threw IllegalArgumentException for any
        // other scheme — silently breaking auto-switch for AI-generated wallpapers.
        recordApplyResult(
            wallpaperApplier.applyByLocator(
                url,
                WallpaperTarget.BOTH,
                nightVariant = nightVariant,
            ),
        )
    }

    private suspend fun applyLastNightVariantWallpaper(isNight: Boolean) {
        val locator = prefs.lastNightVariantWallpaperLocator.first()
        if (locator.isBlank()) return
        if (rotationExclusions.isExcluded(rotationIdentityForLocator(locator))) {
            recordExcluded("The night variant source")
            return
        }
        val target = runCatching {
            WallpaperTarget.valueOf(prefs.lastNightVariantWallpaperTarget.first())
        }.getOrDefault(WallpaperTarget.BOTH)
        recordApplyResult(
            wallpaperApplier.applyByLocator(
                locator = locator,
                target = target,
                darkenPercent = prefs.lastNightVariantWallpaperDarkenPercent.first(),
                nightVariant = isNight,
            ),
        )
    }

    private fun recordExcluded(label: String) {
        receiptStore.recordFailure(
            uniqueWorkName = WORK_NAME,
            errorClass = "RotationItemExcluded",
            deferralReason = "$label is excluded. Restore it in Settings, Wallpaper rotation, Rotation exclusions.",
        )
    }

    private fun recordApplyResult(result: Result<Unit>) {
        result.fold(
            onSuccess = { receiptStore.recordSuccess(WORK_NAME) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                receiptStore.recordFailure(
                    uniqueWorkName = WORK_NAME,
                    errorClass = error.javaClass.simpleName,
                    deferralReason = "Theme wallpaper switching failed. Open its source and try a manual apply.",
                )
            },
        )
    }

    private fun isNightMode(): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val WORK_NAME = "system_theme_wallpaper"
    }
}
