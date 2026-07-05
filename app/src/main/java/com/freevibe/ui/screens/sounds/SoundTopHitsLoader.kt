package com.freevibe.ui.screens.sounds

import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.Sound
import com.freevibe.data.repository.YouTubeRepository
import com.freevibe.service.BundledContentProvider
import com.freevibe.util.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore

internal class SoundTopHitsLoader(
    private val youtubeRepo: YouTubeRepository,
    private val prefs: PreferencesManager,
    private val bundledContent: BundledContentProvider,
    private val topHits: MutableStateFlow<List<Sound>>,
    private val scope: CoroutineScope,
    private val schedulePreviewPrebuffer: (List<Sound>) -> Unit,
    private val cacheResolvedPreview: (Sound, String) -> Sound,
) {
    private val ytResolveSemaphore = Semaphore(6)
    private val titleBlocklist = Regex(
        "hindi|telugu|pack|trending|popular|\\bnew\\b|\\btop\\b|\\bbest\\b|timer|countdown|quiz|comparison|tutorial|how to|turn on|turn off|notification spam",
        RegexOption.IGNORE_CASE,
    )

    fun fetchTopHits() {
        scope.launch {
            try {
                if (!isYouTubeProviderEnabled()) {
                    val fallbackHits = rankSounds(
                        sounds = bundledContent.getRingtones(),
                        tab = SoundTab.RINGTONES,
                        filter = SoundQualityFilter.BEST,
                    ).take(5)
                    topHits.value = fallbackHits
                    schedulePreviewPrebuffer(fallbackHits)
                    return@launch
                }

                val blocked = blockedWords()
                val allHits = mutableListOf<Sound>()
                val seenFingerprints = mutableSetOf<String>()
                for (query in PreferencesManager.defaultTopHitQueries()) {
                    if (allHits.size >= 5) break
                    try {
                        val result = youtubeRepo.searchSounds(
                            query = query,
                            maxDuration = 40,
                            minDuration = 8,
                            blockedWords = blocked,
                        )
                        result.items
                            .filter { !titleBlocklist.containsMatchIn(it.name) }
                            .forEach {
                                if (seenFingerprints.add(soundFingerprint(it)) && allHits.size < 5) {
                                    allHits.add(it)
                                }
                            }
                    } catch (e: Exception) {
                        e.rethrowIfCancelled()
                    }
                }
                currentCoroutineContext().ensureActive()
                val rankedHits = rankSounds(allHits, SoundTab.RINGTONES, SoundQualityFilter.BEST).take(5)
                topHits.value = rankedHits
                schedulePreviewPrebuffer(rankedHits)

                supervisorScope {
                    allHits.forEach { hit ->
                        launch {
                            ytResolveSemaphore.acquire()
                            try {
                                youtubeRepo.getAudioPreviewUrl(hit.id.removePrefix("yt_"))?.let { url ->
                                    currentCoroutineContext().ensureActive()
                                    cacheResolvedPreview(hit, url)
                                }
                            } catch (e: Exception) {
                                e.rethrowIfCancelled()
                            } finally {
                                ytResolveSemaphore.release()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancelled()
            }
        }
    }

    private suspend fun blockedWords(): List<String> =
        try {
            prefs.ytSoundBlockedWords.first()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            emptyList()
        }

    private suspend fun isYouTubeProviderEnabled(): Boolean = prefs.youtubeProviderEnabled.first()
}
