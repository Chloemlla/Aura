package com.chloemlla.aura

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.annotation.ExperimentalCoilApi
import coil3.memoryCacheMaxSizePercentWhileInBackground
import coil3.request.crossfade
import com.chloemlla.aura.data.local.WallpaperCacheManager
import com.chloemlla.aura.service.NotificationChannels
import com.chloemlla.aura.service.AppCheckInstaller
import com.chloemlla.aura.service.ClashProxyManager
import com.chloemlla.aura.service.OfflineFavoritesManager
import com.chloemlla.aura.service.PathBackedRecordReconciler
import com.chloemlla.aura.util.LocaleHelper
import com.chloemlla.lumen.crash.LumenCrash
import com.chloemlla.lumen.crash.LumenCrashConfig
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class AuraApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var wallpaperCacheManager: WallpaperCacheManager

    @Inject
    lateinit var offlineFavoritesManager: OfflineFavoritesManager

    @Inject
    lateinit var systemThemeListener: com.chloemlla.aura.service.SystemThemeListener

    @Inject
    lateinit var pathBackedRecordReconciler: PathBackedRecordReconciler

    @Inject
    lateinit var clashProxyManager: ClashProxyManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
        installLumenCrashSdk()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: android.content.Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.newBuilder().build() }))
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25) // 25% of available app memory
                .build()
        }
        // Shrink the bitmap cache to 15% of its max while backgrounded so a wallpaper app
        // that holds many large images does not retain foreground-sized RAM off-screen.
        .memoryCacheMaxSizePercentWhileInBackground(0.15)
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "coil_cache").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024) // 256 MB — wallpaper app needs generous image cache
                .build()
        }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        installLumenCrashSdk() // idempotent via isInstalled() check
        installAppCheck()
        NotificationChannels.createAll(this)
        evictStaleCaches()
        startSystemThemeListener()
        startClashProxy()
        initYtDlp()
        enqueueAuraOriginalsDownload()
        publishWidgetPreview()
        reconcileRotationTriggers()
    }

    private fun installLumenCrashSdk() {
        if (LumenCrash.isInstalled()) return
        runCatching {
            LumenCrash.install(
                this,
                LumenCrashConfig(
                    appDisplayName = getString(R.string.app_name),
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    commitHash = BuildConfig.SHORT_HASH,
                    fileProviderAuthority = "${packageName}.fileprovider",
                    shareSubject = getString(R.string.crash_report_share_subject),
                    reportTitle = getString(R.string.crash_report_title),
                    reportMessage = getString(R.string.crash_report_message),
                ),
            )
        }.onFailure { e ->
            Log.e("AuraApp", "LumenCrash.install() failed", e)
        }
    }

    private fun startClashProxy() {
        appScope.launch {
            try {
                clashProxyManager.start()
                if (BuildConfig.DEBUG) Log.d("AuraApp", "ClashProxyManager started")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (BuildConfig.DEBUG) Log.w("AuraApp", "ClashProxyManager start failed: ${e.message}")
            }
        }
    }

    private fun installAppCheck() {
        try {
            AppCheckInstaller.install(this)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "App Check init failed: ${e.message}")
        }
    }

    /**
     * NX-6: on cold start, read the rotate-on-unlock + rotate-on-screen-off prefs
     * and reconcile the foreground [RotationTriggerService]. The service stays
     * idle (no notification, no work) until at least one trigger is opted in.
     * Settings UI calls [RotationTriggerService.reconcile] directly on toggle.
     */
    private fun reconcileRotationTriggers() {
        appScope.launch {
            try {
                val prefs = com.chloemlla.aura.data.local.PreferencesManager(this@AuraApp)
                val unlock = prefs.rotateOnUnlock.first()
                val screenOff = prefs.rotateOnScreenOff.first()
                if (unlock || screenOff) {
                    com.chloemlla.aura.service.RotationTriggerService.reconcile(
                        this@AuraApp, unlock = unlock, screenOff = screenOff,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "Rotation trigger reconcile failed: ${e.message}")
            }
        }
    }

    /**
     * Roadmap N-5: schedule the Aura Originals CC0 sound pack download on Wi-Fi.
     * Worker is enqueued every cold start with KEEP policy, so existing successful
     * downloads aren't redone and the pack converges idempotently.
     */
    private fun enqueueAuraOriginalsDownload() {
        try {
            com.chloemlla.aura.service.AuraOriginalsDownloader.enqueue(this)
        } catch (e: Exception) {
            // Must never crash app startup on a WorkManager queue failure.
            if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "AuraOriginals enqueue failed: ${e.message}")
        }
    }

    private fun publishWidgetPreview() {
        appScope.launch {
            try {
                com.chloemlla.aura.widget.WidgetPreviewPublisher.publishIfNeeded(this@AuraApp)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "Widget preview publish failed", e)
            }
        }
    }

    private fun initYtDlp() {
        appScope.launch {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this@AuraApp)
                if (BuildConfig.DEBUG) Log.d("AuraApp", "yt-dlp initialized")
            } catch (e: Throwable) {
                // Must catch CancellationException-as-Throwable here? Throwable catches it, but
                // we never cancel appScope before process death, so swallowing is OK. We do NOT
                // rethrow: init-failure of yt-dlp should degrade the YouTube tab, not kill the app.
                if (BuildConfig.DEBUG) Log.e("AuraApp", "yt-dlp init failed: ${e.message}")
            }
        }
    }

    private fun evictStaleCaches() {
        appScope.launch {
            try {
                wallpaperCacheManager.evictExpired()
                offlineFavoritesManager.pruneOrphans()
                pathBackedRecordReconciler.reconcile()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "Cache eviction failed", e)
            }
        }
    }

    private fun startSystemThemeListener() {
        appScope.launch {
            try {
                systemThemeListener.startListening()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Dark/light mode auto-switch is optional; never crash on startup
                if (BuildConfig.DEBUG) Log.w("FreeVibeApp", "System theme listener failed", e)
            }
        }
    }
}
