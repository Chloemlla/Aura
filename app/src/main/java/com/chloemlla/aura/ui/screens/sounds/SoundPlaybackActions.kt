package com.chloemlla.aura.ui.screens.sounds

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.service.AudioPlaybackManager
import com.chloemlla.aura.service.AudioPreviewCache
import com.chloemlla.aura.service.SelectedContentHolder
import com.chloemlla.aura.util.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val FIRST_VISIBLE_PREVIEW_COUNT = 8

internal class SoundPlaybackActions(
    private val audioPlaybackManager: AudioPlaybackManager,
    private val audioPreviewCache: AudioPreviewCache,
    private val selectedContent: SelectedContentHolder,
    private val youtubeProviderEnabled: StateFlow<Boolean>,
    private val autoPreview: StateFlow<Boolean>,
    private val previewVolume: StateFlow<Float>,
    private val state: MutableStateFlow<SoundsUiState>,
    private val communityUploads: MutableStateFlow<List<Sound>>,
    private val previewReadyIds: MutableStateFlow<Set<String>>,
    private val playbackProgress: MutableStateFlow<Float>,
    private val scope: CoroutineScope,
    private val resolveYouTubePreview: suspend (Sound) -> String?,
    private val shouldRefreshYouTubePreview: (Sound) -> Boolean,
    private val youtubeDisabledMessage: () -> String,
    private val persistFeed: (SoundsUiState) -> Unit = {},
) {
    private var progressJob: Job? = null
    private val previewPrebufferInFlight = ConcurrentHashMap.newKeySet<String>()
    private var pendingSeekFraction: Float? = null

    fun togglePlayback(sound: Sound) {
        val soundKey = sound.stableKey()
        if (sound.source == ContentSource.YOUTUBE && !youtubeProviderEnabled.value) {
            state.update { it.copy(error = youtubeDisabledMessage()) }
            return
        }
        if (state.value.playingId == soundKey) {
            stopPlayback()
        } else if (soundKey == state.value.resolvingId) {
            state.update { it.copy(resolvingId = null) }
        } else if (shouldRefreshYouTubePreview(sound)) {
            scope.launch {
                state.update { it.copy(resolvingId = soundKey) }
                val url = resolveYouTubePreview(sound)
                if (state.value.resolvingId != soundKey) return@launch
                if (url != null) {
                    val updatedSound = cacheResolvedPreview(sound, url)
                    state.update { it.copy(resolvingId = null) }
                    startPlayback(updatedSound)
                } else {
                    state.update { it.copy(resolvingId = null, error = "Could not load audio") }
                }
            }
        } else {
            startPlayback(sound)
        }
    }

    fun seekTo(fraction: Float) {
        val dur = audioPlaybackManager.duration.value
        if (dur > 0) {
            audioPlaybackManager.seekTo((fraction * dur).toLong())
        } else {
            // Duration is not ready yet (playback was just started); carry the request
            // and apply it once the progress loop observes a real duration.
            pendingSeekFraction = fraction.coerceIn(0f, 1f)
        }
    }

    fun stopIfPlaying(sound: Sound) {
        if (state.value.playingId == sound.stableKey()) stopPlayback()
    }

    fun stopPlayback() {
        progressJob?.cancel()
        playbackProgress.value = 0f
        pendingSeekFraction = null
        state.update { it.copy(resolvingId = null) }
        audioPlaybackManager.stop()
    }

    fun schedulePreviewPrebuffer(sounds: List<Sound>) {
        if (!autoPreview.value) return
        sounds
            .asSequence()
            .filter { it.previewUrl.isNotBlank() }
            .filter { it.source != ContentSource.YOUTUBE || youtubeProviderEnabled.value }
            .take(FIRST_VISIBLE_PREVIEW_COUNT)
            .forEach { sound ->
                val key = sound.stableKey()
                if (key in previewReadyIds.value || !previewPrebufferInFlight.add(key)) return@forEach
                scope.launch {
                    try {
                        if (audioPreviewCache.prebuffer(sound)) {
                            previewReadyIds.update { it + key }
                        }
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                    } finally {
                        previewPrebufferInFlight.remove(key)
                    }
                }
            }
    }

    fun isInPreviewPrebufferWindow(soundKey: String): Boolean {
        val visibleFeed = state.value.sounds.take(FIRST_VISIBLE_PREVIEW_COUNT)
        return visibleFeed.any { it.stableKey() == soundKey }
    }

    fun cancelProgress() {
        progressJob?.cancel()
    }

    /** True while this instance is the one driving the shared player's progress. */
    val isActivelyPlaying: Boolean get() = progressJob?.isActive == true

    fun cacheResolvedPreview(sound: Sound, previewUrl: String): Sound {
        if (previewUrl.isBlank()) return sound
        val updatedSound = sound.copy(previewUrl = previewUrl)
        val targetKey = updatedSound.stableKey()

        state.update { st ->
            val refreshed = st.sounds.map { existing ->
                if (existing.stableKey() == targetKey && existing.previewUrl != previewUrl) {
                    existing.copy(previewUrl = previewUrl)
                } else {
                    existing
                }
            }
            if (refreshed == st.sounds) st else st.copy(sounds = refreshed)
        }
        communityUploads.update { uploads ->
            uploads.map { existing ->
                if (existing.stableKey() == targetKey && existing.previewUrl != previewUrl) {
                    existing.copy(previewUrl = previewUrl)
                } else {
                    existing
                }
            }
        }

        val currentSelected = selectedContent.selectedSound.value
        if (currentSelected?.stableKey() == targetKey && currentSelected.previewUrl != previewUrl) {
            selectedContent.selectSound(currentSelected.copy(previewUrl = previewUrl))
        }

        val refreshedSound = selectedContent.selectedSound.value?.takeIf { it.stableKey() == targetKey } ?: updatedSound
        persistFeed(state.value)
        if (isInPreviewPrebufferWindow(targetKey)) {
            schedulePreviewPrebuffer(listOf(refreshedSound))
        }
        return refreshedSound
    }

    private fun startPlayback(sound: Sound) {
        if (sound.source == ContentSource.YOUTUBE && !youtubeProviderEnabled.value) {
            state.update { it.copy(error = youtubeDisabledMessage()) }
            return
        }
        stopPlayback()
        val soundKey = sound.stableKey()
        if (sound.source == ContentSource.YOUTUBE) {
            state.update { it.copy(resolvingId = soundKey) }
        }
        audioPlaybackManager.play(sound, sound.previewUrl, previewVolume.value)
        progressJob?.cancel()
        progressJob = scope.launch {
            while (audioPlaybackManager.currentSoundId.value == soundKey) {
                audioPlaybackManager.pollProgress()
                val dur = audioPlaybackManager.duration.value
                val pos = audioPlaybackManager.currentPosition.value
                pendingSeekFraction?.let { fraction ->
                    if (dur > 0) {
                        audioPlaybackManager.seekTo((fraction * dur).toLong())
                        pendingSeekFraction = null
                    }
                }
                playbackProgress.value = if (dur > 0) pos.toFloat() / dur else 0f
                delay(100)
            }
        }
    }
}
