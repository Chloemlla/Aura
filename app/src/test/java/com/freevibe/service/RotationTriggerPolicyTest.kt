package com.freevibe.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotationTriggerPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `a tap queues behind a rotation that is applying and replaces one that is only waiting`() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            triggeredRotationPolicy(restartCountdown = true, existing = listOf(WorkInfo.State.RUNNING)),
        )
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            triggeredRotationPolicy(restartCountdown = true, existing = listOf(WorkInfo.State.ENQUEUED)),
        )
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            triggeredRotationPolicy(restartCountdown = true, existing = listOf(WorkInfo.State.SUCCEEDED)),
        )
        assertEquals(ExistingWorkPolicy.REPLACE, triggeredRotationPolicy(restartCountdown = true, existing = emptyList()))
    }

    @Test
    fun `passive triggers always coalesce`() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            triggeredRotationPolicy(restartCountdown = false, existing = listOf(WorkInfo.State.RUNNING)),
        )
        assertEquals(ExistingWorkPolicy.KEEP, triggeredRotationPolicy(restartCountdown = false, existing = emptyList()))
    }

    @Test
    fun `a tap replaces a waiting unlock rotation and a second unlock keeps the tap`() {
        RotationTriggerService.enqueueRotation(context, restartCountdown = false)
        RotationTriggerService.enqueueRotation(context, restartCountdown = false)
        val passive = waiting().single()
        assertTrue("unlock rotations keep the battery floor", passive.constraints.requiresBatteryNotLow())

        RotationTriggerService.enqueueRotation(context, restartCountdown = true)
        val tapped = waiting().single().id
        assertNotEquals(passive.id, tapped)
        // Expedited work only accepts a network constraint; building it with more throws.
        assertFalse(waiting().single().constraints.requiresBatteryNotLow())

        RotationTriggerService.enqueueRotation(context, restartCountdown = false)
        assertEquals(tapped, waiting().single().id)
    }

    private fun waiting(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RotationTriggerService.WORK_NAME)
            .get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
}
