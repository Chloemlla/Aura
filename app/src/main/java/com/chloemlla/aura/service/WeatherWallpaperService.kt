package com.chloemlla.aura.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.chloemlla.aura.data.remote.weather.WeatherEffect

/**
 * Live wallpaper service that renders a static wallpaper image with
 * weather particle effects overlay (rain, snow, fog, stars).
 * Pauses rendering when not visible to save battery.
 * Targets 30 FPS for particle updates.
 */
class WeatherWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine(), LiveWallpaperResourceReporter {
        private val receiptStore by lazy { LiveWallpaperReceiptStore.create(this@WeatherWallpaperService) }
        private var renderer: WeatherParticleRenderer? = null
        private var vfxRenderer: VfxParticleRenderer? = null
        private var touchRenderer: TouchEffectRenderer? = null
        private val shaderRenderer = AgslShaderBackgroundRenderer()
        private var shaderPreset: AgslShaderPreset? = null
        private var wallpaperBitmap: Bitmap? = null
        private var scaledBitmap: Bitmap? = null
        private val bitmapLock = Any()
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        @Volatile private var destroyed = false
        private val frameInterval = 33L // ~30 FPS

        private var reducedMotion = false

        // Adaptive tint state
        private var dimEnabled = false
        private var tintEnabled = false
        private var tintIntensity = 0.3f
        private var tintLat = 0.0
        private var tintLon = 0.0
        private var tintLocationPresent = false

        // Cached tint paint. Rebuilding ColorMatrix + Paint every frame at 30 FPS was
        // ~30 allocations/sec under steady-state — we now rebuild only when the input
        // hour rounds to a different 5-minute bucket. Reset to null whenever any input
        // changes (intensity, location, enable flag).
        private var tintPaint: Paint? = null
        private var tintPaintBucket: Int = Int.MIN_VALUE

        private var lastDrawReceiptMs = 0L
        private val drawRunner = Runnable { draw() }
        // Every post/removal of drawRunner goes through postDraw/cancelDraw, so
        // this flag cannot drift from what the Handler actually holds.
        private var drawScheduled = false
        private val mediaLoader = LiveWallpaperMediaLoader("aura-weather-loader")
        private fun weatherPrefs() = getSharedPreferences("freevibe_weather_wp", MODE_PRIVATE)
        private val dimming = LiveWallpaperDimming(
            onRevealChanged = { if (visible) postDraw(0L) },
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // MUST be here, not in onSurfaceCreated: on Android 16 the framework's
            // setTouchEventsEnabled() re-runs updateSurface(), which re-dispatches
            // onSurfaceCreated — infinite recursion -> StackOverflowError (observed
            // as a wallpaper-process crash after reboot on SDK 36).
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadShaderPresetFromPrefs()
            receiptStore.recordSurfaceCreated(LiveWallpaperReceiptStore.ENGINE_WEATHER, currentWallpaperLocator())
            loadWallpaperBitmap()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer = WeatherParticleRenderer(width, height)
            vfxRenderer = VfxParticleRenderer(width, height)
            touchRenderer = TouchEffectRenderer(width, height)
            synchronized(bitmapLock) {
                val oldScaled = scaledBitmap
                scaledBitmap = wallpaperBitmap?.let { scaleBitmap(it, width, height) }
                if (oldScaled !== wallpaperBitmap && oldScaled !== scaledBitmap) oldScaled?.recycle()
            }
            loadReducedMotionFromPrefs()
            loadWeatherFromPrefs()
            loadVfxFromPrefs()
            loadTouchEffectsFromPrefs()
            loadShaderPresetFromPrefs()
            loadAdaptiveTintFromPrefs()
            loadDimmingFromPrefs()
            if (visible) scheduleDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            receiptStore.recordVisibilityChanged(LiveWallpaperReceiptStore.ENGINE_WEATHER, visible)
            if (visible) {
                loadReducedMotionFromPrefs()
                loadWeatherFromPrefs()
                loadVfxFromPrefs()
                loadTouchEffectsFromPrefs()
                loadShaderPresetFromPrefs()
                loadAdaptiveTintFromPrefs()
                loadDimmingFromPrefs()
                scheduleDraw()
            } else {
                cancelDraw()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (dimEnabled) dimming.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                    touchRenderer?.onTouch(event.getX(pointerIndex), event.getY(pointerIndex))
                    if (visible) scheduleDraw()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            cancelDraw()
            receiptStore.recordSurfaceDestroyed(LiveWallpaperReceiptStore.ENGINE_WEATHER)
        }

        override fun onDestroy() {
            super.onDestroy()
            destroyed = true
            cancelDraw()
            mediaLoader.shutdown()
            synchronized(bitmapLock) {
                if (scaledBitmap !== wallpaperBitmap) scaledBitmap?.recycle()
                wallpaperBitmap?.recycle()
                scaledBitmap = null
                wallpaperBitmap = null
            }
            shaderRenderer.clear()
        }

        private fun loadWallpaperBitmap() {
            val prefs = weatherPrefs()
            val path = prefs.getString("wallpaper_path", null) ?: return
            mediaLoader.request {
                try {
                    val file = java.io.File(path)
                    if (!file.exists()) return@request
                    val (targetWidth, targetHeight) = resolveDecodeTarget()
                    val bmp = BitmapSampling.decodeSampledBitmap(path, targetWidth, targetHeight)
                        ?: return@request
                    handler.post {
                        if (destroyed) { bmp.recycle(); return@post }
                        synchronized(bitmapLock) {
                            val oldWallpaper = wallpaperBitmap
                            val oldScaled = scaledBitmap
                            wallpaperBitmap = bmp
                            // Re-scale if surface dimensions are known
                            val holder = surfaceHolder
                            val rect = holder.surfaceFrame
                            if (rect.width() > 0 && rect.height() > 0) {
                                scaledBitmap = scaleBitmap(bmp, rect.width(), rect.height())
                            } else {
                                scaledBitmap = null
                            }
                            // Recycle old bitmaps — check identity to avoid double-recycle
                            if (oldScaled != null && oldScaled !== oldWallpaper && oldScaled !== scaledBitmap) {
                                oldScaled.recycle()
                            }
                            if (oldWallpaper != null && oldWallpaper !== bmp) {
                                oldWallpaper.recycle()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        private fun resolveDecodeTarget(): Pair<Int, Int> {
            val rect = surfaceHolder.surfaceFrame
            val width = if (rect.width() > 0) rect.width() else resources.displayMetrics.widthPixels
            val height = if (rect.height() > 0) rect.height() else resources.displayMetrics.heightPixels
            return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        }

        private fun loadReducedMotionFromPrefs() {
            val prefs = weatherPrefs()
            val manualReduce = prefs.getBoolean("reduce_animations", false)
            val systemDisabled = Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
            reducedMotion = manualReduce || systemDisabled
        }

        private fun loadVfxFromPrefs() {
            val prefs = weatherPrefs()
            val effectName = prefs.getString("vfx_effect", "NONE") ?: "NONE"
            try {
                vfxRenderer?.setEffect(VfxParticleRenderer.VfxEffect.valueOf(effectName))
            } catch (_: Exception) {
                vfxRenderer?.setEffect(VfxParticleRenderer.VfxEffect.NONE)
            }
        }

        private fun loadTouchEffectsFromPrefs() {
            val prefs = weatherPrefs()
            touchRenderer?.setStrength(parseTouchEffectStrength(prefs.getString("touch_effect_strength", "OFF")))
        }

        private fun loadShaderPresetFromPrefs() {
            val presetId = weatherPrefs().getString(
                LIVE_WALLPAPER_SHADER_PRESET_PREF,
                AgslShaderGallery.NONE_ID,
            )
            val nextPreset = AgslShaderGallery.find(AgslShaderGallery.sanitizeId(presetId))
            if (nextPreset?.id != shaderPreset?.id) {
                shaderPreset = nextPreset
                shaderRenderer.clear()
            }
        }

        private fun currentWallpaperLocator(): String? =
            shaderPreset?.let { "shader:${it.id}" }
                ?: weatherPrefs().getString("wallpaper_path", null)

        private fun loadWeatherFromPrefs() {
            val prefs = weatherPrefs()
            val effectName = prefs.getString("weather_effect", "CLEAR_DAY") ?: "CLEAR_DAY"
            val wind = prefs.getFloat("wind_speed", 0f).toDouble()
            try {
                renderer?.setWeather(WeatherEffect.valueOf(effectName), wind)
            } catch (_: Exception) {
                renderer?.setWeather(WeatherEffect.CLEAR_DAY)
            }
        }

        private fun loadDimmingFromPrefs() {
            dimEnabled = weatherPrefs().getBoolean("live_wallpaper_dim_enabled", false)
        }

        private fun loadAdaptiveTintFromPrefs() {
            val prefs = weatherPrefs()
            tintEnabled = prefs.getBoolean("adaptive_tint_enabled", false)
            tintIntensity = prefs.getFloat("adaptive_tint_intensity", 0.3f)
            // Prefer the Float-precision keys (current schema). Fall back to the legacy
            // Long-truncated keys only when a fresh weather update hasn't run yet so
            // existing users don't lose tinting between an upgrade and the next 30-min
            // worker tick. location_present is the canonical "we have coordinates" flag.
            tintLocationPresent = prefs.getBoolean("location_present", false)
            if (tintLocationPresent) {
                tintLat = prefs.getFloat("location_lat", 0f).toDouble()
                tintLon = prefs.getFloat("location_lon", 0f).toDouble()
            } else {
                // Legacy fallback for users who upgraded mid-cycle: pre-Float-schema
                // Long coords. Mark the location present when both exist — otherwise
                // currentTintPaint() bails on !tintLocationPresent and the fallback
                // values are never consumed (tint silently off until the next worker
                // tick rewrites the Float keys).
                val legacyLat = runCatching { prefs.getLong("location_lat", Long.MIN_VALUE) }
                    .getOrDefault(Long.MIN_VALUE)
                val legacyLon = runCatching { prefs.getLong("location_lon", Long.MIN_VALUE) }
                    .getOrDefault(Long.MIN_VALUE)
                if (legacyLat != Long.MIN_VALUE && legacyLon != Long.MIN_VALUE) {
                    tintLat = legacyLat.toDouble()
                    tintLon = legacyLon.toDouble()
                    tintLocationPresent = true
                } else {
                    tintLat = 0.0
                    tintLon = 0.0
                }
            }
            // Invalidate the cached tint paint — any of these inputs may have changed.
            tintPaint = null
            tintPaintBucket = Int.MIN_VALUE
        }

        /**
         * Returns a cached [Paint] with the current adaptive-tint ColorMatrix applied,
         * or null if tinting is disabled / no location is present. The Paint is rebuilt
         * only when the current local hour crosses a 5-minute bucket, so the per-frame
         * cost at 30 FPS is a couple of double comparisons instead of two new objects.
         */
        private fun currentTintPaint(): Paint? {
            if (!tintEnabled || !tintLocationPresent) return null
            val hour = SolarCalculator.currentHour()
            // 5-min buckets: 12 per hour, 288 per day. The tintOffsets curve has steps
            // every ~30 min so 5-min granularity is more than smooth enough.
            val bucket = (hour * 12.0).toInt()
            val cached = tintPaint
            if (cached != null && bucket == tintPaintBucket) return cached
            val sunTimes = SolarCalculator.sunTimes(tintLat, tintLon)
            val offsets = SolarCalculator.tintOffsets(hour, sunTimes, tintIntensity)
            if (offsets[0] == 0f && offsets[1] == 0f && offsets[2] == 0f) {
                // Neutral midday — skip the ColorMatrix entirely so the bitmap draws
                // with the same fast path as when tint is disabled.
                tintPaint = null
                tintPaintBucket = bucket
                return null
            }
            val matrix = ColorMatrix().apply {
                set(floatArrayOf(
                    1f, 0f, 0f, 0f, offsets[0],
                    0f, 1f, 0f, 0f, offsets[1],
                    0f, 0f, 1f, 0f, offsets[2],
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            tintPaint = paint
            tintPaintBucket = bucket
            return paint
        }

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

        /**
         * Weather holds a render callback, two decoded bitmaps, and a decode
         * thread while it loads. The surface can be destroyed mid-decode, so the
         * loader count is what proves the engine is not accumulating threads
         * across repeated create/destroy cycles.
         */
        override fun resourceSnapshot(): LiveWallpaperResourceSnapshot =
            LiveWallpaperResourceSnapshot(
                engine = LiveWallpaperReceiptStore.ENGINE_WEATHER,
                frameCallbacks = if (drawScheduled) 1 else 0,
                imageBuffers = synchronized(bitmapLock) {
                    val scaled = scaledBitmap
                    (if (wallpaperBitmap != null) 1 else 0) +
                        (if (scaled != null && scaled !== wallpaperBitmap) 1 else 0)
                },
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
                    drawBaseBackground(canvas)

                    if (!reducedMotion) {
                        renderer?.update()
                        renderer?.draw(canvas)

                        vfxRenderer?.update()
                        vfxRenderer?.draw(canvas)

                        touchRenderer?.update()
                        touchRenderer?.draw(canvas)
                    }

                    if (dimEnabled) {
                        dimming.tick()
                        dimming.drawDimOverlay(canvas, canvas.width, canvas.height)
                    }
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
                receiptStore.recordDraw(LiveWallpaperReceiptStore.ENGINE_WEATHER)
            }
            if (visible && !reducedMotion) {
                postDraw(frameInterval)
            } else if (visible && dimEnabled && dimming.isRevealing) {
                // Reduced-motion mode has no self-rescheduling frame loop, so the
                // re-dim frame at reveal expiry must be scheduled here, AFTER the
                // touch handler's scheduleDraw() (which removes pending callbacks)
                // has already run — scheduling it from onTouchEvent gets cancelled.
                postDraw(LiveWallpaperDimming.REVEAL_DURATION_MS + 50L)
            }
        }

        private fun drawBaseBackground(canvas: Canvas) {
            val preset = shaderPreset
            if (preset != null) {
                shaderRenderer.draw(canvas, preset)
                return
            }
            val bmp = synchronized(bitmapLock) { scaledBitmap }
            if (bmp != null && !bmp.isRecycled) {
                val paint = currentTintPaint()
                canvas.drawBitmap(bmp, 0f, 0f, paint)
            } else {
                canvas.drawColor(android.graphics.Color.BLACK)
            }
        }

        private fun scaleBitmap(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
            val scaleX = targetW.toFloat() / src.width
            val scaleY = targetH.toFloat() / src.height
            val scale = maxOf(scaleX, scaleY) // Fill (crop to fit)
            val matrix = Matrix().apply { setScale(scale, scale) }
            val scaled = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            // Center crop
            val x = ((scaled.width - targetW) / 2).coerceAtLeast(0)
            val y = ((scaled.height - targetH) / 2).coerceAtLeast(0)
            val cropW = targetW.coerceAtMost(scaled.width - x).coerceAtLeast(1)
            val cropH = targetH.coerceAtMost(scaled.height - y).coerceAtLeast(1)
            return if (x > 0 || y > 0) {
                // If Bitmap.createBitmap throws (OOM / invalid rect), recycle `scaled` so we
                // don't leak its native allocation — mirrors the fix in ParallaxWallpaperService.
                val cropped = try {
                    Bitmap.createBitmap(scaled, x, y, cropW, cropH)
                } catch (t: Throwable) {
                    if (scaled !== src) try { scaled.recycle() } catch (_: Throwable) {}
                    throw t
                }
                if (cropped !== scaled && scaled !== src) {
                    try { scaled.recycle() } catch (_: Throwable) {}
                }
                cropped
            } else scaled
        }
    }
}
