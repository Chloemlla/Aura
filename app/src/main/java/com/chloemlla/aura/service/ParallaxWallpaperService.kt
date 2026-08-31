package com.chloemlla.aura.service

import android.app.WallpaperColors
import android.app.wallpaper.WallpaperDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import com.chloemlla.aura.BuildConfig
import com.chloemlla.aura.R
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.Executors

/**
 * Live wallpaper service that creates a parallax/depth effect by splitting
 * a wallpaper image into foreground and background layers using ML Kit
 * Subject Segmentation (multi-subject, GA — replaced the long-running
 * selfie-segmentation beta per ROADMAP N-3), then shifting layers at
 * different rates based on device tilt (accelerometer).
 *
 * The segmenter model is unbundled — downloaded on first use via Google Play
 * services. We proactively request the install at engine creation so the
 * first apply isn't a silent no-op.
 *
 * Falls back to displaying the image normally if segmentation fails.
 */
class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    @RequiresApi(36)
    override fun onCreateEngine(description: WallpaperDescription): Engine =
        ParallaxEngine(readAuraWallpaperDescriptionContent(description)?.source)

    inner class ParallaxEngine(
        private val describedImagePath: String? = null,
    ) : Engine(), SensorEventListener, LiveWallpaperResourceReporter {

        private val receiptStore by lazy { LiveWallpaperReceiptStore.create(this@ParallaxWallpaperService) }
        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null

        private val bitmapLock = Any()
        private var originalBitmap: Bitmap? = null
        private var backgroundLayer: Bitmap? = null
        private var foregroundLayer: Bitmap? = null
        private var fallbackBitmap: Bitmap? = null
        private var activeSegmenter: SubjectSegmenter? = null

        private var screenWidth = 0
        private var screenHeight = 0

        // Smoothed tilt values (low-pass filtered)
        private var tiltX = 0f
        private var tiltY = 0f

        // Raw sensor gravity for low-pass filter
        private val gravity = FloatArray(3)

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var surfaceAlive = false
        private var segmentGeneration = 0L
        private var frameInterval = LiveWallpaperFrameBudget.NORMAL_FRAME_INTERVAL_MS
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Segmentation result processing is a full-screen per-pixel pass (two bitmap
        // copies + an IntArray loop). It used to run on the main thread (GMS Task's
        // default callback thread), stalling the whole UI process on every surface
        // creation / rotation. All heavy work now happens here; only layer publication
        // and field-bitmap recycling are posted back to the main thread.
        private val segmentExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "aura-parallax-segment").apply { isDaemon = true }
        }

        // Input bitmaps of segmentations that are still running, guarded by bitmapLock.
        // scaleAndSegment must not recycle a fallback that an in-flight segmentation is
        // still reading (AURA-G1-04); the owning callback recycles it once it finishes.
        private val inFlightSegmentInputs = mutableSetOf<Bitmap>()

        private var batteryReceiverRegistered = false
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (
                    intent?.action == Intent.ACTION_BATTERY_CHANGED ||
                    intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
                ) {
                    frameInterval = LiveWallpaperFrameBudget.frameIntervalMs(
                        readLiveWallpaperBatterySnapshot(context ?: this@ParallaxWallpaperService),
                    )
                }
            }
        }
        private val clockOverlayRenderer = WallpaperClockOverlayRenderer()

        // Max parallax offset in pixels
        private val maxOffset = 30f
        // Foreground moves 1.5x the background offset
        private val fgMultiplier = 1.5f

        private var lastDrawReceiptMs = 0L
        private val drawRunner = Runnable { draw() }
        // Every post/removal of drawRunner goes through postDraw/cancelDraw, so
        // this flag cannot drift from what the Handler actually holds.
        private var drawScheduled = false
        private var sensorRegistered = false
        private val mediaLoader = LiveWallpaperMediaLoader("aura-parallax-loader")
        private val colorPublisher = LiveWallpaperColorPublisher()

        private fun getPrefs() = getSharedPreferences(PARALLAX_WALLPAPER_PREFS_NAME, MODE_PRIVATE)
        private fun getImagePath(): String? =
            describedImagePath ?: getPrefs().getString("image_path", null)

        @RequiresApi(36)
        override fun onApplyWallpaper(which: Int): WallpaperDescription {
            val content = auraWallpaperDescriptionContent(source = getImagePath())
            return buildAuraWallpaperDescription(
                id = auraWallpaperDescriptionId(
                    "parallax",
                    AuraWallpaperDescriptionContent(source = getImagePath()),
                ),
                title = getString(R.string.parallax_wallpaper_label),
                description = getString(R.string.parallax_wallpaper_desc),
                content = content,
            )
        }

        private fun loadColorPublicationFromPrefs() {
            val enabled = getPrefs().getBoolean(
                LIVE_WALLPAPER_COLORS_ENABLED_PREF,
                LIVE_WALLPAPER_COLORS_ENABLED_DEFAULT,
            )
            if (colorPublisher.setEnabled(enabled)) notifyWallpaperColorsChanged()
        }

        @RequiresApi(android.os.Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? = colorPublisher.current

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            requestSegmenterModuleInstall()
        }

        /**
         * The Subject Segmentation model ships unbundled via Google Play services.
         * Asking for it once at engine create avoids the first-apply silent failure
         * mode where segmenter.process() returns MlKitException.UNAVAILABLE because
         * the module hasn't been delivered yet.
         */
        private fun requestSegmenterModuleInstall() {
            try {
                // Use a placeholder client to declare the module dependency. We don't
                // care about the install Task's result; downstream segmenter.process
                // already handles the not-yet-installed case by falling back to the
                // single-image path. This is best-effort warm-up only.
                val placeholderClient = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
                )
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(placeholderClient)
                    .build()
                ModuleInstall.getClient(applicationContext)
                    .installModules(request)
                    .addOnCompleteListener {
                        try { placeholderClient.close() } catch (_: Exception) {}
                    }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("ParallaxWP", "Segmenter module install request failed: ${e.message}")
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAlive = true
            registerBatteryReceiver()
            refreshFrameBudget()
            clockOverlayRenderer.refresh(this@ParallaxWallpaperService)
            receiptStore.recordSurfaceCreated(LiveWallpaperReceiptStore.ENGINE_PARALLAX, getImagePath())
            loadImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenWidth = width
            screenHeight = height
            clockOverlayRenderer.refresh(this@ParallaxWallpaperService)
            loadColorPublicationFromPrefs()
            val bmp = synchronized(bitmapLock) { originalBitmap }
            if (bmp != null) scaleAndSegment(bmp)
            else loadImage()
            if (visible) scheduleDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            receiptStore.recordVisibilityChanged(LiveWallpaperReceiptStore.ENGINE_PARALLAX, visible)
            if (visible) {
                refreshFrameBudget()
                clockOverlayRenderer.refresh(this@ParallaxWallpaperService)
                loadColorPublicationFromPrefs()
                registerSensor()
                scheduleDraw()
            } else {
                unregisterSensor()
                cancelDraw()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            surfaceAlive = false
            cancelDraw()
            unregisterSensor()
            unregisterBatteryReceiver()
            // The surface is gone, so nothing can draw these layers or consume
            // this segmenter. Holding them until onDestroy kept a full-screen set
            // of bitmaps plus a native ML Kit client alive across every
            // surface-destroy the platform performs (rotation, unlock, preview
            // teardown), which on a process that lives for days is a real leak.
            releaseSegmenter()
            recycleBitmaps()
            receiptStore.recordSurfaceDestroyed(LiveWallpaperReceiptStore.ENGINE_PARALLAX)
        }

        override fun onDestroy() {
            super.onDestroy()
            destroyed = true
            cancelDraw()
            unregisterSensor()
            unregisterBatteryReceiver()
            segmentExecutor.shutdown()
            mediaLoader.shutdown()
            releaseSegmenter()
            recycleBitmaps()
            colorPublisher.clear()
        }

        // -- Sensor --

        private fun registerSensor() {
            if (sensorRegistered) return
            val sensor = accelerometer ?: return
            val registered = sensorManager
                ?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) == true
            sensorRegistered = registered
        }

        private fun unregisterSensor() {
            if (!sensorRegistered) return
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }

        private fun releaseSegmenter() {
            val segmenter = synchronized(bitmapLock) {
                val current = activeSegmenter
                activeSegmenter = null
                current
            }
            try { segmenter?.close() } catch (_: Exception) {}
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            // Low-pass filter to smooth sensor noise
            val alpha = 0.15f
            gravity[0] = alpha * event.values[0] + (1 - alpha) * gravity[0]
            gravity[1] = alpha * event.values[1] + (1 - alpha) * gravity[1]

            // Normalize: ~9.8 at rest, tilt gives deviation
            // X: left/right tilt, Y: forward/back tilt
            tiltX = (gravity[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            tiltY = ((gravity[1] - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // -- Image Loading --

        private fun loadImage() {
            val path = getImagePath() ?: return
            mediaLoader.request {
                try {
                    val file = java.io.File(path)
                    if (!file.exists()) return@request
                    val (targetWidth, targetHeight) = resolveDecodeTarget()
                    val bmp = BitmapSampling.decodeSampledBitmap(path, targetWidth, targetHeight)
                        ?: return@request
                    // Quantize here, on the loader thread, while this bitmap is still
                    // ours: once it is posted the main thread owns its lifetime. The
                    // publisher keeps the colors, never the bitmap.
                    val colorsChanged = colorPublisher.update(path, bmp)
                    handler.post {
                        // G1-10: if the surface was destroyed while the decode was in
                        // flight, re-populating originalBitmap and starting a fresh
                        // segmentation would resurrect the exact resources
                        // onSurfaceDestroyed just released. Guard on surface liveness.
                        if (destroyed || !surfaceAlive) { bmp.recycle(); return@post }
                        if (colorsChanged) notifyWallpaperColorsChanged()
                        synchronized(bitmapLock) {
                            originalBitmap?.recycle()
                            originalBitmap = bmp
                        }
                        if (screenWidth > 0 && screenHeight > 0) {
                            scaleAndSegment(bmp)
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.e("ParallaxWP", "Load failed: ${e.message}")
                }
            }
        }

        private fun resolveDecodeTarget(): Pair<Int, Int> {
            val padding = (maxOffset * 2).toInt()
            val width = if (screenWidth > 0) screenWidth else resources.displayMetrics.widthPixels
            val height = if (screenHeight > 0) screenHeight else resources.displayMetrics.heightPixels
            return (width + padding).coerceAtLeast(1) to (height + padding).coerceAtLeast(1)
        }

        private fun scaleAndSegment(source: Bitmap) {
            if (destroyed || !surfaceAlive || screenWidth <= 0 || screenHeight <= 0 || source.isRecycled) return

            // Scale with extra padding for parallax movement (add maxOffset on each side)
            val padded = try {
                scaleBitmapCenterCrop(
                    source,
                    screenWidth + (maxOffset * 2).toInt(),
                    screenHeight + (maxOffset * 2).toInt(),
                )
            } catch (t: OutOfMemoryError) {
                // A full-screen scale OOM must not take down the wallpaper process;
                // keep whatever layers/fallback are already present.
                receiptStore.recordError(LiveWallpaperReceiptStore.ENGINE_PARALLAX, "scaleAndSegment OOM")
                return
            }
            val generation = synchronized(bitmapLock) {
                val old = fallbackBitmap
                fallbackBitmap = padded
                // G1-04: the previous fallback may still be the input of an in-flight
                // segmentation — recycling it here would free native memory ML Kit is
                // still reading. Only recycle it when no segmentation owns it; the owning
                // callback recycles it once it finishes.
                if (old != null && old !== padded && old !in inFlightSegmentInputs && !old.isRecycled) {
                    old.recycle()
                }
                segmentGeneration += 1
                segmentGeneration
            }

            // Attempt ML Kit segmentation
            segmentImage(padded, generation)
        }

        private fun segmentImage(bitmap: Bitmap, generation: Long) {
            try {
                synchronized(bitmapLock) { inFlightSegmentInputs.add(bitmap) }
                // Close-and-null BEFORE creating the next segmenter so a lingering
                // success/failure callback from the previous generation can't race us
                // into closing the NEW segmenter mid-flight.
                val previous = activeSegmenter
                activeSegmenter = null
                try { previous?.close() } catch (_: Exception) {}
                // Subject Segmentation returns one foreground-confidence mask (sum of
                // all detected subjects). Per-subject masks are also available but
                // Aura collapses everything in front of the background into a single
                // parallax foreground, matching the previous selfie-segmenter behavior.
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build()
                val segmenter = SubjectSegmentation.getClient(options)
                activeSegmenter = segmenter
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                segmenter.process(inputImage)
                    .addOnSuccessListener(segmentExecutor) { result ->
                        handleSegmentSuccess(segmenter, bitmap, generation, result)
                    }
                    .addOnFailureListener(segmentExecutor) { e ->
                        handleSegmentFailure(segmenter, bitmap, generation, e)
                    }
            } catch (e: Exception) {
                synchronized(bitmapLock) { inFlightSegmentInputs.remove(bitmap) }
                if (BuildConfig.DEBUG) android.util.Log.e("ParallaxWP", "Segmenter init error: ${e.message}")
                receiptStore.recordError(LiveWallpaperReceiptStore.ENGINE_PARALLAX, "segmenter init: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * Runs on [segmentExecutor]. Performs the full-screen mask synthesis off the
         * main thread (AURA-G1-03), then posts the layer swap back to the main thread so
         * the render loop and field-bitmap recycling stay serialized with draw().
         */
        private fun handleSegmentSuccess(
            segmenter: SubjectSegmenter,
            bitmap: Bitmap,
            generation: Long,
            result: SubjectSegmentationResult,
        ) {
            synchronized(bitmapLock) {
                if (activeSegmenter === segmenter) activeSegmenter = null
                inFlightSegmentInputs.remove(bitmap)
            }
            try { segmenter.close() } catch (_: Exception) {}
            if (destroyed || bitmap.isRecycled) return

            var bgBitmap: Bitmap? = null
            var fgBitmap: Bitmap? = null
            var handedOff = false
            try {
                // Extract pixels under lock to prevent race with recycleBitmaps()
                val pixels: IntArray
                val bmpW: Int
                val bmpH: Int
                synchronized(bitmapLock) {
                    // A newer generation took over the fallback slot while we ran; our
                    // input is orphaned. Recycle it and drop our work. Safe on this
                    // thread because it is no longer what draw() reads.
                    if (fallbackBitmap !== bitmap || !surfaceAlive) {
                        recycleInputIfSuperseded(bitmap)
                        return
                    }
                    if (bitmap.isRecycled) return
                    bmpW = bitmap.width
                    bmpH = bitmap.height
                    pixels = IntArray(bmpW * bmpH)
                    bitmap.getPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
                    // bitmap.copy() can return null on low-memory devices; fall back
                    // to a fresh ARGB_8888 allocation populated from the pixel array
                    // we just extracted so we always end up with a usable background.
                    bgBitmap = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: OutOfMemoryError) { null }
                }
                if (bgBitmap == null) {
                    // Reconstruct from the IntArray we have on hand rather than
                    // dropping out — keeps the parallax layered effect even when
                    // the OS short-circuits a direct bitmap.copy.
                    bgBitmap = try {
                        Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            .also { it.setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH) }
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                }

                val floatBuffer = result.foregroundConfidenceMask
                    ?: run {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.w("ParallaxWP", "No foreground mask in result, using fallback")
                        }
                        return
                    }
                floatBuffer.rewind()
                val fgPixels = IntArray(bmpW * bmpH)
                val maskLimit = floatBuffer.limit()

                for (i in 0 until bmpW * bmpH) {
                    val confidence = if (i < maskLimit) floatBuffer.get(i) else 0f
                    if (confidence > 0.5f) {
                        val srcPixel = pixels[i]
                        val a = (confidence * 255f).toInt().coerceIn(0, 255)
                        fgPixels[i] = (a shl 24) or (srcPixel and 0x00FFFFFF)
                    } else {
                        fgPixels[i] = 0
                    }
                }

                val foreground = try {
                    Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    if (BuildConfig.DEBUG) android.util.Log.w("ParallaxWP", "fgBitmap OOM: ${e.message}")
                    return
                }
                foreground.setPixels(fgPixels, 0, bmpW, 0, 0, bmpW, bmpH)
                fgBitmap = foreground
                val layersReady = bgBitmap != null && fgBitmap != null

                // Publish on the main thread: draw() reads these fields, and recycling
                // the retired fallback / old layers off-main would race the render loop.
                val gen = generation
                handler.post {
                    synchronized(bitmapLock) {
                        if (destroyed || !surfaceAlive || gen != segmentGeneration) {
                            // Stale by the time we reached the main thread.
                            bgBitmap?.let { b -> try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
                            fgBitmap?.let { f -> try { if (!f.isRecycled) f.recycle() } catch (_: Throwable) {} }
                            recycleInputIfSuperseded(bitmap)
                            return@post
                        }
                        if (layersReady) {
                            val oldFg = foregroundLayer
                            val oldBg = backgroundLayer
                            foregroundLayer = fgBitmap
                            backgroundLayer = bgBitmap
                            oldFg?.recycle()
                            oldBg?.recycle()
                            // Retire the fallback only if we actually have both layers;
                            // otherwise keep it so draw() has SOMETHING to render.
                            fallbackBitmap?.recycle()
                            fallbackBitmap = null
                        } else {
                            // bgBitmap or fgBitmap is null — keep the fallback.
                            bgBitmap?.let { b -> try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
                            fgBitmap?.let { f -> try { if (!f.isRecycled) f.recycle() } catch (_: Throwable) {} }
                        }
                    }
                }
                handedOff = true

                if (BuildConfig.DEBUG) {
                    android.util.Log.d("ParallaxWP", "Subject segmentation succeeded: ${bmpW}x${bmpH}, mask cap $maskLimit")
                }
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) {
                    receiptStore.recordError(LiveWallpaperReceiptStore.ENGINE_PARALLAX, "segmentation OOM")
                } else if (BuildConfig.DEBUG) {
                    android.util.Log.e("ParallaxWP", "Segment result error: ${t.message}")
                }
            } finally {
                if (!handedOff) {
                    // Nothing we allocated has been handed to the main thread yet.
                    bgBitmap?.let { b -> try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
                    fgBitmap?.let { f -> try { if (!f.isRecycled) f.recycle() } catch (_: Throwable) {} }
                }
            }
        }

        private fun handleSegmentFailure(
            segmenter: SubjectSegmenter,
            bitmap: Bitmap,
            generation: Long,
            e: Exception,
        ) {
            synchronized(bitmapLock) {
                if (activeSegmenter === segmenter) activeSegmenter = null
                inFlightSegmentInputs.remove(bitmap)
            }
            try { segmenter.close() } catch (_: Exception) {}
            if (destroyed) {
                handler.post { recycleInputIfSuperseded(bitmap) }
                return
            }
            if (BuildConfig.DEBUG) android.util.Log.w("ParallaxWP", "Subject segmentation failed, using fallback: ${e.message}")
            receiptStore.recordError(LiveWallpaperReceiptStore.ENGINE_PARALLAX, "segmentation: ${e.javaClass.simpleName}: ${e.message}")
            receiptStore.recordRecovery(LiveWallpaperReceiptStore.ENGINE_PARALLAX, "fallback to single-layer bitmap")
            val gen = generation
            handler.post {
                synchronized(bitmapLock) {
                    if (gen != segmentGeneration || !surfaceAlive) {
                        recycleInputIfSuperseded(bitmap)
                        return@post
                    }
                    val oldBg = backgroundLayer
                    val oldFg = foregroundLayer
                    backgroundLayer = null
                    foregroundLayer = null
                    oldBg?.recycle()
                    oldFg?.recycle()
                }
            }
        }

        // -- Drawing --

        private fun scheduleDraw() {
            cancelDraw()
            if (visible) postDraw(0L)
        }

        /** The single door for posting the render loop, so accounting stays exact. */
        private fun postDraw(delayMs: Long) {
            handler.removeCallbacks(drawRunner)
            handler.postDelayed(drawRunner, delayMs)
            drawScheduled = true
        }

        /** The single door for cancelling the render loop. */
        private fun cancelDraw() {
            handler.removeCallbacks(drawRunner)
            drawScheduled = false
        }

        private fun refreshFrameBudget() {
            frameInterval = LiveWallpaperFrameBudget.frameIntervalMs(
                readLiveWallpaperBatterySnapshot(this@ParallaxWallpaperService),
            )
        }

        private fun registerBatteryReceiver() {
            if (batteryReceiverRegistered) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(batteryReceiver, LiveWallpaperFrameBudget.batteryBroadcastFilter(), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(batteryReceiver, LiveWallpaperFrameBudget.batteryBroadcastFilter())
                }
                batteryReceiverRegistered = true
            } catch (_: Exception) {
            }
        }

        private fun unregisterBatteryReceiver() {
            if (!batteryReceiverRegistered) return
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            } finally {
                batteryReceiverRegistered = false
            }
        }

        /**
         * Recycles a segmentation input bitmap on the main thread when it is no longer
         * the active fallback. Must run on the main thread: draw() may otherwise be
         * mid-drawBitmap on it.
         */
        private fun recycleInputIfSuperseded(bitmap: Bitmap) {
            synchronized(bitmapLock) {
                if (fallbackBitmap !== bitmap) {
                    try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Throwable) {}
                }
            }
        }

        /**
         * Parallax is the heaviest engine: a sensor listener, a render callback,
         * up to four full-screen bitmaps, a native segmentation client, and a
         * decode thread. Each is engine-scoped and must be gone with the surface.
         */
        override fun resourceSnapshot(): LiveWallpaperResourceSnapshot =
            LiveWallpaperResourceSnapshot(
                engine = LiveWallpaperReceiptStore.ENGINE_PARALLAX,
                frameCallbacks = if (drawScheduled) 1 else 0,
                sensorListeners = if (sensorRegistered) 1 else 0,
                imageBuffers = synchronized(bitmapLock) {
                    listOf(originalBitmap, backgroundLayer, foregroundLayer, fallbackBitmap)
                        .count { it != null && !it.isRecycled }
                },
                segmenters = if (activeSegmenter != null) 1 else 0,
                loaderThreads = mediaLoader.outstanding,
            )

        private fun draw() {
            drawScheduled = false
            if (!visible) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)

                    val bgOffsetX = -tiltX * maxOffset
                    val bgOffsetY = -tiltY * maxOffset
                    val fgOffsetX = -tiltX * maxOffset * fgMultiplier
                    val fgOffsetY = -tiltY * maxOffset * fgMultiplier

                    // Center the padded bitmap on screen
                    val baseX = -maxOffset
                    val baseY = -maxOffset

                    val bg: Bitmap?
                    val fg: Bitmap?
                    val fb: Bitmap?
                    synchronized(bitmapLock) {
                        bg = backgroundLayer
                        fg = foregroundLayer
                        fb = fallbackBitmap
                    }

                    if (bg != null && fg != null && !bg.isRecycled && !fg.isRecycled) {
                        // Draw background layer with base offset
                        canvas.drawBitmap(bg, baseX + bgOffsetX, baseY + bgOffsetY, paint)
                        // Draw foreground layer with enhanced offset
                        canvas.drawBitmap(fg, baseX + fgOffsetX, baseY + fgOffsetY, paint)
                    } else if (fb != null && !fb.isRecycled) {
                        // Fallback: single image with slight parallax movement
                        canvas.drawBitmap(fb, baseX + bgOffsetX, baseY + bgOffsetY, paint)
                    }
                    clockOverlayRenderer.draw(this@ParallaxWallpaperService, canvas)
                }
            } catch (_: Exception) {
            } finally {
                canvas?.let {
                    try { holder.unlockCanvasAndPost(it) } catch (_: Exception) {}
                }
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastDrawReceiptMs >= 30_000L) {
                lastDrawReceiptMs = now
                receiptStore.recordDraw(LiveWallpaperReceiptStore.ENGINE_PARALLAX)
            }
            if (visible) {
                postDraw(frameInterval)
            }
        }

        // -- Utilities --

        /**
         * Fill/crop to exactly [targetW]x[targetH] in one pass. The old implementation
         * scaled the WHOLE source into a `src.width*scale × src.height*scale` intermediate
         * bitmap and then cropped it; for a wide source whose short edge was below the
         * viewport that intermediate ballooned to multiples of the screen area. Drawing the
         * computed source rect straight into a target-sized canvas bounds the allocation to
         * the output (AURA-G1-01).
         */
        private fun scaleBitmapCenterCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
            val srcWidth = src.width
            val srcHeight = src.height
            val scale = maxOf(targetW.toFloat() / srcWidth, targetH.toFloat() / srcHeight)
            val srcW = (targetW / scale).coerceAtMost(srcWidth.toFloat())
            val srcH = (targetH / scale).coerceAtMost(srcHeight.toFloat())
            val srcX = ((srcWidth - srcW) / 2f).coerceAtLeast(0f)
            val srcY = ((srcHeight - srcH) / 2f).coerceAtLeast(0f)
            val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(
                src,
                Rect(srcX.toInt(), srcY.toInt(), (srcX + srcW).toInt(), (srcY + srcH).toInt()),
                RectF(0f, 0f, targetW.toFloat(), targetH.toFloat()),
                paint,
            )
            return result
        }

        private fun recycleBitmaps() {
            synchronized(bitmapLock) {
                originalBitmap?.recycle(); originalBitmap = null
                backgroundLayer?.recycle(); backgroundLayer = null
                foregroundLayer?.recycle(); foregroundLayer = null
                fallbackBitmap?.recycle(); fallbackBitmap = null
            }
        }
    }
}
