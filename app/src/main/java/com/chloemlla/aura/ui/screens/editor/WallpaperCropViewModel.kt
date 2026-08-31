package com.chloemlla.aura.ui.screens.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperTarget
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.service.SmartCropCalculator
import com.chloemlla.aura.service.SmartCropDetector
import com.chloemlla.aura.service.WallpaperApplyCoordinator
import com.chloemlla.aura.service.WallpaperApplyPolicy
import com.chloemlla.aura.service.WallpaperApplier
import com.chloemlla.aura.service.advertisedLengthExceeds
import com.chloemlla.aura.service.MediaIngestionImageFlow
import com.chloemlla.aura.service.decodeImageBytesForFlow
import com.chloemlla.aura.service.readStreamCapped
import com.chloemlla.aura.service.ShareOutbox
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

data class CropState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isApplying: Boolean = false,
    val smartCropInProgress: Boolean = false,
    val success: String? = null,
    val error: String? = null,
)

@HiltViewModel
class WallpaperCropViewModel @Inject constructor(
    private val wallpaperApplier: WallpaperApplier,
    private val okHttpClient: OkHttpClient,
    private val smartCropDetector: SmartCropDetector,
    @ApplicationContext private val appContext: Context,
    private val applyCoordinator: WallpaperApplyCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(CropState())
    val state = _state.asStateFlow()
    private var loadedWallpaperKey: String? = null
    private val displacedBitmaps = DisplacedBitmapRecycler()

    /**
     * Identity for the cropped output: the wallpaper the crop was loaded from is
     * what history records, so a crop can be undone like any other apply.
     */
    private var croppedWallpaper: Wallpaper? = null

    fun loadWallpaper(wallpaper: Wallpaper): Boolean {
        croppedWallpaper = wallpaper
        val currentState = _state.value
        val wallpaperKey = wallpaper.stableKey()
        if (loadedWallpaperKey == wallpaperKey && (currentState.bitmap != null || currentState.isLoading)) {
            return true
        }
        loadedWallpaperKey = wallpaperKey
        val url = wallpaper.fullUrl
        val scheme = url.substringBefore(":", "").lowercase(java.util.Locale.ROOT)
        if (scheme == "content" || scheme == "file") {
            loadFromContentUri(Uri.parse(url))
        } else {
            loadFromUrl(url)
        }
        return true
    }

    fun loadFromUrl(url: String) {
        viewModelScope.launch {
            val previous = _state.value.bitmap
            _state.update {
                it.copy(
                    bitmap = null,
                    isLoading = true,
                    scale = 1f,
                    offsetX = 0f,
                    offsetY = 0f,
                    success = null,
                    error = null,
                )
            }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body ?: throw Exception("Empty body")
                        val advertised = body.contentLength()
                        if (advertisedLengthExceeds(advertised, MAX_CROP_BYTES)) {
                            throw Exception("Image too large to crop")
                        }
                        val bytes = readStreamCapped(body.byteStream(), MAX_CROP_BYTES)
                        decodeImageBytesForFlow(
                            bytes = bytes,
                            flow = MediaIngestionImageFlow.EDITOR,
                            declaredMimeType = body.contentType()?.toString(),
                            extension = url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = ""),
                            maxLongEdge = MAX_CROP_LONG_EDGE,
                        )
                    }
                }
                _state.update { it.copy(bitmap = bitmap, isLoading = false) }
                displacedBitmaps.displace(previous, listOf(_state.value.bitmap))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun loadFromContentUri(uri: Uri) {
        viewModelScope.launch {
            val previous = _state.value.bitmap
            _state.update {
                it.copy(
                    bitmap = null,
                    isLoading = true,
                    scale = 1f,
                    offsetX = 0f,
                    offsetY = 0f,
                    success = null,
                    error = null,
                )
            }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw Exception("Could not open image")
                    stream.use {
                        val bytes = readStreamCapped(it, MAX_CROP_BYTES)
                        val bitmap = decodeImageBytesForFlow(
                            bytes = bytes,
                            flow = MediaIngestionImageFlow.EDITOR,
                            declaredMimeType = appContext.contentResolver.getType(uri),
                            extension = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = ""),
                            maxLongEdge = MAX_CROP_LONG_EDGE,
                        )
                        ShareOutbox.deleteExternalMedia(appContext, uri)
                        bitmap
                    }
                }
                _state.update { it.copy(bitmap = bitmap, isLoading = false) }
                displacedBitmaps.displace(previous, listOf(_state.value.bitmap))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setFromBitmap(bitmap: Bitmap) {
        val previous = _state.value.bitmap
        _state.update { it.copy(bitmap = bitmap) }
        displacedBitmaps.displace(previous, listOf(bitmap))
    }

    fun updateTransform(scale: Float, offsetX: Float, offsetY: Float) {
        _state.update { it.copy(scale = scale, offsetX = offsetX, offsetY = offsetY) }
    }

    fun resetTransform() {
        _state.update { it.copy(scale = 1f, offsetX = 0f, offsetY = 0f) }
    }

    fun applyCropped(target: WallpaperTarget, viewportWidth: Int, viewportHeight: Int) {
        val bmp = _state.value.bitmap ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val s = _state.value

        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            var cropped: Bitmap? = null
            try {
                cropped = withContext(Dispatchers.Default) {
                    cropBitmap(bmp, s.scale, s.offsetX, s.offsetY, viewportWidth, viewportHeight)
                }
                // Cropped output goes through the coordinator so it lands in history
                // and can be undone, exactly like an uncropped apply.
                applyCoordinator.apply(
                    wallpaper = croppedWallpaper,
                    target = target,
                    policy = WallpaperApplyPolicy.DERIVED,
                ) { wallpaperApplier.applyFromBitmap(cropped, target) }
                    .onSuccess { receipt ->
                        recycleIfNotShown(cropped)
                        _state.update {
                            it.copy(isApplying = false, success = receipt.feedbackMessage ?: "Applied")
                        }
                    }
                    .onFailure { e ->
                        recycleIfNotShown(cropped)
                        _state.update { it.copy(isApplying = false, error = e.message) }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recycleIfNotShown(cropped)
                _state.update { it.copy(isApplying = false, error = e.message) }
            }
        }
    }

    /**
     * Smart Crop (ROADMAP NX-3) — runs ML Kit Subject Segmentation against the
     * currently-loaded bitmap, computes a centre-on-subject transform, and
     * publishes the result so the composable can sync local gesture state.
     *
     * Returns the new transform on success or null when no subject is detected
     * or segmentation fails. Errors flow through [CropState.error]; UI-visible
     * "no subject" is surfaced as an error message, not as a thrown exception.
     */
    suspend fun applySmartCrop(viewportWidth: Int, viewportHeight: Int): SmartCropCalculator.Transform? {
        val bmp = _state.value.bitmap ?: return null
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        _state.update { it.copy(smartCropInProgress = true) }
        return try {
            val subject = withContext(Dispatchers.Default) {
                smartCropDetector.detectSubject(bmp)
            }
            if (subject == null) {
                _state.update {
                    it.copy(
                        smartCropInProgress = false,
                        error = "Couldn't detect a subject — drag to position manually",
                    )
                }
                return null
            }
            val t = SmartCropCalculator.computeTransform(
                bitmapWidth = bmp.width,
                bitmapHeight = bmp.height,
                subject = subject,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
            // SmartCropCalculator works in an absolute scale (the image fills the
            // viewport at scale = max(vpW/bw, vpH/bh)), while the preview shows the
            // bitmap at fit * scale. Convert back so the applied transform lands on
            // the same pixels the preview displays.
            val fit = minOf(
                viewportWidth / bmp.width.toFloat(),
                viewportHeight / bmp.height.toFloat(),
            )
            val displayScale = t.scale / fit
            val display = SmartCropCalculator.Transform(
                scale = displayScale,
                offsetX = t.offsetX,
                offsetY = t.offsetY,
            )
            _state.update {
                it.copy(
                    smartCropInProgress = false,
                    scale = display.scale,
                    offsetX = display.offsetX,
                    offsetY = display.offsetY,
                    success = "Smart crop applied",
                )
            }
            display
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(smartCropInProgress = false, error = e.message) }
            null
        }
    }

    fun clearMessages() = _state.update { it.copy(success = null, error = null) }

    private fun cropBitmap(
        source: Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Bitmap {
        // The on-screen preview renders the bitmap with ContentScale.Fit before the
        // graphicsLayer scale/translate applies, so a source pixel occupies
        // fit * scale screen pixels — not scale alone. Without this factor every
        // remote/landscape crop that letterboxes would take a different region than
        // the preview shows.
        val fit = minOf(
            viewWidth / source.width.toFloat(),
            viewHeight / source.height.toFloat(),
        )
        val totalScale = fit * scale
        val scaledW = source.width * totalScale
        val scaledH = source.height * totalScale

        val imgLeft = (viewWidth - scaledW) / 2f + offsetX
        val imgTop = (viewHeight - scaledH) / 2f + offsetY

        val visLeft = (0f - imgLeft).coerceAtLeast(0f)
        val visTop = (0f - imgTop).coerceAtLeast(0f)
        val visRight = (viewWidth - imgLeft).coerceAtMost(scaledW)
        val visBottom = (viewHeight - imgTop).coerceAtMost(scaledH)

        val srcLeft = (visLeft / totalScale).toInt().coerceIn(0, source.width - 1)
        val srcTop = (visTop / totalScale).toInt().coerceIn(0, source.height - 1)
        val srcRight = (visRight / totalScale).toInt().coerceIn(srcLeft + 1, source.width)
        val srcBottom = (visBottom / totalScale).toInt().coerceIn(srcTop + 1, source.height)

        val cropWidth = srcRight - srcLeft
        val cropHeight = srcBottom - srcTop
        if (cropWidth < MIN_CROP_SIZE || cropHeight < MIN_CROP_SIZE) {
            throw IllegalArgumentException("Selection is too small")
        }

        return Bitmap.createBitmap(
            source,
            srcLeft,
            srcTop,
            cropWidth,
            cropHeight,
        )
    }

    /**
     * Recycle [bitmap] unless it is the very instance [CropState.bitmap] still
     * points at. [cropBitmap] hands back the source bitmap unchanged when the
     * whole image fits the viewport (immutable source + full cover), and recycling
     * that would tear the next frame out from under the preview's Image.
     */
    private fun recycleIfNotShown(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap === _state.value.bitmap) return
        bitmap.recycle()
    }

    override fun onCleared() {
        // Nothing can draw once the ViewModel is cleared, so the current bitmap is
        // freed immediately along with everything still waiting in the recycler.
        displacedBitmaps.drain(listOf(_state.value.bitmap))
        _state.value.bitmap?.recycle()
        super.onCleared()
    }

    private companion object {
        /** Max bytes accepted when downloading a wallpaper for cropping. */
        private const val MAX_CROP_BYTES = 64L * 1024 * 1024
        private const val MAX_CROP_LONG_EDGE = 4096
        /** Refuse to apply a crop that degenerates to a sub-64px sliver. */
        private const val MIN_CROP_SIZE = 64
    }
}
