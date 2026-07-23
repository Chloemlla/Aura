package com.freevibe.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WorkManager-integration harness for wallpaper rotation reliability — the failure class that
 * dominates comparable OSS changers (changer stalls after reboot, metered background fetches).
 * Verifies through a real WorkManager that (a) the unique periodic work re-arms idempotently
 * (mirroring the boot-time reconcile) and (b) the Wi-Fi-only preference produces an UNMETERED
 * constraint so a metered network cannot satisfy the run.
 */
@RunWith(RobolectricTestRunner::class)
class AutoWallpaperWorkerSchedulingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun uniqueWork(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AutoWallpaperWorker.WORK_NAME)
            .get()

    @Test
    fun `wifi-only rotation enqueues UNMETERED periodic work`() {
        AutoWallpaperWorker.scheduleWithConstraints(
            context = context,
            requiresCharging = false,
            requiresWiFiOnly = true,
            requiresIdle = false,
            requiresNetwork = true,
        )

        val infos = uniqueWork()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(NetworkType.UNMETERED, infos[0].constraints.requiredNetworkType)
    }

    @Test
    fun `re-arming after reboot keeps a single periodic work`() {
        AutoWallpaperWorker.scheduleWithConstraints(
            context = context,
            requiresCharging = false,
            requiresWiFiOnly = false,
            requiresIdle = false,
            requiresNetwork = true,
        )
        assertEquals(1, uniqueWork().size)

        // Simulate the boot-time reconcile re-arming the same unique work.
        AutoWallpaperWorker.scheduleWithConstraints(
            context = context,
            requiresCharging = false,
            requiresWiFiOnly = false,
            requiresIdle = false,
            requiresNetwork = true,
        )

        val infos = uniqueWork()
        assertEquals("re-arm stays idempotent", 1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos[0].constraints.requiredNetworkType)
    }
}
