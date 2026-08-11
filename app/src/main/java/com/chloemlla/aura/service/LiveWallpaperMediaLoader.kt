package com.chloemlla.aura.service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes and coalesces wallpaper decode work for one live-wallpaper engine.
 *
 * Both bitmap engines used to start a bare `Thread` per load, and both trigger a
 * load from `onSurfaceCreated` *and* `onSurfaceChanged`. Every surface churn -
 * rotation, unlock, preview teardown, launcher restart - therefore kicked off
 * another full-screen decode alongside the ones still running, and the wallpaper
 * process never restarts to clean up after them. The cross-engine soak caught it
 * as concurrent loader threads rising with the cycle count.
 *
 * One thread runs at a time, and at most one further request waits behind it:
 * a third request would only decode the state the waiting one is about to
 * produce, so it is dropped rather than queued.
 */
internal class LiveWallpaperMediaLoader(threadName: String) {

    private val outstandingCount = AtomicInteger(0)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    /** Decodes running or waiting to run. */
    val outstanding: Int get() = outstandingCount.get()

    /** Runs [work] on the loader thread unless a newer load already covers it. */
    fun request(work: () -> Unit) {
        if (outstandingCount.get() >= MAX_OUTSTANDING) return
        outstandingCount.incrementAndGet()
        try {
            executor.execute {
                try {
                    work()
                } catch (_: Throwable) {
                    // A failed decode must not kill the loader thread; the engine
                    // keeps whatever it was already drawing.
                } finally {
                    outstandingCount.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            outstandingCount.decrementAndGet()
        }
    }

    /**
     * Stops the loader. Whatever is mid-decode is interrupted and its result
     * discarded by the engine's destroyed check, so from the engine's point of
     * view nothing is outstanding once this returns.
     */
    fun shutdown() {
        executor.shutdownNow()
        outstandingCount.set(0)
    }

    private companion object {
        const val MAX_OUTSTANDING = LIVE_WALLPAPER_MAX_OUTSTANDING_LOADS
    }
}

/**
 * How many decodes one engine may have running or waiting.
 *
 * Exposed because the soak harness asserts against this bound rather than a
 * number copied into the test: if the cap ever changes, the harness follows it
 * instead of quietly going slack.
 */
internal const val LIVE_WALLPAPER_MAX_OUTSTANDING_LOADS = 2
