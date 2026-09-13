package com.freevibe.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWallpaperMediaLoaderTest {

    @Test
    fun `newest pending request replaces stale queued work`() {
        val loader = LiveWallpaperMediaLoader("aura-loader-test")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val executed = CopyOnWriteArrayList<Int>()

        try {
            loader.request {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                executed += 1
                completed.countDown()
            }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            loader.request {
                executed += 2
            }
            loader.request {
                executed += 3
                completed.countDown()
            }

            assertEquals(LIVE_WALLPAPER_MAX_OUTSTANDING_LOADS, loader.outstanding)
            releaseFirst.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), executed)
            waitUntilIdle(loader)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun `stop discards pending work and rejects later requests`() {
        val loader = LiveWallpaperMediaLoader("aura-loader-stop-test")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executed = CopyOnWriteArrayList<Int>()

        loader.request {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
            executed += 1
        }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        loader.request { executed += 2 }

        loader.shutdown()
        releaseFirst.countDown()
        loader.request { executed += 3 }

        assertEquals(0, loader.outstanding)
        assertTrue(executed.none { it == 2 || it == 3 })
    }

    private fun waitUntilIdle(loader: LiveWallpaperMediaLoader) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.outstanding != 0 && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(0, loader.outstanding)
    }
}
