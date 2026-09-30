package com.freevibe.ui.screens.sounds

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoundPreviewWarmupTest {

    private fun sounds(count: Int) = (1..count).map {
        Sound(
            id = "yt_clip$it",
            source = ContentSource.YOUTUBE,
            name = "Clip $it",
            description = "",
            previewUrl = "",
            downloadUrl = "",
            duration = 12.0,
            tags = emptyList(),
            license = "YouTube",
            uploaderName = "Tester",
        )
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        val gate = CompletableDeferred<Unit>()
    }

    /**
     * A foreground scope on the test scheduler. backgroundScope work is skipped by
     * advanceUntilIdle, so it would never run here. Each test cancels it at the end.
     */
    private fun TestScope.workScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun warmup(
        work: CoroutineScope,
        recorder: Recorder,
        permits: Semaphore = Semaphore(PREVIEW_WORK_CONCURRENCY),
        hold: Boolean = false,
    ) =
        SoundPreviewWarmup(
            scope = work,
            permits = permits,
            needsResolve = { true },
            resolve = { sound ->
                recorder.calls += sound.id
                recorder.active++
                recorder.maxActive = maxOf(recorder.maxActive, recorder.active)
                try {
                    if (hold) recorder.gate.await()
                    "https://example.com/${sound.id}.m4a"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    recorder.cancelled += sound.id
                    throw e
                } finally {
                    recorder.active--
                }
            },
            onResolved = { sound, _ -> recorder.resolved += sound.id },
        )

    @Test
    fun `window covers visible rows plus one lookahead and nothing before layout`() {
        val feed = sounds(30)

        assertEquals(emptyList<Sound>(), previewResolveWindow(feed, null, null))
        assertEquals(feed.subList(3, 9), previewResolveWindow(feed, 3, 7))
        assertEquals("lookahead stops at the end", feed.subList(27, 30), previewResolveWindow(feed, 27, 29))
    }

    @Test
    fun `thirty results resolve only the requested window, each once`() = runTest(StandardTestDispatcher()) {
        val recorder = Recorder()
        val work = workScope()
        val queue = warmup(work, recorder)
        val feed = sounds(30)

        queue.request(key = 1, window = feed.take(INITIAL_PREVIEW_RESOLVE_WINDOW))
        queue.request(key = 1, window = feed.take(INITIAL_PREVIEW_RESOLVE_WINDOW))
        advanceUntilIdle()

        assertEquals(INITIAL_PREVIEW_RESOLVE_WINDOW, recorder.calls.size)
        assertEquals(feed.take(INITIAL_PREVIEW_RESOLVE_WINDOW).map { it.id }.toSet(), recorder.resolved.toSet())
        work.cancel()
    }

    @Test
    fun `switching feed cancels the old feed's pending work`() = runTest(StandardTestDispatcher()) {
        val recorder = Recorder()
        val work = workScope()
        val queue = warmup(work, recorder, hold = true)
        val feed = sounds(6)

        queue.request(key = 1, window = feed)
        advanceUntilIdle()
        assertEquals("only the permit count starts", PREVIEW_WORK_CONCURRENCY, recorder.calls.size)

        queue.switchFeed(2)
        recorder.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(PREVIEW_WORK_CONCURRENCY, recorder.cancelled.size)
        assertEquals("queued rows never start after a switch", PREVIEW_WORK_CONCURRENCY, recorder.calls.size)
        assertTrue("nothing from the old feed lands", recorder.resolved.isEmpty())
        work.cancel()
    }

    @Test
    fun `resolve and prebuffer share one bounded set of permits`() = runTest(StandardTestDispatcher()) {
        val recorder = Recorder()
        val permits = Semaphore(PREVIEW_WORK_CONCURRENCY)
        val work = workScope()
        val queue = warmup(work, recorder, permits = permits, hold = true)

        // A prebuffer holding one slot leaves the rest for resolution.
        permits.acquire()
        queue.request(key = 1, window = sounds(8))
        advanceUntilIdle()
        assertEquals(PREVIEW_WORK_CONCURRENCY - 1, recorder.maxActive)

        permits.release()
        recorder.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(8, recorder.resolved.size)
        assertTrue(recorder.maxActive <= PREVIEW_WORK_CONCURRENCY)
        work.cancel()
    }
}
