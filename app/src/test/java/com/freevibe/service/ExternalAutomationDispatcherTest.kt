package com.freevibe.service

import android.content.Context
import android.content.Intent
import com.freevibe.ACTION_SHORTCUT_DOWNLOADS
import com.freevibe.ACTION_SHORTCUT_SEARCH
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for the shared automation entry point. Both exported
 * surfaces (activity + broadcast receiver) must produce identical accept/reject
 * results, identical diagnostics, and exactly one enqueue per accepted request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalAutomationDispatcherTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private var enqueueCount = 0

    private val countingEnqueue: (Context) -> Unit = { enqueueCount++ }

    @Before
    fun setUp() {
        enqueueCount = 0
        clearGateState()
    }

    @After
    fun tearDown() = clearGateState()

    private fun clearGateState() {
        context.getSharedPreferences("freevibe_external_automation", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun rotateIntent(callerPackage: String? = null): Intent =
        Intent(TaskerActionReceiver.ACTION_ROTATE_NOW).apply {
            if (callerPackage != null) {
                putExtra(ExternalAutomationGate.EXTRA_CALLER_PACKAGE, callerPackage)
            }
        }

    private fun dispatch(
        intent: Intent?,
        entryPoint: String,
        nowMs: Long,
    ) = ExternalAutomationDispatcher.dispatch(
        context = context,
        intent = intent,
        entryPoint = entryPoint,
        nowMs = nowMs,
        enqueueRotation = countingEnqueue,
    )

    @Test
    fun `activity launch enqueues nothing while automation is disabled`() {
        val decision = dispatch(
            rotateIntent(),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 10_000L,
        )

        assertFalse(decision.accepted)
        assertEquals("disabled", decision.reason)
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `receiver broadcast enqueues nothing while automation is disabled`() {
        val decision = dispatch(
            rotateIntent(),
            ExternalAutomationDispatcher.ENTRY_POINT_RECEIVER,
            nowMs = 10_000L,
        )

        assertFalse(decision.accepted)
        assertEquals("disabled", decision.reason)
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `enabled activity launch enqueues exactly once`() {
        ExternalAutomationGate.setEnabled(context, true)

        val decision = dispatch(
            rotateIntent(),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 10_000L,
        )

        assertTrue(decision.accepted)
        assertEquals(1, enqueueCount)
    }

    @Test
    fun `activity relaunch inside the throttle window enqueues nothing`() {
        ExternalAutomationGate.setEnabled(context, true)

        // Cold start.
        dispatch(rotateIntent(), ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY, nowMs = 10_000L)
        // onNewIntent redelivery a second later — this is the bypass the gate closes.
        val second = dispatch(
            rotateIntent(),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 11_000L,
        )

        assertFalse(second.accepted)
        assertEquals("rate_limited", second.reason)
        assertEquals(1, enqueueCount)
    }

    @Test
    fun `activity and receiver share one throttle budget`() {
        ExternalAutomationGate.setEnabled(context, true)

        dispatch(rotateIntent(), ExternalAutomationDispatcher.ENTRY_POINT_RECEIVER, nowMs = 10_000L)
        val viaActivity = dispatch(
            rotateIntent(),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 12_000L,
        )

        assertFalse(viaActivity.accepted)
        assertEquals("rate_limited", viaActivity.reason)
        assertEquals(1, enqueueCount)
    }

    @Test
    fun `both entry points record the same diagnostic fields`() {
        ExternalAutomationGate.setEnabled(context, true)

        dispatch(
            rotateIntent(callerPackage = "net.dinglisch.android.taskerm"),
            ExternalAutomationDispatcher.ENTRY_POINT_RECEIVER,
            nowMs = 10_000L,
        )
        val fromReceiver = ExternalAutomationGate.readDiagnostics(context)

        clearGateState()
        ExternalAutomationGate.setEnabled(context, true)
        dispatch(
            rotateIntent(callerPackage = "net.dinglisch.android.taskerm"),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 10_000L,
        )
        val fromActivity = ExternalAutomationGate.readDiagnostics(context)

        assertEquals(
            fromReceiver.copy(lastEntryPoint = ""),
            fromActivity.copy(lastEntryPoint = ""),
        )
        assertEquals(ExternalAutomationDispatcher.ENTRY_POINT_RECEIVER, fromReceiver.lastEntryPoint)
        assertEquals(ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY, fromActivity.lastEntryPoint)
    }

    @Test
    fun `malformed caller package is not persisted`() {
        ExternalAutomationGate.setEnabled(context, true)

        dispatch(
            rotateIntent(callerPackage = "bad package\nname"),
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 10_000L,
        )

        assertEquals("", ExternalAutomationGate.readDiagnostics(context).lastCallerPackage)
    }

    @Test
    fun `ordinary launcher shortcuts never touch automation state`() {
        ExternalAutomationGate.setEnabled(context, true)

        listOf(ACTION_SHORTCUT_SEARCH, ACTION_SHORTCUT_DOWNLOADS, Intent.ACTION_MAIN).forEach { action ->
            val decision = dispatch(
                Intent(action),
                ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
                nowMs = 10_000L,
            )
            assertFalse(decision.accepted)
            assertEquals(ExternalAutomationDispatcher.REASON_NOT_AUTOMATION, decision.reason)
        }

        val decisionForNull = dispatch(
            null,
            ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
            nowMs = 10_000L,
        )

        assertFalse(decisionForNull.accepted)
        assertEquals(0, enqueueCount)
        // No diagnostics were written, so the last action stays empty.
        assertEquals("", ExternalAutomationGate.readDiagnostics(context).lastAction)
    }

    @Test
    fun `unknown entry point tags are not persisted`() {
        ExternalAutomationGate.setEnabled(context, true)

        dispatch(rotateIntent(), entryPoint = "spoofed", nowMs = 10_000L)

        assertEquals("", ExternalAutomationGate.readDiagnostics(context).lastEntryPoint)
    }
}
