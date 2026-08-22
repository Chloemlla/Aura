package com.freevibe.ui.screens.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freevibe.R
import com.freevibe.data.model.ContentType
import com.freevibe.data.model.Sound
import com.freevibe.data.model.SoundAction
import com.freevibe.data.model.SoundActionDecision
import com.freevibe.data.model.soundLicenseCapabilities
import com.freevibe.data.model.stableKey
import com.freevibe.service.AudioExportFormat
import com.freevibe.service.AudioFadeCurve
import com.freevibe.service.AudioTrimmer
import com.freevibe.service.MediaIngestionLimitExceeded
import com.freevibe.service.MediaFamily
import com.freevibe.service.SoundUrlResolver
import com.freevibe.service.SoundApplier
import com.freevibe.service.copyStreamCapped
import com.freevibe.service.isLosslessCutAllowed
import com.freevibe.service.losslessCutExportFormat
import com.freevibe.service.normalizeMediaFileName
import com.freevibe.service.requireSniffedMediaFile
import com.freevibe.service.ShareOutbox
import com.freevibe.service.speedAdjustedDurationMs
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class SoundEditorState(
    val isLoading: Boolean = false,
    val waveform: FloatArray = floatArrayOf(),
    val durationMs: Long = 0,
    val frameDurationMs: Long = 1,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val playbackPosition: Float = 0f,
    val isPlaying: Boolean = false,
    val isApplying: Boolean = false,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val fadeCurve: AudioFadeCurve = AudioFadeCurve.LINEAR,
    val playbackSpeed: Float = 1f,
    val losslessCut: Boolean = false,
    val exportFormat: AudioExportFormat = AudioExportFormat.M4A,
    val exportBitrateKbps: Int? = AudioExportFormat.M4A.defaultBitrateKbps,
    val waveformZoom: Float = 1f,
    val waveformViewportStart: Float = 0f,
    val fileName: String = "",
    val localFilePath: String? = null,
    val isLocalFile: Boolean = false,
    val success: String? = null,
    val error: String? = null,
) {
    val trimStartFraction: Float get() = if (durationMs > 0L) trimStartMs.toFloat() / durationMs else 0f
    val trimEndFraction: Float get() = if (durationMs > 0L) trimEndMs.toFloat() / durationMs else 1f
    val trimDurationMs: Long get() = trimEndMs - trimStartMs
    val processedDurationMs: Long get() = speedAdjustedDurationMs(trimDurationMs, playbackSpeed)
    val maximumFadeMs: Long get() = (processedDurationMs / 2L).coerceAtLeast(1L)
    val canUseLosslessCut: Boolean
        get() = isLosslessCutAllowed(fadeInMs, fadeOutMs, playbackSpeed) &&
            losslessCutExportFormat(localFilePath) != null
    val effectiveExportFormat: AudioExportFormat
        get() = if (losslessCut) losslessCutExportFormat(localFilePath) ?: exportFormat else exportFormat

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoundEditorState) return false
        return isLoading == other.isLoading && waveform.contentEquals(other.waveform) &&
            durationMs == other.durationMs && frameDurationMs == other.frameDurationMs &&
            trimStartMs == other.trimStartMs && trimEndMs == other.trimEndMs &&
            playbackPosition == other.playbackPosition &&
            isPlaying == other.isPlaying && isApplying == other.isApplying &&
            fadeInMs == other.fadeInMs && fadeOutMs == other.fadeOutMs &&
            fadeCurve == other.fadeCurve && playbackSpeed == other.playbackSpeed &&
            losslessCut == other.losslessCut && exportFormat == other.exportFormat &&
            exportBitrateKbps == other.exportBitrateKbps && waveformZoom == other.waveformZoom &&
            waveformViewportStart == other.waveformViewportStart &&
            fileName == other.fileName && localFilePath == other.localFilePath &&
            isLocalFile == other.isLocalFile && success == other.success && error == other.error
    }

    override fun hashCode(): Int {
        var result = isLoading.hashCode()
        result = 31 * result + waveform.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + frameDurationMs.hashCode()
        result = 31 * result + trimStartMs.hashCode()
        result = 31 * result + trimEndMs.hashCode()
        result = 31 * result + playbackPosition.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + isApplying.hashCode()
        result = 31 * result + fadeInMs.hashCode()
        result = 31 * result + fadeOutMs.hashCode()
        result = 31 * result + fadeCurve.hashCode()
        result = 31 * result + playbackSpeed.hashCode()
        result = 31 * result + losslessCut.hashCode()
        result = 31 * result + exportFormat.hashCode()
        result = 31 * result + (exportBitrateKbps ?: 0)
        result = 31 * result + waveformZoom.hashCode()
        result = 31 * result + waveformViewportStart.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (localFilePath?.hashCode() ?: 0)
        result = 31 * result + isLocalFile.hashCode()
        result = 31 * result + (success?.hashCode() ?: 0)
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

private val FILE_SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]")
private val NAME_SANITIZE_REGEX = Regex("[^a-zA-Z0-9]")

@HiltViewModel
class SoundEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val soundApplier: SoundApplier,
    private val audioTrimmer: AudioTrimmer,
    private val soundUrlResolver: SoundUrlResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(SoundEditorState())
    val state = _state.asStateFlow()

    private var player: android.media.MediaPlayer? = null
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var undoState: UndoSnapshot? = null
    private var loadedSoundKey: String? = null

    private data class UndoSnapshot(
        val trimStartMs: Long,
        val trimEndMs: Long,
        val fadeIn: Long,
        val fadeOut: Long,
        val fadeCurve: AudioFadeCurve,
        val playbackSpeed: Float,
    )

    fun loadSound(sound: Sound, editConfirmed: Boolean = false): Boolean {
        val currentState = _state.value
        val soundKey = sound.stableKey()
        if (shouldReuseLoadedSound(loadedSoundKey, soundKey, currentState)) {
            return true
        }
        soundEditorEditGateMessage(sound, editConfirmed)?.let { message ->
            _state.update { it.copy(error = message) }
            return false
        }
        loadedSoundKey = soundKey
        if (sound.downloadUrl.isBlank() && sound.previewUrl.isBlank() && sound.sourcePageUrl.isBlank()) return false
        loadRemoteSound(sound.name) {
            val resolvedUrl = soundUrlResolver.resolve(sound)
                ?: throw IllegalStateException("No audio URL available")
            downloadToCache(resolvedUrl, sound.name, soundKey)
        }
        return true
    }

    fun loadFromUrl(url: String, name: String) {
        loadedSoundKey = buildRemoteAudioCacheIdentity(url, name)
        loadRemoteSound(name) { downloadToCache(url, name, buildRemoteAudioCacheIdentity(url, name)) }
    }

    private fun loadRemoteSound(name: String, loader: suspend () -> File) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            stopPlayback()
            _state.update {
                it.copy(
                    isLoading = true,
                    waveform = floatArrayOf(),
                    durationMs = 0,
                    frameDurationMs = 1,
                    trimStartMs = 0,
                    trimEndMs = 0,
                    playbackPosition = 0f,
                    isPlaying = false,
                    isApplying = false,
                    fadeInMs = 0,
                    fadeOutMs = 0,
                    fadeCurve = AudioFadeCurve.LINEAR,
                    playbackSpeed = 1f,
                    losslessCut = false,
                    waveformZoom = 1f,
                    waveformViewportStart = 0f,
                    fileName = name,
                    localFilePath = null,
                    isLocalFile = false,
                    success = null,
                    error = null,
                )
            }
            try {
                val file = withContext(Dispatchers.IO) { loader() }
                val waveform = withContext(Dispatchers.Default) { extractWaveform(file.absolutePath) }
                val timing = withContext(Dispatchers.IO) { getAudioTiming(file.absolutePath) }
                _state.update {
                    it.copy(
                        isLoading = false,
                        waveform = waveform,
                        durationMs = timing.durationMs,
                        frameDurationMs = timing.frameDurationMs,
                        localFilePath = file.absolutePath,
                        trimStartMs = 0,
                        trimEndMs = defaultRingtoneTrimEndMs(timing.durationMs),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isLoading = false, error = "Failed to load: ${e.message}") }
            }
        }
    }

    fun loadFromLocalUri(uri: Uri) {
        val localKey = buildLocalAudioEditorIdentity(uri.toString())
        if (shouldReuseLoadedLocalUri(loadedSoundKey, localKey, _state.value)) {
            return
        }
        loadedSoundKey = localKey
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            stopPlayback()
            _state.update {
                it.copy(
                    isLoading = true,
                    waveform = floatArrayOf(),
                    durationMs = 0,
                    frameDurationMs = 1,
                    trimStartMs = 0,
                    trimEndMs = 0,
                    playbackPosition = 0f,
                    isPlaying = false,
                    isApplying = false,
                    fadeInMs = 0,
                    fadeOutMs = 0,
                    fadeCurve = AudioFadeCurve.LINEAR,
                    playbackSpeed = 1f,
                    losslessCut = false,
                    waveformZoom = 1f,
                    waveformViewportStart = 0f,
                    error = null,
                    isLocalFile = true,
                    success = null,
                )
            }
            var cachedFile: File? = null
            try {
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri).also { cachedFile = it } }
                val name = file.nameWithoutExtension
                val waveform = withContext(Dispatchers.Default) { extractWaveform(file.absolutePath) }
                val timing = withContext(Dispatchers.IO) { getAudioTiming(file.absolutePath) }
                _state.update {
                    it.copy(
                        isLoading = false,
                        fileName = name,
                        waveform = waveform,
                        durationMs = timing.durationMs,
                        frameDurationMs = timing.frameDurationMs,
                        localFilePath = file.absolutePath,
                        trimStartMs = 0,
                        trimEndMs = defaultRingtoneTrimEndMs(timing.durationMs),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A revoked or malformed local URI can fail after the bounded copy
                // succeeds. Do not leave that unusable cache entry behind.
                cachedFile?.delete()
                _state.update { it.copy(isLoading = false, error = "Failed to load file: ${e.message}") }
            }
        }
    }

    fun saveUndo() {
        val s = _state.value
        undoState = UndoSnapshot(
            s.trimStartMs,
            s.trimEndMs,
            s.fadeInMs,
            s.fadeOutMs,
            s.fadeCurve,
            s.playbackSpeed,
        )
    }

    fun undo() {
        undoState?.let { snap ->
            _state.update {
                it.copy(
                    trimStartMs = snap.trimStartMs, trimEndMs = snap.trimEndMs,
                    fadeInMs = snap.fadeIn, fadeOutMs = snap.fadeOut,
                    fadeCurve = snap.fadeCurve,
                    playbackSpeed = snap.playbackSpeed,
                )
            }
            undoState = null
        }
    }

    val canUndo: Boolean get() = undoState != null

    fun setTrimStart(fraction: Float) {
        val state = _state.value
        setTrimStartMs((state.durationMs * fraction.coerceIn(0f, 1f)).roundToLong())
    }

    fun setTrimEnd(fraction: Float) {
        val state = _state.value
        setTrimEndMs((state.durationMs * fraction.coerceIn(0f, 1f)).roundToLong())
    }

    fun setTrimStartMs(milliseconds: Long) {
        _state.update { state ->
            val start = clampTrimStartMs(
                requestedMs = milliseconds,
                trimEndMs = state.trimEndMs,
                durationMs = state.durationMs,
                frameDurationMs = state.frameDurationMs,
            )
            val maxFade = (speedAdjustedDurationMs(state.trimEndMs - start, state.playbackSpeed) / 2L)
                .coerceAtLeast(0L)
            state.copy(
                trimStartMs = start,
                fadeInMs = state.fadeInMs.coerceAtMost(maxFade),
                fadeOutMs = state.fadeOutMs.coerceAtMost(maxFade),
            )
        }
    }

    fun setTrimEndMs(milliseconds: Long) {
        _state.update { state ->
            val end = clampTrimEndMs(
                requestedMs = milliseconds,
                trimStartMs = state.trimStartMs,
                durationMs = state.durationMs,
                frameDurationMs = state.frameDurationMs,
            )
            val maxFade = (speedAdjustedDurationMs(end - state.trimStartMs, state.playbackSpeed) / 2L)
                .coerceAtLeast(0L)
            state.copy(
                trimEndMs = end,
                fadeInMs = state.fadeInMs.coerceAtMost(maxFade),
                fadeOutMs = state.fadeOutMs.coerceAtMost(maxFade),
            )
        }
    }

    fun setFadeIn(ms: Long) {
        _state.update { state ->
            val fadeInMs = ms.coerceIn(0L, state.maximumFadeMs)
            state.copy(
                fadeInMs = fadeInMs,
                losslessCut = state.losslessCut &&
                    isLosslessCutAllowed(fadeInMs, state.fadeOutMs, state.playbackSpeed),
            )
        }
    }

    fun setFadeOut(ms: Long) {
        _state.update { state ->
            val fadeOutMs = ms.coerceIn(0L, state.maximumFadeMs)
            state.copy(
                fadeOutMs = fadeOutMs,
                losslessCut = state.losslessCut &&
                    isLosslessCutAllowed(state.fadeInMs, fadeOutMs, state.playbackSpeed),
            )
        }
    }

    fun setFadeCurve(curve: AudioFadeCurve) {
        _state.update { it.copy(fadeCurve = curve) }
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed !in SOUND_EDITOR_PLAYBACK_SPEEDS) return
        _state.update { state ->
            val maximumFadeMs = (speedAdjustedDurationMs(state.trimDurationMs, speed) / 2L)
                .coerceAtLeast(1L)
            state.copy(
                playbackSpeed = speed,
                fadeInMs = state.fadeInMs.coerceAtMost(maximumFadeMs),
                fadeOutMs = state.fadeOutMs.coerceAtMost(maximumFadeMs),
                losslessCut = state.losslessCut &&
                    isLosslessCutAllowed(state.fadeInMs, state.fadeOutMs, speed),
            )
        }
        player?.let { activePlayer ->
            runCatching {
                activePlayer.playbackParams = activePlayer.playbackParams
                    .setSpeed(speed)
                    .setPitch(1f)
            }
        }
    }

    fun setExportFormat(format: AudioExportFormat) {
        _state.update {
            it.copy(
                exportFormat = format,
                exportBitrateKbps = format.defaultBitrateKbps,
                losslessCut = false,
            )
        }
    }

    fun setExportBitrate(kbps: Int) {
        _state.update { state ->
            if (kbps in state.exportFormat.bitratesKbps) state.copy(exportBitrateKbps = kbps) else state
        }
    }

    fun setLosslessCut(enabled: Boolean) {
        _state.update { state ->
            if (!enabled) state.copy(losslessCut = false)
            else if (state.canUseLosslessCut) state.copy(losslessCut = true)
            else state
        }
    }

    fun transformWaveformViewport(zoomChange: Float, panFraction: Float, focusFraction: Float) {
        _state.update { state ->
            val viewport = updateWaveformViewport(
                zoom = state.waveformZoom,
                startFraction = state.waveformViewportStart,
                zoomChange = zoomChange,
                panFraction = panFraction,
                focusFraction = focusFraction,
            )
            state.copy(
                waveformZoom = viewport.zoom,
                waveformViewportStart = viewport.startFraction,
            )
        }
    }

    fun resetWaveformViewport() {
        _state.update { it.copy(waveformZoom = 1f, waveformViewportStart = 0f) }
    }

    fun togglePlayback() {
        if (_state.value.isPlaying) stopPlayback() else startPlayback()
    }

    fun canWriteSettings(): Boolean = soundApplier.canWriteSettings()

    fun canOpenWriteSettings(): Boolean = soundApplier.canOpenWriteSettings()

    fun requestWriteSettings() = soundApplier.requestWriteSettings()

    fun applyTrimmed(type: ContentType) {
        val s = _state.value
        val path = s.localFilePath ?: return
        viewModelScope.launch {
            if (!soundApplier.canWriteSettings()) {
                _state.update { it.copy(error = "System settings access is required before applying sounds.") }
                return@launch
            }
            _state.update { it.copy(isApplying = true) }
            try {
                val trimmedPath = audioTrimmer.trim(
                    inputPath = path,
                    startMs = s.trimStartMs,
                    endMs = s.trimEndMs,
                    outputFileName = s.fileName,
                    fadeInMs = s.fadeInMs,
                    fadeOutMs = s.fadeOutMs,
                    fadeCurve = s.fadeCurve,
                    playbackSpeed = s.playbackSpeed,
                    exportFormat = s.exportFormat,
                    bitrateKbps = s.exportBitrateKbps,
                    losslessCut = s.losslessCut,
                ).getOrThrow()

                soundApplier.applyFromLocalFile(trimmedPath, s.fileName, type)
                    .onSuccess {
                        val label = when (type) {
                            ContentType.RINGTONE -> "ringtone"
                            ContentType.NOTIFICATION -> "notification"
                            ContentType.ALARM -> "alarm"
                            else -> "sound"
                        }
                        _state.update { it.copy(isApplying = false, success = "Set as $label") }
                    }
                    .onFailure { e ->
                        _state.update { it.copy(isApplying = false, error = e.message) }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isApplying = false, error = e.message) }
            }
        }
    }

    fun exportTrimmed() {
        val state = _state.value
        val path = state.localFilePath ?: return
        viewModelScope.launch {
            _state.update { it.copy(isApplying = true, success = null, error = null) }
            try {
                val outputPath = audioTrimmer.trim(
                    inputPath = path,
                    startMs = state.trimStartMs,
                    endMs = state.trimEndMs,
                    outputFileName = state.fileName,
                    fadeInMs = state.fadeInMs,
                    fadeOutMs = state.fadeOutMs,
                    fadeCurve = state.fadeCurve,
                    playbackSpeed = state.playbackSpeed,
                    exportFormat = state.exportFormat,
                    bitrateKbps = state.exportBitrateKbps,
                    losslessCut = state.losslessCut,
                ).getOrThrow()
                soundApplier.exportFromLocalFile(outputPath, state.fileName)
                    .getOrThrow()
                _state.update {
                    it.copy(
                        isApplying = false,
                        success = context.getString(
                            R.string.editor_sound_export_success,
                            state.effectiveExportFormat.name,
                        ),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        isApplying = false,
                        error = context.getString(
                            R.string.editor_sound_export_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(success = null, error = null) }

    private fun startPlayback() {
        val path = _state.value.localFilePath ?: return
        val startMs = _state.value.trimStartMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val endMs = _state.value.trimEndMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        stopPlayback()
        try {
            player = android.media.MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { mp ->
                    if (player == null) return@setOnPreparedListener
                    try {
                        mp.seekTo(startMs)
                        mp.playbackParams = android.media.PlaybackParams()
                            .setSpeed(_state.value.playbackSpeed)
                            .setPitch(1f)
                        mp.start()
                    } catch (_: IllegalStateException) {
                        return@setOnPreparedListener
                    }
                    _state.update { it.copy(isPlaying = true) }

                    playbackJob?.cancel()
                    playbackJob = viewModelScope.launch {
                        try {
                            while (_state.value.isPlaying) {
                                val p = player ?: break
                                val pos = try { p.currentPosition } catch (_: IllegalStateException) { break }
                                val dur = try { p.duration } catch (_: IllegalStateException) { break }
                                if (shouldLoopTrimPreview(pos, startMs, endMs)) {
                                    try {
                                        p.seekTo(startMs)
                                        p.start()
                                        if (dur > 0) _state.update { it.copy(playbackPosition = startMs.toFloat() / dur) }
                                    } catch (_: IllegalStateException) {
                                        break
                                    }
                                    kotlinx.coroutines.delay(20)
                                    continue
                                }
                                if (dur > 0) _state.update { it.copy(playbackPosition = pos.toFloat() / dur) }
                                kotlinx.coroutines.delay(50)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                        stopPlayback()
                    }
                }
                setOnCompletionListener { mp ->
                    if (_state.value.isPlaying && endMs > startMs) {
                        try {
                            mp.seekTo(startMs)
                            mp.start()
                        } catch (_: IllegalStateException) {
                            stopPlayback()
                        }
                    } else {
                        stopPlayback()
                    }
                }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            player?.apply {
                try { setOnPreparedListener(null) } catch (_: Exception) {}
                try { setOnCompletionListener(null) } catch (_: Exception) {}
                try { setOnErrorListener(null) } catch (_: Exception) {}
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        player = null
        _state.update { it.copy(isPlaying = false) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        stopPlayback()
        super.onCleared()
    }

    private suspend fun downloadToCache(url: String, name: String, cacheIdentity: String): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "audio_edit")
        cacheDir.mkdirs()
        val file = File(cacheDir, buildRemoteAudioCacheFileName(name, cacheIdentity, url))
        if (!file.exists()) {
            val tmpFile = File(cacheDir, file.name + ".tmp")
            try {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Download failed: HTTP ${response.code}")
                    val body = response.body ?: throw Exception("Empty response body")
                    // Editing is for short clips — cap at 96 MB so a hostile/misresolved YT
                    // URL can't fill cacheDir while the user waits on the editor.
                    val advertised = body.contentLength()
                    if (advertised in 1..Long.MAX_VALUE && advertised > MAX_EDIT_DOWNLOAD_BYTES) {
                        throw Exception("Audio file too large (${advertised / (1024 * 1024)} MB)")
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            try {
                                copyStreamCapped(input, output, MAX_EDIT_DOWNLOAD_BYTES)
                            } catch (e: MediaIngestionLimitExceeded) {
                                throw Exception("Audio file too large (${MAX_EDIT_DOWNLOAD_BYTES / (1024 * 1024)} MB)", e)
                            }
                        }
                    }
                }
                if (tmpFile.length() > 0) {
                    if (!tmpFile.renameTo(file)) {
                        tmpFile.copyTo(file, overwrite = true)
                        tmpFile.delete()
                    }
                } else {
                    tmpFile.delete()
                    throw Exception("Download produced empty file")
                }
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            }
        }
        file
    }

    private companion object {
        /** Max size for an audio file downloaded into the editor's cache. */
        private const val MAX_EDIT_DOWNLOAD_BYTES = 96L * 1024 * 1024
    }

    private suspend fun copyUriToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "audio_edit")
        cacheDir.mkdirs()
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "local_audio"
        val safeName = fileName.replace(FILE_SANITIZE_REGEX, "_")
        val tempFile = File(cacheDir, ".${safeName}.${System.nanoTime()}.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyStreamCapped(input, output, MAX_EDIT_DOWNLOAD_BYTES)
                }
            } ?: throw IllegalStateException("Cannot read file")
            if (tempFile.length() <= 0L) throw IllegalStateException("The selected file is empty")

            val sniffed = requireSniffedMediaFile(tempFile, MediaFamily.AUDIO, "Sound")
            val file = File(cacheDir, normalizeMediaFileName(safeName, sniffed))
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            ShareOutbox.deleteExternalMedia(context, uri)
            file
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }
    }

    private fun extractWaveform(path: String, numSamples: Int = 200): FloatArray {
        val amplitudes = FloatArray(numSamples)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return amplitudes

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            val sampleInterval = duration / numSamples

            val buffer = ByteBuffer.allocate(65536)
            var sampleIndex = 0

            while (sampleIndex < numSamples) {
                val targetTime = sampleIndex * sampleInterval
                extractor.seekTo(targetTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                buffer.order(ByteOrder.LITTLE_ENDIAN)
                var sumSquares = 0.0
                val samples = min(size / 2, 1024)
                for (i in 0 until samples) {
                    val sample = buffer.getShort(i * 2).toFloat()
                    sumSquares += sample * sample
                }
                val rms = Math.sqrt(sumSquares / max(samples, 1)).toFloat()
                amplitudes[sampleIndex] = rms / 32768f

                buffer.clear()
                sampleIndex++
                extractor.advance()
            }
        } catch (_: Exception) {
            for (i in amplitudes.indices) {
                amplitudes[i] = (Math.sin(i * 0.3) * 0.5 + 0.5).toFloat() * 0.7f
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return amplitudes
    }

    private data class AudioTiming(val durationMs: Long, val frameDurationMs: Long)

    private fun getAudioTiming(path: String): AudioTiming {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return AudioTiming(0L, 1L)
            val format = extractor.getTrackFormat(trackIndex)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1_000L).coerceAtLeast(0L)
            } else {
                0L
            }
            extractor.selectTrack(trackIndex)
            val firstSampleUs = extractor.sampleTime
            val frameDurationMs = if (firstSampleUs >= 0L && extractor.advance()) {
                val secondSampleUs = extractor.sampleTime
                if (secondSampleUs > firstSampleUs) {
                    ((secondSampleUs - firstSampleUs + 999L) / 1_000L).coerceAtLeast(1L)
                } else {
                    1L
                }
            } else {
                1L
            }
            AudioTiming(durationMs, frameDurationMs)
        } catch (_: Exception) {
            AudioTiming(0L, 1L)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}

internal fun buildRemoteAudioCacheIdentity(url: String, name: String): String = "$name::$url"

internal fun buildLocalAudioEditorIdentity(uri: String): String = "local::$uri"

internal const val MIN_RINGTONE_TRIM_MS: Long = 8_000L
internal const val MAX_RINGTONE_TRIM_MS: Long = 30_000L

internal fun shouldLoopTrimPreview(positionMs: Int, startMs: Int, endMs: Int): Boolean =
    endMs > startMs && positionMs >= endMs

internal fun defaultRingtoneTrimEndMs(durationMs: Long): Long =
    when {
        durationMs <= 0L -> 0L
        else -> durationMs.coerceAtMost(MAX_RINGTONE_TRIM_MS)
    }

internal fun defaultRingtoneTrimEndFraction(durationMs: Long): Float =
    when {
        durationMs <= 0L -> 1f
        durationMs <= MAX_RINGTONE_TRIM_MS -> 1f
        else -> (MAX_RINGTONE_TRIM_MS.toFloat() / durationMs).coerceIn(0.02f, 1f)
    }

internal fun clampTrimStartMs(
    requestedMs: Long,
    trimEndMs: Long,
    durationMs: Long,
    frameDurationMs: Long,
): Long {
    if (durationMs <= 0L || trimEndMs <= 0L) return 0L
    val maximum = (trimEndMs - frameDurationMs.coerceAtLeast(1L)).coerceAtLeast(0L)
    return requestedMs.coerceIn(0L, maximum)
}

internal fun clampTrimEndMs(
    requestedMs: Long,
    trimStartMs: Long,
    durationMs: Long,
    frameDurationMs: Long,
): Long {
    if (durationMs <= 0L) return 0L
    val minimum = (trimStartMs + frameDurationMs.coerceAtLeast(1L)).coerceAtMost(durationMs)
    return requestedMs.coerceIn(minimum, durationMs)
}

internal data class WaveformViewport(val zoom: Float, val startFraction: Float)

internal fun updateWaveformViewport(
    zoom: Float,
    startFraction: Float,
    zoomChange: Float,
    panFraction: Float,
    focusFraction: Float,
): WaveformViewport {
    val oldZoom = zoom.coerceIn(1f, MAX_WAVEFORM_ZOOM)
    val oldSpan = 1f / oldZoom
    val focus = focusFraction.coerceIn(0f, 1f)
    val focusInSource = startFraction + focus * oldSpan
    val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_WAVEFORM_ZOOM)
    val newSpan = 1f / newZoom
    val maximumStart = (1f - newSpan).coerceAtLeast(0f)
    val newStart = (focusInSource - focus * newSpan - panFraction * oldSpan)
        .coerceIn(0f, maximumStart)
    return WaveformViewport(newZoom, newStart)
}

internal const val MAX_WAVEFORM_ZOOM = 8f

internal val SOUND_EDITOR_PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

internal fun shouldReuseLoadedSound(
    loadedSoundKey: String?,
    requestedSoundKey: String,
    state: SoundEditorState,
): Boolean =
    loadedSoundKey == requestedSoundKey &&
        !state.isLocalFile &&
        (state.localFilePath != null || state.isLoading)

internal fun shouldReuseLoadedLocalUri(
    loadedSoundKey: String?,
    requestedLocalKey: String,
    state: SoundEditorState,
): Boolean =
    loadedSoundKey == requestedLocalKey &&
        state.isLocalFile &&
        (state.localFilePath != null || state.isLoading)

internal fun soundEditorEditGateMessage(sound: Sound, editConfirmed: Boolean): String? {
    val capability = sound.soundLicenseCapabilities().capability(SoundAction.EDIT)
    return when (capability.decision) {
        SoundActionDecision.ALLOWED -> null
        SoundActionDecision.CONFIRMATION_REQUIRED -> capability.reason.takeUnless { editConfirmed }
        SoundActionDecision.DISABLED -> capability.reason
    }
}

internal fun buildRemoteAudioCacheFileName(name: String, cacheIdentity: String, url: String): String {
    val ext = when {
        url.contains(".ogg", ignoreCase = true) -> ".ogg"
        url.contains(".wav", ignoreCase = true) -> ".wav"
        url.contains(".flac", ignoreCase = true) -> ".flac"
        else -> ".mp3"
    }
    val safeName = name.replace(NAME_SANITIZE_REGEX, "_").trim('_').ifBlank { "audio" }
    val scopedSuffix = cacheIdentity.hashCode().toUInt().toString(16)
    return "${safeName}_$scopedSuffix$ext"
}
