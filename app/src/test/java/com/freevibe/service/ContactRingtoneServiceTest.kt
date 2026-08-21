package com.freevibe.service

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactRingtoneServiceTest {

    @Test
    fun `dnd access denial is reported before contact priority guidance`() {
        assertEquals(
            ContactDndGuidance.POLICY_ACCESS_REQUIRED,
            classifyContactDndGuidance(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                hasPolicyAccess = false,
                priorityCategories = 0,
                priorityCallSenders = 0,
                contactIsStarred = false,
            ),
        )
    }

    @Test
    fun `priority mode asks for a starred contact when that is the active rule`() {
        assertEquals(
            ContactDndGuidance.CONTACT_MUST_BE_STARRED,
            classifyContactDndGuidance(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                hasPolicyAccess = true,
                priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
                priorityCallSenders = NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                contactIsStarred = false,
            ),
        )
    }

    @Test
    fun `priority mode allows a starred contact when calls use starred senders`() {
        assertEquals(
            ContactDndGuidance.NONE,
            classifyContactDndGuidance(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                hasPolicyAccess = true,
                priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
                priorityCallSenders = NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                contactIsStarred = true,
            ),
        )
    }

    @Test
    fun `alarms only and no interruptions report blocked calls`() {
        assertEquals(
            ContactDndGuidance.CALLS_BLOCKED,
            classifyContactDndGuidance(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS,
                hasPolicyAccess = true,
                priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
                priorityCallSenders = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                contactIsStarred = true,
            ),
        )
        assertEquals(
            ContactDndGuidance.CALLS_BLOCKED,
            classifyContactDndGuidance(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE,
                hasPolicyAccess = true,
                priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
                priorityCallSenders = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                contactIsStarred = true,
            ),
        )
    }
}
