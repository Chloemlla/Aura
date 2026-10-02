package com.chloemlla.aura.ui.screens.sounds

import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.util.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Rows a fresh feed resolves before the list has reported what is on screen. */
internal const val INITIAL_PREVIEW_RESOLVE_WINDOW = 6

/** Rows past the last visible one that are resolved ahead of a scroll. */
internal const val PREVIEW_RESOLVE_LOOKAHEAD = 1

/** Resolve and prebuffer work share this many slots, so neither can starve playback. */
internal const val PREVIEW_WORK_CONCURRENCY = 3

/**
 * The rows whose preview is worth resolving now: what the list shows plus a
 * short lookahead. Empty until the list has laid anything out.
 */
internal fun previewResolveWindow(
    sounds: List<Sound>,
    firstVisibleIndex: Int?,
    lastVisibleIndex: Int?,
    lookahead: Int = PREVIEW_RESOLVE_LOOKAHEAD,
): List<Sound> {
    if (firstVisibleIndex == null || lastVisibleIndex == null || sounds.isEmpty()) return emptyList()
    val start = firstVisibleIndex.coerceIn(0, sounds.size)
    val end = (lastVisibleIndex + 1 + lookahead).coerceIn(start, sounds.size)
    return sounds.subList(start, end)
}

/**
 * One bounded, cancellable queue for YouTube preview resolution.
 *
 * Sound tabs used to extract a stream for every search result before anyone
 * tapped, which spent data and battery on audio nobody heard. Now only rows on
 * screen (plus [PREVIEW_RESOLVE_LOOKAHEAD]) are resolved, each at most once per
 * feed, and a new feed cancels whatever the old one still had in flight.
 */
internal class SoundPreviewWarmup(
    private val scope: CoroutineScope,
    private val permits: Semaphore,
    private val needsResolve: (Sound) -> Boolean,
    private val resolve: suspend (Sound) -> String?,
    private val onResolved: (Sound, String) -> Unit,
) {
    private var feedKey: Int? = null
    private var feedScope = newFeedScope()
    private val requested = mutableSetOf<String>()

    private fun newFeedScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * Resolves [window] for the feed identified by [key]. A different key means a
     * new tab, query, or refresh: the previous feed's pending work is cancelled.
     * Call from the main thread.
     */
    fun request(key: Int, window: List<Sound>) {
        switchFeed(key)
        window.forEach { sound ->
            if (!needsResolve(sound)) return@forEach
            if (!synchronized(requested) { requested.add(sound.stableKey()) }) return@forEach
            feedScope.launch {
                try {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        resolve(sound)?.let { url ->
                            currentCoroutineContext().ensureActive()
                            onResolved(sound, url)
                        }
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    // A failed warmup is retried on tap, where the error is shown.
                    synchronized(requested) { requested.remove(sound.stableKey()) }
                }
            }
        }
    }

    /** Drops the previous feed's pending work as soon as the feed identity changes. */
    fun switchFeed(key: Int) {
        if (key == feedKey) return
        cancel()
        feedKey = key
    }

    fun cancel() {
        feedScope.cancel()
        feedScope = newFeedScope()
        synchronized(requested) { requested.clear() }
        feedKey = null
    }
}
