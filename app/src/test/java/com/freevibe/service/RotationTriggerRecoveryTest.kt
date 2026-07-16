package com.freevibe.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RotationTriggerRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = RotationTriggerRecovery.clear(context)

    @After
    fun tearDown() = RotationTriggerRecovery.clear(context)

    @Test
    fun `denied trigger request survives until service recovery`() {
        RotationTriggerRecovery.markPending(context, unlock = true, screenOff = false)

        assertEquals(
            PendingRotationTriggerRequest(unlock = true, screenOff = false),
            RotationTriggerRecovery.pendingRequest(context),
        )
    }

    @Test
    fun `latest denied trigger state replaces stale request`() {
        RotationTriggerRecovery.markPending(context, unlock = true, screenOff = false)
        RotationTriggerRecovery.markPending(context, unlock = false, screenOff = true)

        assertEquals(
            PendingRotationTriggerRequest(unlock = false, screenOff = true),
            RotationTriggerRecovery.pendingRequest(context),
        )
    }

    @Test
    fun `clearing recovery removes the durable retry`() {
        RotationTriggerRecovery.markPending(context, unlock = true, screenOff = true)

        RotationTriggerRecovery.clear(context)

        assertNull(RotationTriggerRecovery.pendingRequest(context))
    }
}
