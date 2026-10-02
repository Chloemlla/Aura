package com.chloemlla.aura.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WorkManager-integration harness for wallpaper rotation reliability — the failure class that
 * dominates comparable OSS changers (changer stalls after reboot, metered background fetches).
 * Verifies through a real WorkManager that (a) the unique periodic work re-arms idempotently
 * (mirroring the boot-time reconcile) and (b) the Wi-Fi-only preference produces an UNMETERED
 * constraint so a metered network cannot satisfy the run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    private fun enqueuedWork(): List<WorkInfo> = uniqueWork().filter { it.state == WorkInfo.State.ENQUEUED }

    private fun arm(restartCountdown: Boolean) = AutoWallpaperWorker.scheduleWithConstraints(
        context = context,
        requiresCharging = false,
        requiresWiFiOnly = false,
        requiresIdle = false,
        requiresNetwork = false,
        restartCountdown = restartCountdown,
    )

    @Test
    fun `a plain re-arm keeps the running countdown`() {
        arm(restartCountdown = false)
        val first = enqueuedWork().single().id

        arm(restartCountdown = false)

        assertEquals("UPDATE keeps the same work item and its period start", first, enqueuedWork().single().id)
    }

    @Test
    fun `a manual apply restart replaces the work so the interval starts over`() {
        arm(restartCountdown = false)
        val first = enqueuedWork().single().id

        arm(restartCountdown = true)

        val restarted = enqueuedWork().single().id
        assertNotEquals("restart must enqueue a fresh periodic work item", first, restarted)
        assertTrue(
            "the replaced item must not stay scheduled",
            uniqueWork().filter { it.id == first }.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `restart interval follows the scheduler when it owns the work`() {
        assertEquals(
            45L,
            rotationRestartIntervalMinutes(
                restartOnManual = true,
                schedulerEnabled = true,
                schedulerIntervalMinutes = 45L,
                autoWallpaperEnabled = true,
                autoWallpaperIntervalHours = 12L,
            ),
        )
    }

    @Test
    fun `restart interval converts the hourly rotation to minutes`() {
        assertEquals(
            720L,
            rotationRestartIntervalMinutes(
                restartOnManual = true,
                schedulerEnabled = false,
                schedulerIntervalMinutes = 45L,
                autoWallpaperEnabled = true,
                autoWallpaperIntervalHours = 12L,
            ),
        )
    }

    @Test
    fun `no restart when the option is off or nothing rotates`() {
        assertNull(
            rotationRestartIntervalMinutes(
                restartOnManual = false,
                schedulerEnabled = true,
                schedulerIntervalMinutes = 45L,
                autoWallpaperEnabled = true,
                autoWallpaperIntervalHours = 12L,
            ),
        )
        assertNull(
            rotationRestartIntervalMinutes(
                restartOnManual = true,
                schedulerEnabled = false,
                schedulerIntervalMinutes = 45L,
                autoWallpaperEnabled = false,
                autoWallpaperIntervalHours = 12L,
            ),
        )
    }

    @Test
    fun `every one-shot rotation shares one unique work name and none are dropped`() {
        // Three unique names used to carry the same worker (periodic, trigger, and
        // Settings "Run now"), so WorkManager serialised none of them and KEEP threw
        // away a second tap (AURA-G2-07).
        RotationTriggerService.enqueueRotation(context)
        RotationTriggerService.enqueueRotation(context, receiptWorkName = "auto_wallpaper_run_now")

        val manager = WorkManager.getInstance(context)
        assertEquals(
            "APPEND_OR_REPLACE queues the second trigger instead of discarding it",
            2,
            manager.getWorkInfosForUniqueWork(RotationTriggerService.WORK_NAME).get().size,
        )
        assertEquals(
            "the manual-run receipt name must not become a third unique work name",
            0,
            manager.getWorkInfosForUniqueWork("auto_wallpaper_run_now").get().size,
        )
        assertEquals(
            "a one-shot must not be enqueued under the periodic name either",
            0,
            uniqueWork().size,
        )
    }
}
