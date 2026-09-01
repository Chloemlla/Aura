package com.chloemlla.aura.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chloemlla.aura.MainActivity
import com.chloemlla.aura.data.local.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that dynamically registers broadcast receivers for
 * [Intent.ACTION_USER_PRESENT] (unlock) and [Intent.ACTION_SCREEN_OFF].
 *
 * Required as a foreground service because both broadcasts are on the
 * background-restriction allow-list (Android 8+) only when the receiver is
 * runtime-registered from a long-lived process. A manifest-declared receiver
 * would not fire on phone unlock.
 *
 * ROADMAP NX-6 — first slice: per-unlock + screen-off pre-stage rotation
 * triggers. Off-by-default; the user opts in via two new Settings toggles
 * backed by `PreferencesManager.rotateOnUnlock` and `rotateOnScreenOff`.
 *
 * Each trigger enqueues a one-shot `AutoWallpaperWorker` (same worker the
 * periodic rotation already uses) with the same constraints the periodic
 * schedule respects. WorkManager handles retries and battery coalescing for us.
 *
 * Not in scope for this slice:
 * - Per-app rotation exclusion (needs `PACKAGE_USAGE_STATS`)
 * - Sub-15-minute periodic rotation (AlarmManager-backed)
 * - One-tap-shuffle Glance widget (bundled with NX-2 widget polish)
 */
@AndroidEntryPoint
class RotationTriggerService : Service() {

    @Inject lateinit var prefs: PreferencesManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var receiver: BroadcastReceiver? = null
    @Volatile private var screenOffEnabled = false
    @Volatile private var unlockEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // API 31+ can throw ForegroundServiceStartNotAllowedException here when
        // the service is recreated in the background (START_STICKY restart).
        // Degrade to a stopped service instead of crashing; RotationTriggerRecovery
        // rebuilds it on the next foreground (AURA-G2-09).
        if (!startInForeground()) {
            stopSelf()
            return
        }
        RotationTriggerRecovery.clear(this)
        registerTriggers()
        // Cold-start the trigger-path constraint cache so a first unlock/screen-off
        // after process death doesn't fall back to the requiresNetwork=true default
        // and strand an offline local-folder rotation (AURA-G2-12).
        serviceScope.launch { AutoWallpaperWorker.refreshOneShotConstraints(prefs) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY restart after process death: the volatile flags are gone
            // and defaulting them to false would stopSelf() — silently killing
            // rotate-on-unlock until the app is next opened. Re-read the toggles off
            // the main thread; DataStore hits disk on cold start and blocking here
            // pushes the 5s startForeground window (AURA-G2-08).
            refreshFromPreferences()
        } else {
            // Allow Intent extras to update which triggers are active without
            // restarting the service. EXTRA_UNLOCK / EXTRA_SCREEN_OFF default to the
            // current state so the caller can selectively flip one.
            screenOffEnabled = intent.getBooleanExtra(EXTRA_SCREEN_OFF, screenOffEnabled)
            unlockEnabled = intent.getBooleanExtra(EXTRA_UNLOCK, unlockEnabled)
            if (!screenOffEnabled && !unlockEnabled) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun refreshFromPreferences() {
        serviceScope.launch {
            unlockEnabled = prefs.rotateOnUnlock.first()
            screenOffEnabled = prefs.rotateOnScreenOff.first()
            if (!screenOffEnabled && !unlockEnabled) {
                stopSelf()
            }
        }
    }

    private fun startInForeground(): Boolean {
        // Explicit activity intent (never null) instead of
        // getLaunchIntentForPackage, which returns null when the launcher
        // component is disabled and would crash PendingIntent.getActivity
        // (AURA-G2-26).
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, NotificationChannels.ROTATION_TRIGGERS)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Aura — wallpaper triggers")
            .setContentText("Rotating on unlock / screen off")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) when Aura is in
            // the background. Report the denial so a later foreground rebuilds the
            // service with the desired triggers.
            false
        }
    }

    private fun registerTriggers() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> if (unlockEnabled) enqueueRotation(context)
                    Intent.ACTION_SCREEN_OFF -> if (screenOffEnabled) enqueueRotation(context)
                }
            }
        }
        receiver = rx
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // ContextCompat is available but the receiver does not export any callbacks
        // back to other apps — RECEIVER_NOT_EXPORTED is the correct flag on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rx, filter)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 9241
        const val EXTRA_UNLOCK = "extra_unlock"
        const val EXTRA_SCREEN_OFF = "extra_screen_off"
        const val WORK_NAME = "rotation_trigger_oneshot"

        /**
         * Idempotent start: if any trigger is enabled, start (or update) the service;
         * if both are disabled, stop it. A background-start denial is persisted and
         * retried when the user next resumes Aura.
         */
        fun reconcile(context: Context, unlock: Boolean, screenOff: Boolean) {
            if (unlock || screenOff) {
                val intent = Intent(context, RotationTriggerService::class.java).apply {
                    putExtra(EXTRA_UNLOCK, unlock)
                    putExtra(EXTRA_SCREEN_OFF, screenOff)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (_: IllegalStateException) {
                    // Android 8-11 use IllegalStateException for background FGS
                    // denials; Android 12+ throws its ForegroundServiceStartNotAllowedException
                    // subclass. Persist the exact request and make the degraded state visible.
                    RotationTriggerRecovery.markPending(context, unlock, screenOff)
                }
            } else {
                RotationTriggerRecovery.clear(context)
                context.stopService(Intent(context, RotationTriggerService::class.java))
            }
        }

        /**
         * Enqueue a one-shot rotation. Reuses [AutoWallpaperWorker] (the periodic
         * worker already does the right thing — it reads source / target / shuffle
         * from prefs and applies). A plain one-shot: expedited was dropped because
         * it requires getForegroundInfo() on API 26-30 and none of the workers
         * implement it, so expedited runs failed outright there (AURA-G2-03).
         *
         * [receiptWorkName] only selects the receipt bucket the run reports into —
         * every one-shot rotation shares this single unique work name so that
         * WorkManager can actually serialise them. Settings "Run now" used to enqueue
         * under a third name of its own, which meant it could run concurrently with
         * an unlock trigger and a periodic rotation, each overwriting the other's
         * wallpaper and history row (AURA-G2-07).
         */
        internal fun enqueueRotation(context: Context, receiptWorkName: String = WORK_NAME) {
            // Reuse the periodic path's constraint resolution (source-aware
            // network requirement + the user's charging / Wi-Fi / idle opt-ins)
            // instead of hard-coding NetworkType.CONNECTED, which stranded
            // local-folder rotations offline and ignored Wi-Fi-only
            // (AURA-G2-12). The flags come from the cache refreshed by
            // schedule() / Settings toggles / onCreate below.
            val constraints = buildAutoWallpaperConstraints(
                requiresCharging = AutoWallpaperWorker.cachedRequiresCharging,
                requiresWiFiOnly = AutoWallpaperWorker.cachedRequiresWiFiOnly,
                requiresIdle = AutoWallpaperWorker.cachedRequiresIdle,
                requiresNetwork = AutoWallpaperWorker.cachedRequiresNetwork,
            )
            val request = OneTimeWorkRequestBuilder<AutoWallpaperWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        AutoWallpaperWorker.RECEIPT_WORK_NAME_KEY to receiptWorkName,
                        AutoWallpaperWorker.TRIGGERED_ROTATION_KEY to true,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                // APPEND_OR_REPLACE, not KEEP: KEEP silently dropped a tap on the
                // tile or "Run now" whenever a rotation was already queued, which
                // reads as "the button does nothing". Appending runs them in order
                // instead of concurrently.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
