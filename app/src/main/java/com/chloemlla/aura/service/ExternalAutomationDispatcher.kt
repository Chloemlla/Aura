package com.chloemlla.aura.service

import android.content.Context
import android.content.Intent

/**
 * The single place external rotate/shuffle requests turn into rotation work.
 *
 * Aura exposes two entry points that any other app can reach:
 * - [TaskerActionReceiver], an exported broadcast receiver.
 * - [com.chloemlla.aura.MainActivity], an exported activity that can be started with
 *   the same automation actions (explicit component + action, launcher
 *   shortcut, `am start`).
 *
 * Both must apply the identical opt-in/throttle policy from
 * [ExternalAutomationGate], otherwise the activity path silently becomes an
 * ungated bypass of the user's automation consent. Every accepted request is
 * enqueued exactly once here; callers must not call
 * [RotationTriggerService.enqueueRotation] for automation intents themselves.
 *
 * Intents that are not automation intents (ordinary launcher shortcuts, widget
 * deep links, normal cold starts) short-circuit before the gate so they never
 * write automation diagnostics.
 */
object ExternalAutomationDispatcher {

    /** Entry-point tag recorded in diagnostics for the exported receiver path. */
    const val ENTRY_POINT_RECEIVER = "receiver"

    /** Entry-point tag recorded in diagnostics for the exported activity path. */
    const val ENTRY_POINT_ACTIVITY = "activity"

    /** Decision reason for an intent that is not an automation request at all. */
    const val REASON_NOT_AUTOMATION = "not_automation_intent"

    /**
     * Evaluates [intent] against the shared gate and enqueues rotation work only
     * when the gate accepts it.
     *
     * @return the gate decision, or a [REASON_NOT_AUTOMATION] rejection when the
     *   intent is not one of the documented automation actions.
     */
    fun dispatch(
        context: Context,
        intent: Intent?,
        entryPoint: String,
        nowMs: Long = System.currentTimeMillis(),
        enqueueRotation: (Context) -> Unit = { RotationTriggerService.enqueueRotation(it) },
    ): ExternalAutomationDecision {
        if (intent == null || !ExternalAutomationGate.isSupportedAction(intent.action)) {
            return ExternalAutomationDecision(accepted = false, reason = REASON_NOT_AUTOMATION)
        }
        val decision = ExternalAutomationGate.evaluate(
            context = context,
            intent = intent,
            entryPoint = entryPoint,
            nowMs = nowMs,
        )
        if (decision.accepted) {
            enqueueRotation(context)
        }
        return decision
    }
}
