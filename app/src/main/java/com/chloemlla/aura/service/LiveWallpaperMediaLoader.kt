package com.chloemlla.aura.service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
 * One thread runs at a time, and at most one further request waits behind it.
 * A newer request replaces the waiting request, so rapid surface churn always
 * finishes by decoding the latest state.
 */
internal class LiveWallpaperMediaLoader(threadName: String) {

    private val lock = Any()
    @Volatile private var outstandingCount = 0
    private var running = false
    private var stopped = false
    private var pendingWork: (() -> Unit)? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    /** Decodes running or waiting to run. */
    val outstanding: Int get() = outstandingCount

    /** Runs [work] on the loader thread unless a newer load already covers it. */
    fun request(work: () -> Unit) {
        synchronized(lock) {
            if (stopped) return
            if (running) {
                pendingWork = work
                outstandingCount = MAX_OUTSTANDING
                return
            }
            running = true
            outstandingCount = 1
            try {
                executor.execute { runWorkLoop(work) }
            } catch (_: RejectedExecutionException) {
                running = false
                outstandingCount = 0
            }
        }
    }

    private fun runWorkLoop(initialWork: () -> Unit) {
        var work = initialWork
        while (true) {
            try {
                work()
            } catch (_: Throwable) {
                // A failed decode must not kill the loader thread; the engine
                // keeps whatever it was already drawing.
            }
            val next = synchronized(lock) {
                if (stopped) {
                    running = false
                    outstandingCount = 0
                    null
                } else {
                    pendingWork.also {
                        pendingWork = null
                        if (it == null) {
                            running = false
                            outstandingCount = 0
                        } else {
                            outstandingCount = 1
                        }
                    }
                }
            } ?: return
            work = next
        }
    }

    /**
     * Stops the loader. Whatever is mid-decode is interrupted and its result
     * discarded by the engine's destroyed check, so from the engine's point of
     * view nothing is outstanding once this returns.
     */
    fun shutdown() {
        synchronized(lock) {
            stopped = true
            pendingWork = null
            outstandingCount = 0
        }
        executor.shutdownNow()
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
