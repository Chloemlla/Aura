package com.freevibe.ui.screens.editor

import android.graphics.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.stableKey
import com.freevibe.service.DepthBackgroundStyle
import com.freevibe.service.DepthFrameStyle
import com.freevibe.service.DepthPortraitComposer
import com.freevibe.service.WallpaperEditorMemoryBudget
import com.freevibe.service.DepthPortraitOptions
import com.freevibe.service.WallpaperApplyCoordinator
import com.freevibe.service.WallpaperApplyPolicy
import com.freevibe.service.WallpaperApplier
import com.freevibe.service.advertisedLengthExceeds
import com.freevibe.service.MediaIngestionImageFlow
import com.freevibe.service.decodeImageBytesForFlow
import com.freevibe.service.readStreamCapped
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import javax.inject.Inject

enum class WallpaperOverlayType { TEXT, STICKER }

enum class WallpaperSticker { STAR, HEART, SPARKLE }

data class WallpaperOverlayLayer(
    val id: Long,
    val type: WallpaperOverlayType,
    val text: String = DEFAULT_OVERLAY_TEXT,
    val sticker: WallpaperSticker = WallpaperSticker.STAR,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val color: Int = Color.WHITE,
)

data class EditorState(
    val originalBitmap: Bitmap? = null,
    val editedBitmap: Bitmap? = null,
    val brightness: Float = 0f,       // -100 to 100
    val contrast: Float = 1f,         // 0.5 to 2.0
    val saturation: Float = 1f,       // 0 to 2.0
    val blurRadius: Float = 0f,       // 0 to 25
    val vignette: Float = 0f,         // 0 to 1.0
    val grain: Float = 0f,            // 0 to 1.0
    val amoledCrush: Float = 0f,      // 0 to 1.0 — pushes dark pixels to pure black
    val warmth: Float = 0f,           // -50 to 50 — color temperature shift
    val depthBackgroundStyle: DepthBackgroundStyle = DepthBackgroundStyle.BLUR,
    val depthFrameStyle: DepthFrameStyle = DepthFrameStyle.NONE,
    val depthSubjectScale: Float = 1f,
    val overlayLayers: List<WallpaperOverlayLayer> = emptyList(),
    val selectedOverlayId: Long? = null,
    val canUndoOverlay: Boolean = false,
    val isProcessing: Boolean = false,
    val isApplying: Boolean = false,
    val isLoadingImage: Boolean = false,
    val isDepthProcessing: Boolean = false,
    val isExporting: Boolean = false,
    val isPreparingParallax: Boolean = false,
    val pendingParallaxLaunch: Boolean = false,
    val success: String? = null,
    val error: String? = null,
    val qualityWarning: String? = null,
) {
    /**
     * True once a decoded source exists. Apply, export, and parallax all read the
     * source bitmap, so their controls must wait for this rather than firing into
     * a null bitmap while a URL is still downloading.
     */
    val isSourceReady: Boolean get() = originalBitmap != null
}

private data class FilterRenderResult(
    val bitmap: Bitmap,
    val qualityWarning: String?,
)

@HiltViewModel
class WallpaperEditorViewModel @Inject constructor(
    private val wallpaperApplier: WallpaperApplier,
    private val depthPortraitComposer: DepthPortraitComposer,
    private val okHttpClient: OkHttpClient,
    private val applyCoordinator: WallpaperApplyCoordinator,
) : ViewModel() {

    /**
     * Identity for the edited output. The editor works on a bitmap, so the source
     * wallpaper it was loaded from is what history records; when the editor was
     * opened on a raw bitmap there is nothing to record and history is skipped.
     */
    private var editedWallpaper: Wallpaper? = null

    /** Remembers which wallpaper the current edit session started from. */
    fun setEditedWallpaperIdentity(wallpaper: Wallpaper?) {
        editedWallpaper = wallpaper
    }

    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()
    private var filterJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * Ownership token for the in-flight source load. Cancellation alone is not
     * enough: a load that has already left the cancellable suspension point can
     * still return, and without this an older URL's decoded bitmap would overwrite
     * a newer source the user has since chosen.
     */
    private var loadToken = 0L
    private var loadedWallpaperKey: String? = null
    private var nextOverlayId = 1L
    private val overlayUndoStack = ArrayDeque<List<WallpaperOverlayLayer>>()

    fun loadWallpaper(wallpaper: Wallpaper): Boolean {
        editedWallpaper = wallpaper
        val currentState = _state.value
        val wallpaperKey = wallpaper.stableKey()
        if (loadedWallpaperKey == wallpaperKey && (currentState.originalBitmap != null || currentState.isLoadingImage)) {
            return true
        }
        loadedWallpaperKey = wallpaperKey
        loadFromUrl(wallpaper.fullUrl)
        return true
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun setSourceBitmap(bitmap: Bitmap) {
        overlayUndoStack.clear()
        resetUndoCoalescing()
        _state.update {
            it.copy(
                originalBitmap = bitmap,
                editedBitmap = bitmap,
                overlayLayers = emptyList(),
                selectedOverlayId = null,
                canUndoOverlay = false,
                qualityWarning = null,
            )
        }
        // Filter sliders moved before the source arrived were recorded in state but
        // could not render; replay them now so the preview matches the controls
        // instead of silently showing an unfiltered image.
        applyFilters()
    }

    fun updateBrightness(value: Float) {
        _state.update { it.copy(brightness = value) }
        applyFilters()
    }

    fun updateContrast(value: Float) {
        _state.update { it.copy(contrast = value) }
        applyFilters()
    }

    fun updateSaturation(value: Float) {
        _state.update { it.copy(saturation = value) }
        applyFilters()
    }

    fun updateBlur(value: Float) {
        _state.update { it.copy(blurRadius = value) }
        applyFilters()
    }

    fun updateVignette(value: Float) { _state.update { it.copy(vignette = value) }; applyFilters() }
    fun updateGrain(value: Float) { _state.update { it.copy(grain = value) }; applyFilters() }
    fun updateAmoledCrush(value: Float) { _state.update { it.copy(amoledCrush = value) }; applyFilters() }
    fun updateWarmth(value: Float) { _state.update { it.copy(warmth = value) }; applyFilters() }
    fun updateDepthBackgroundStyle(style: DepthBackgroundStyle) {
        _state.update { it.copy(depthBackgroundStyle = style) }
    }

    fun updateDepthFrameStyle(style: DepthFrameStyle) {
        _state.update { it.copy(depthFrameStyle = style) }
    }

    fun updateDepthSubjectScale(value: Float) {
        _state.update { it.copy(depthSubjectScale = value.coerceIn(0.92f, 1.18f)) }
    }

    fun applyPreset(brightness: Float, contrast: Float, saturation: Float, blur: Float,
                    vignette: Float = 0f, grain: Float = 0f, amoledCrush: Float = 0f, warmth: Float = 0f) {
        _state.update {
            it.copy(brightness = brightness, contrast = contrast, saturation = saturation,
                blurRadius = blur, vignette = vignette, grain = grain, amoledCrush = amoledCrush, warmth = warmth)
        }
        applyFilters()
    }

    override fun onCleared() {
        filterJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }

    fun resetAll() {
        filterJob?.cancel()
        _state.update {
            it.copy(
                editedBitmap = it.originalBitmap,
                // The cancelled filter job can no longer clear this — do it here or the
                // processing overlay stays up forever.
                isProcessing = false,
                brightness = 0f, contrast = 1f, saturation = 1f, blurRadius = 0f,
                vignette = 0f, grain = 0f, amoledCrush = 0f, warmth = 0f,
                depthBackgroundStyle = DepthBackgroundStyle.BLUR,
                depthFrameStyle = DepthFrameStyle.NONE,
                depthSubjectScale = 1f,
                overlayLayers = emptyList(),
                selectedOverlayId = null,
                canUndoOverlay = false,
                isDepthProcessing = false,
                isExporting = false,
                isPreparingParallax = false,
                pendingParallaxLaunch = false,
                qualityWarning = null,
            )
        }
        overlayUndoStack.clear()
        resetUndoCoalescing()
    }

    fun apply(target: WallpaperTarget) {
        val snapshot = _state.value
        if (snapshot.editedBitmap == null && snapshot.originalBitmap == null) return
        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            val bitmap = snapshot.renderBitmapForOutputAsync()
            if (bitmap == null) {
                _state.update { it.copy(isApplying = false) }
                return@launch
            }
            try {
                // Edited output goes through the coordinator so it lands in history
                // and can be undone, exactly like an unedited apply.
                applyCoordinator.apply(
                    wallpaper = editedWallpaper,
                    target = target,
                    policy = WallpaperApplyPolicy.DERIVED,
                ) { wallpaperApplier.applyFromBitmap(bitmap, target) }
                    .onSuccess { receipt ->
                        _state.update {
                            it.copy(isApplying = false, success = receipt.feedbackMessage ?: "Applied")
                        }
                    }
                    .onFailure { e -> _state.update { it.copy(isApplying = false, error = e.message) } }
            } finally {
                recycleRenderedBitmap(bitmap, snapshot)
            }
        }
    }

    fun clearSuccess() = _state.update { it.copy(success = null) }

    fun clearPendingParallaxLaunch() = _state.update { it.copy(pendingParallaxLaunch = false) }

    fun composeDepthPortrait() {
        val source = _state.value.editedBitmap ?: _state.value.originalBitmap ?: return
        val options = _state.value.depthPortraitOptions()
        viewModelScope.launch {
            _state.update { it.copy(isDepthProcessing = true, error = null, success = null) }
            try {
                val result = depthPortraitComposer.compose(source, options)
                _state.update {
                    it.copy(
                        editedBitmap = result.bitmap,
                        isDepthProcessing = false,
                        success = if (result.segmentationApplied) {
                            "Depth portrait ready"
                        } else {
                            "Subject segmentation unavailable; original wallpaper kept."
                        },
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        editedBitmap = source,
                        isDepthProcessing = false,
                        error = e.message ?: "Depth portrait failed",
                    )
                }
            }
        }
    }

    fun exportDepthPortrait() {
        exportCurrentBitmap("Depth portrait saved to Pictures/Aura")
    }

    fun exportEditedWallpaper() {
        exportCurrentBitmap("Edited wallpaper saved to Pictures/Aura")
    }

    private fun exportCurrentBitmap(successMessage: String) {
        val snapshot = _state.value
        if (snapshot.editedBitmap == null && snapshot.originalBitmap == null) return
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true, error = null, success = null) }
            val bitmap = snapshot.renderBitmapForOutputAsync()
            if (bitmap == null) {
                _state.update { it.copy(isExporting = false) }
                return@launch
            }
            try {
                depthPortraitComposer.exportToGallery(bitmap)
                    .onSuccess {
                        _state.update { state ->
                            state.copy(isExporting = false, success = successMessage)
                        }
                    }
                    .onFailure { error ->
                        _state.update { state ->
                            state.copy(isExporting = false, error = error.message ?: "Export failed")
                        }
                    }
            } finally {
                recycleRenderedBitmap(bitmap, snapshot)
            }
        }
    }

    fun prepareDepthParallax() {
        val snapshot = _state.value
        if (snapshot.editedBitmap == null && snapshot.originalBitmap == null) return
        viewModelScope.launch {
            _state.update { it.copy(isPreparingParallax = true, error = null, success = null) }
            val bitmap = snapshot.renderBitmapForOutputAsync()
            if (bitmap == null) {
                _state.update { it.copy(isPreparingParallax = false) }
                return@launch
            }
            try {
                wallpaperApplier.prepareParallaxFromBitmap(bitmap, "parallax_depth_portrait.jpg")
                    .onSuccess {
                        _state.update { state ->
                            state.copy(isPreparingParallax = false, pendingParallaxLaunch = true)
                        }
                    }
                    .onFailure { error ->
                        _state.update { state ->
                            state.copy(isPreparingParallax = false, error = error.message ?: "Parallax setup failed")
                        }
                    }
            } finally {
                recycleRenderedBitmap(bitmap, snapshot)
            }
        }
    }

    fun addTextOverlay(text: String = DEFAULT_OVERLAY_TEXT) {
        val state = _state.value
        saveOverlayUndoSnapshot(state)
        val id = nextOverlayId++
        val layer = WallpaperOverlayLayer(
            id = id,
            type = WallpaperOverlayType.TEXT,
            text = text.ifBlank { DEFAULT_OVERLAY_TEXT }.take(MAX_OVERLAY_TEXT_LENGTH),
            color = Color.WHITE,
        )
        _state.update {
            it.copy(
                overlayLayers = it.overlayLayers + layer,
                selectedOverlayId = id,
                canUndoOverlay = overlayUndoStack.isNotEmpty(),
                success = "Text layer added",
            )
        }
    }

    fun addStickerOverlay(sticker: WallpaperSticker = WallpaperSticker.STAR) {
        val state = _state.value
        saveOverlayUndoSnapshot(state)
        val id = nextOverlayId++
        val layer = WallpaperOverlayLayer(
            id = id,
            type = WallpaperOverlayType.STICKER,
            sticker = sticker,
            color = 0xFFFFD54F.toInt(),
        )
        _state.update {
            it.copy(
                overlayLayers = it.overlayLayers + layer,
                selectedOverlayId = id,
                canUndoOverlay = overlayUndoStack.isNotEmpty(),
                success = "Sticker layer added",
            )
        }
    }

    fun selectOverlay(id: Long?) {
        _state.update { state ->
            state.copy(selectedOverlayId = id?.takeIf { selectedId -> state.overlayLayers.any { it.id == selectedId } })
        }
    }

    fun moveOverlay(id: Long, deltaX: Float, deltaY: Float) {
        updateOverlay(id) { layer ->
            layer.copy(
                x = (layer.x + deltaX).coerceIn(0f, 1f),
                y = (layer.y + deltaY).coerceIn(0f, 1f),
            )
        }
    }

    fun updateSelectedOverlayText(value: String) {
        val id = _state.value.selectedOverlayId ?: return
        updateOverlay(id) { layer ->
            layer.copy(text = value.take(MAX_OVERLAY_TEXT_LENGTH))
        }
    }

    fun updateSelectedOverlayScale(value: Float) {
        val id = _state.value.selectedOverlayId ?: return
        updateOverlay(id) { layer -> layer.copy(scale = value.coerceIn(0.5f, 2.25f)) }
    }

    fun updateSelectedOverlayRotation(value: Float) {
        val id = _state.value.selectedOverlayId ?: return
        updateOverlay(id) { layer -> layer.copy(rotationDegrees = value.coerceIn(-180f, 180f)) }
    }

    fun updateSelectedOverlayColor(color: Int) {
        val id = _state.value.selectedOverlayId ?: return
        updateOverlay(id) { layer -> layer.copy(color = color) }
    }

    fun updateSelectedSticker(sticker: WallpaperSticker) {
        val id = _state.value.selectedOverlayId ?: return
        updateOverlay(id) { layer -> layer.copy(sticker = sticker) }
    }

    fun deleteSelectedOverlay() {
        val state = _state.value
        val selectedId = state.selectedOverlayId ?: return
        if (state.overlayLayers.none { it.id == selectedId }) return
        saveOverlayUndoSnapshot(state)
        val remaining = state.overlayLayers.filterNot { it.id == selectedId }
        _state.update {
            it.copy(
                overlayLayers = remaining,
                selectedOverlayId = remaining.lastOrNull()?.id,
                canUndoOverlay = overlayUndoStack.isNotEmpty(),
            )
        }
    }

    fun undoOverlayEdit() {
        if (overlayUndoStack.isEmpty()) return
        // A new gesture right after an undo must get its own snapshot — never
        // coalesce across an undo boundary.
        resetUndoCoalescing()
        val previous = overlayUndoStack.removeLast()
        val selectedId = _state.value.selectedOverlayId
            ?.takeIf { id -> previous.any { it.id == id } }
            ?: previous.lastOrNull()?.id
        _state.update {
            it.copy(
                overlayLayers = previous,
                selectedOverlayId = selectedId,
                canUndoOverlay = overlayUndoStack.isNotEmpty(),
            )
        }
    }

    private fun loadFromUrl(url: String) {
        loadJob?.cancel()
        val token = ++loadToken
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    originalBitmap = null,
                    editedBitmap = null,
                    brightness = 0f,
                    contrast = 1f,
                    saturation = 1f,
                    blurRadius = 0f,
                    vignette = 0f,
                    grain = 0f,
                    amoledCrush = 0f,
                    warmth = 0f,
                    depthBackgroundStyle = DepthBackgroundStyle.BLUR,
                    depthFrameStyle = DepthFrameStyle.NONE,
                    depthSubjectScale = 1f,
                    overlayLayers = emptyList(),
                    selectedOverlayId = null,
                    canUndoOverlay = false,
                    isLoadingImage = true,
                    isDepthProcessing = false,
                    isExporting = false,
                    isPreparingParallax = false,
                    pendingParallaxLaunch = false,
                    success = null,
                    error = null,
                    qualityWarning = null,
                )
            }
            overlayUndoStack.clear()
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body ?: throw Exception("Empty response body")
                        val advertised = body.contentLength()
                        if (advertisedLengthExceeds(advertised, MAX_EDIT_BYTES)) {
                            throw Exception("Image too large to edit")
                        }
                        val bytes = readStreamCapped(body.byteStream(), MAX_EDIT_BYTES)
                        decodeImageBytesForFlow(
                            bytes = bytes,
                            flow = MediaIngestionImageFlow.EDITOR,
                            declaredMimeType = body.contentType()?.toString(),
                            extension = url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = ""),
                            maxLongEdge = MAX_EDIT_LONG_EDGE,
                        )
                    }
                }
                if (token != loadToken) {
                    // A newer source took ownership while this one decoded. Drop the
                    // result and release its pixels instead of clobbering the new state.
                    bitmap.recycle()
                    return@launch
                }
                setSourceBitmap(bitmap)
                _state.update { it.copy(isLoadingImage = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (token != loadToken) return@launch
                _state.update { it.copy(isLoadingImage = false, error = e.message ?: "Failed to load image") }
            }
        }
    }

    private fun updateOverlay(
        id: Long,
        transform: (WallpaperOverlayLayer) -> WallpaperOverlayLayer,
    ) {
        val state = _state.value
        if (state.overlayLayers.none { it.id == id }) return
        saveOverlayUndoSnapshot(state, coalesceKey = id)
        _state.update {
            it.copy(
                overlayLayers = it.overlayLayers.map { layer ->
                    if (layer.id == id) transform(layer) else layer
                },
                selectedOverlayId = id,
                canUndoOverlay = overlayUndoStack.isNotEmpty(),
            )
        }
    }

    private var lastUndoCoalesceKey: Long? = null
    private var lastUndoSnapshotAtNanos = 0L

    private fun resetUndoCoalescing() {
        lastUndoCoalesceKey = null
        lastUndoSnapshotAtNanos = 0L
    }

    /**
     * Pushes an undo snapshot. Continuous edits (drag move, scale/rotation slider,
     * per-keystroke text) pass the layer id as [coalesceKey] so a burst of updates
     * produces ONE undo step — a single drag emits dozens of events and would
     * otherwise flush the entire [MAX_OVERLAY_UNDO]-deep history.
     */
    private fun saveOverlayUndoSnapshot(state: EditorState, coalesceKey: Long? = null) {
        val now = System.nanoTime()
        if (coalesceKey != null && coalesceKey == lastUndoCoalesceKey &&
            now - lastUndoSnapshotAtNanos < OVERLAY_UNDO_COALESCE_NANOS
        ) {
            // Same gesture burst — keep the window rolling without a new snapshot.
            lastUndoSnapshotAtNanos = now
            return
        }
        lastUndoCoalesceKey = coalesceKey
        lastUndoSnapshotAtNanos = now
        if (overlayUndoStack.size >= MAX_OVERLAY_UNDO) {
            overlayUndoStack.removeFirst()
        }
        overlayUndoStack.addLast(state.overlayLayers)
    }

    private fun applyFilters() {
        val original = _state.value.originalBitmap ?: return
        val s = _state.value
        if (s.brightness == 0f && s.contrast == 1f && s.saturation == 1f &&
            s.blurRadius == 0f && s.vignette == 0f && s.grain == 0f &&
            s.amoledCrush == 0f && s.warmth == 0f) {
            // Cancel any in-flight render: a slider released back at its default must
            // not be overwritten moments later by the previous filtered frame, and the
            // dead job can no longer clear isProcessing.
            filterJob?.cancel()
            _state.update { it.copy(editedBitmap = original, isProcessing = false, qualityWarning = null) }
            return
        }
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            _state.update { it.copy(isProcessing = true) }
            val result = withContext(Dispatchers.Default) {
                val matrixResult = applyColorMatrix(original, s.brightness, s.contrast, s.saturation, s.warmth)
                var bmp = matrixResult.bitmap
                if (s.blurRadius > 0.5f) {
                    val prev = bmp
                    bmp = stackBlur(bmp, s.blurRadius.toInt().coerceIn(1, 25))
                    if (prev !== original && prev !== bmp) prev.recycle()
                }
                if (s.amoledCrush > 0.01f) {
                    val prev = bmp
                    bmp = applyAmoledCrush(bmp, s.amoledCrush)
                    if (prev !== original && prev !== bmp) prev.recycle()
                }
                if (s.vignette > 0.01f) {
                    val prev = bmp
                    bmp = applyVignette(bmp, s.vignette)
                    if (prev !== original && prev !== bmp) prev.recycle()
                }
                if (s.grain > 0.01f) {
                    val prev = bmp
                    bmp = applyGrain(bmp, s.grain)
                    if (prev !== original && prev !== bmp) prev.recycle()
                }
                FilterRenderResult(bmp, matrixResult.qualityWarning)
            }
            _state.update {
                it.copy(
                    editedBitmap = result.bitmap,
                    isProcessing = false,
                    qualityWarning = result.qualityWarning,
                )
            }
        }
    }

    private fun applyColorMatrix(
        src: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float = 0f,
    ): FilterRenderResult {
        val result = try {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            val scale = 0.5f
            Bitmap.createBitmap(
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        }
        val qualityWarning = wallpaperEditorDownscaleWarning(
            sourceWidth = src.width,
            sourceHeight = src.height,
            renderedWidth = result.width,
            renderedHeight = result.height,
        )
        val canvas = Canvas(result)
        val paint = Paint()

        val brightnessMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1f, 0f, 0f, 0f, brightness,
                0f, 1f, 0f, 0f, brightness,
                0f, 0f, 1f, 0f, brightness,
                0f, 0f, 0f, 1f, 0f,
            ))
        }

        val t = (1f - contrast) / 2f * 255f
        val contrastMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ))
        }

        val saturationMatrix = ColorMatrix().apply {
            setSaturation(saturation)
        }

        val warmthMatrix = ColorMatrix().apply {
            if (warmth != 0f) {
                val r = warmth.coerceIn(-50f, 50f)
                set(floatArrayOf(
                    1f, 0f, 0f, 0f, r,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, -r,
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
        }

        val combined = ColorMatrix()
        combined.postConcat(brightnessMatrix)
        combined.postConcat(contrastMatrix)
        combined.postConcat(saturationMatrix)
        if (warmth != 0f) combined.postConcat(warmthMatrix)

        paint.colorFilter = ColorMatrixColorFilter(combined)
        if (result.width != src.width || result.height != src.height) {
            val srcRect = android.graphics.Rect(0, 0, src.width, src.height)
            val dstRect = android.graphics.RectF(0f, 0f, result.width.toFloat(), result.height.toFloat())
            canvas.drawBitmap(src, srcRect, dstRect, paint)
        } else {
            canvas.drawBitmap(src, 0f, 0f, paint)
        }

        return FilterRenderResult(result, qualityWarning)
    }

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val scale = 1f / (1 + radius * 0.15f)
        val smallW = (src.width * scale).toInt().coerceAtLeast(1)
        val smallH = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== result) small.recycle()
        return result
    }

    private fun applyAmoledCrush(src: Bitmap, intensity: Float): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        val threshold = (intensity * 80).toInt()
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            if (lum < threshold) {
                val factor = lum.toFloat() / threshold.coerceAtLeast(1)
                val crush = factor * factor
                pixels[i] = (c and 0xFF000000.toInt()) or
                    (((r * crush).toInt().coerceIn(0, 255)) shl 16) or
                    (((g * crush).toInt().coerceIn(0, 255)) shl 8) or
                    ((b * crush).toInt().coerceIn(0, 255))
            }
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    private fun applyVignette(src: Bitmap, intensity: Float): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val cx = src.width / 2f
        val cy = src.height / 2f
        val radius = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        val colors = intArrayOf(0x00000000, 0x00000000, android.graphics.Color.argb((intensity * 220).toInt(), 0, 0, 0))
        val stops = floatArrayOf(0f, 0.4f, 1f)
        val gradient = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
        val paint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
        return result
    }

    private fun applyGrain(src: Bitmap, intensity: Float): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        val strength = (intensity * 40).toInt()
        val random = java.util.Random(42)
        for (i in pixels.indices) {
            val noise = random.nextInt(strength * 2 + 1) - strength
            val c = pixels[i]
            val r = ((c shr 16 and 0xFF) + noise).coerceIn(0, 255)
            val g = ((c shr 8 and 0xFF) + noise).coerceIn(0, 255)
            val b = ((c and 0xFF) + noise).coerceIn(0, 255)
            pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    private companion object {
        /** Max bytes accepted when downloading a wallpaper for editing. */
        private const val MAX_EDIT_BYTES = 64L * 1024 * 1024

        /**
         * Owned by [WallpaperEditorMemoryBudget] so the arithmetic that decides
         * whether this fits under Android 17's per-app memory limiter moves with
         * it. Raising it here alone would not be possible.
         */
        private const val MAX_EDIT_LONG_EDGE = WallpaperEditorMemoryBudget.MAX_EDIT_LONG_EDGE
        private const val MAX_OVERLAY_UNDO = 20
        private const val MAX_OVERLAY_TEXT_LENGTH = 48
        private const val OVERLAY_UNDO_COALESCE_NANOS = 1_000_000_000L // 1s gesture window
    }
}

private const val DEFAULT_OVERLAY_TEXT = "Aura"

private fun EditorState.depthPortraitOptions(): DepthPortraitOptions =
    DepthPortraitOptions(
        backgroundStyle = depthBackgroundStyle,
        frameStyle = depthFrameStyle,
        subjectScale = depthSubjectScale,
    )

private fun EditorState.renderBitmapForOutput(): Bitmap? {
    val base = editedBitmap ?: originalBitmap ?: return null
    if (overlayLayers.isEmpty()) return base
    return renderWallpaperOverlays(base, overlayLayers)
}

private suspend fun EditorState.renderBitmapForOutputAsync(): Bitmap? =
    if (overlayLayers.isEmpty()) {
        renderBitmapForOutput()
    } else {
        withContext(Dispatchers.Default) { renderBitmapForOutput() }
    }

private fun recycleRenderedBitmap(bitmap: Bitmap, snapshot: EditorState) {
    if (snapshot.overlayLayers.isNotEmpty() &&
        bitmap !== snapshot.editedBitmap &&
        bitmap !== snapshot.originalBitmap &&
        !bitmap.isRecycled
    ) {
        bitmap.recycle()
    }
}

internal fun renderWallpaperOverlays(
    base: Bitmap,
    layers: List<WallpaperOverlayLayer>,
): Bitmap {
    val result = base.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(
        base.width,
        base.height,
        Bitmap.Config.ARGB_8888,
    ).also { fallback ->
        Canvas(fallback).drawBitmap(base, 0f, 0f, null)
    }
    val canvas = Canvas(result)
    layers.forEach { layer ->
        canvas.save()
        canvas.translate(layer.x.coerceIn(0f, 1f) * result.width, layer.y.coerceIn(0f, 1f) * result.height)
        canvas.rotate(layer.rotationDegrees)
        when (layer.type) {
            WallpaperOverlayType.TEXT -> drawTextOverlay(canvas, result, layer)
            WallpaperOverlayType.STICKER -> drawStickerOverlay(canvas, result, layer)
        }
        canvas.restore()
    }
    return result
}

private fun drawTextOverlay(canvas: Canvas, bitmap: Bitmap, layer: WallpaperOverlayLayer) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = layer.color
        textAlign = Paint.Align.CENTER
        textSize = (bitmap.width * 0.09f * layer.scale).coerceAtLeast(18f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.argb(180, 0, 0, 0))
    }
    val metrics = paint.fontMetrics
    val baseline = -(metrics.ascent + metrics.descent) / 2f
    canvas.drawText(layer.text.ifBlank { DEFAULT_OVERLAY_TEXT }, 0f, baseline, paint)
}

private fun drawStickerOverlay(canvas: Canvas, bitmap: Bitmap, layer: WallpaperOverlayLayer) {
    val size = (min(bitmap.width, bitmap.height) * 0.16f * layer.scale).coerceAtLeast(32f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = layer.color
        style = Paint.Style.FILL
        setShadowLayer(size * 0.08f, 0f, size * 0.04f, Color.argb(150, 0, 0, 0))
    }
    val path = when (layer.sticker) {
        WallpaperSticker.STAR -> starPath(size)
        WallpaperSticker.HEART -> heartPath(size)
        WallpaperSticker.SPARKLE -> sparklePath(size)
    }
    canvas.drawPath(path, paint)
}

private fun starPath(size: Float): Path {
    val path = Path()
    val outer = size / 2f
    val inner = outer * 0.45f
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = -PI / 2.0 + i * PI / 5.0
        val x = (cos(angle) * radius).toFloat()
        val y = (sin(angle) * radius).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun heartPath(size: Float): Path {
    val half = size / 2f
    return Path().apply {
        moveTo(0f, half * 0.72f)
        cubicTo(-half, -half * 0.05f, -half * 0.82f, -half * 0.76f, 0f, -half * 0.32f)
        cubicTo(half * 0.82f, -half * 0.76f, half, -half * 0.05f, 0f, half * 0.72f)
        close()
    }
}

private fun sparklePath(size: Float): Path {
    val half = size / 2f
    val arm = half * 0.32f
    return Path().apply {
        moveTo(0f, -half)
        lineTo(arm, -arm)
        lineTo(half, 0f)
        lineTo(arm, arm)
        lineTo(0f, half)
        lineTo(-arm, arm)
        lineTo(-half, 0f)
        lineTo(-arm, -arm)
        close()
    }
}

internal fun wallpaperEditorDownscaleWarning(
    sourceWidth: Int,
    sourceHeight: Int,
    renderedWidth: Int,
    renderedHeight: Int,
): String? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || renderedWidth <= 0 || renderedHeight <= 0) return null
    if (renderedWidth >= sourceWidth && renderedHeight >= sourceHeight) return null
    val widthRatio = renderedWidth.toFloat() / sourceWidth.toFloat()
    val heightRatio = renderedHeight.toFloat() / sourceHeight.toFloat()
    val percent = (minOf(widthRatio, heightRatio) * 100f).roundToInt().coerceIn(1, 99)
    return "Memory was tight, so this edit is rendered at about $percent% resolution. It can still be applied, but a smaller source image will preserve full detail."
}
