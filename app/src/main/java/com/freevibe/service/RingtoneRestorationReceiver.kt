package com.freevibe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.freevibe.data.local.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Restores Aura-applied ringtone/notification/alarm sounds after a reboot or app
 * update (some OEM updates reset sound URIs to defaults).
 *
 * The receiver only enqueues [RingtoneRestorationWorker] and returns. The previous
 * goAsync() + coroutine design did DataStore and ContentResolver reads under the
 * broadcast deadline, which ANR'd on-device (Android 16, post-boot CPU pressure):
 * BOOT_COMPLETED work must not race a ~10s timer against boot-time disk contention.
 */
class RingtoneRestorationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Nothing the platform keeps can answer "did the boot broadcast reach
        // Aura?" after the fact, so it has to be written down while it is true.
        // One small put, inside the broadcast deadline. A device that has plainly
        // rebooted with no record here is an OEM that never delivered it, and
        // Rotation Health reports exactly that.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { BootObservationStore.recordBoot(context) }
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            RingtoneRestorationWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RingtoneRestorationWorker>().build(),
        )
    }
}

@HiltWorker
class RingtoneRestorationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val prefs: PreferencesManager,
    private val livenessMonitor: LiveWallpaperLivenessMonitor,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            restoreIfNeeded(RingtoneManager.TYPE_RINGTONE, prefs.lastAppliedRingtoneUri.first())
            restoreIfNeeded(RingtoneManager.TYPE_NOTIFICATION, prefs.lastAppliedNotificationUri.first())
            restoreIfNeeded(RingtoneManager.TYPE_ALARM, prefs.lastAppliedAlarmUri.first())
            // A reboot and a package replace are the two moments the platform is
            // known to drop a live wallpaper, and this worker already runs on
            // exactly those. Reading it here keeps the binder call off the boot
            // broadcast deadline and out of any render thread.
            runCatching { livenessMonitor.refresh() }
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Restoration is best-effort; a missing provider right after boot is not
            // worth a retry storm.
            Result.success()
        }
    }

    private fun restoreIfNeeded(type: Int, lastAppliedUri: String) {
        if (lastAppliedUri.isBlank()) return
        val expected = Uri.parse(lastAppliedUri)
        val current = RingtoneManager.getActualDefaultRingtoneUri(applicationContext, type)
        if (current != expected) {
            try {
                applicationContext.contentResolver.openInputStream(expected)?.close()
                    ?: return
                RingtoneManager.setActualDefaultRingtoneUri(applicationContext, type, expected)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val WORK_NAME = "ringtone_restoration"
    }
}
