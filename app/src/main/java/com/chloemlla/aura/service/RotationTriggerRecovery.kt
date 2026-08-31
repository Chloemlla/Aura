package com.chloemlla.aura.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chloemlla.aura.MainActivity
import com.chloemlla.aura.R

internal data class PendingRotationTriggerRequest(
    val unlock: Boolean,
    val screenOff: Boolean,
)

/**
 * Persists foreground-service starts that Android rejects while Aura is in the
 * background. Opening Aura retries the exact request from a resumed activity.
 */
internal object RotationTriggerRecovery {
    private const val PREFS_NAME = "rotation_trigger_recovery"
    private const val KEY_PENDING = "pending"
    private const val KEY_UNLOCK = "unlock"
    private const val KEY_SCREEN_OFF = "screen_off"
    internal const val NOTIFICATION_ID = 9242
    private const val REQUEST_CODE = 9242

    fun pendingRequest(context: Context): PendingRotationTriggerRequest? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        return PendingRotationTriggerRequest(
            unlock = prefs.getBoolean(KEY_UNLOCK, false),
            screenOff = prefs.getBoolean(KEY_SCREEN_OFF, false),
        ).takeIf { it.unlock || it.screenOff }
    }

    @SuppressLint("ApplySharedPref")
    fun markPending(context: Context, unlock: Boolean, screenOff: Boolean) {
        val alreadyPending = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_UNLOCK, unlock)
            .putBoolean(KEY_SCREEN_OFF, screenOff)
            .commit()
        // A pending state can be re-marked on every background process start
        // (periodic worker wake-up, widget refresh); re-notifying the same
        // notification each time re-alerts the user (AURA-G2-16).
        if (!alreadyPending) showRecoveryNotification(context)
    }

    fun retryIfPending(context: Context) {
        val request = pendingRequest(context) ?: return
        RotationTriggerService.reconcile(
            context = context,
            unlock = request.unlock,
            screenOff = request.screenOff,
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun showRecoveryNotification(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.ROTATION_RECOVERY)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(context.getString(R.string.rotation_trigger_recovery_title))
            .setContentText(context.getString(R.string.rotation_trigger_recovery_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.rotation_trigger_recovery_body)),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // API 33+ can suppress this alert when notification permission is denied.
        // The durable marker still guarantees an automatic retry on the next open.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
