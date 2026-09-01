package com.chloemlla.aura.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperTarget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * A 24H wallpaper pack maps dayparts (morning/day/evening/night) to wallpaper
 * URIs. The worker checks every 15 minutes and applies the wallpaper for the
 * current daypart if it hasn't already been applied.
 */
@Serializable
data class WallpaperPack(
    val id: String,
    val name: String,
    val target: String = "BOTH",
    val slots: List<DaypartSlot> = emptyList(),
)

@Serializable
data class DaypartSlot(
    val daypart: Daypart,
    val wallpaperUri: String,
    val label: String = "",
)

@Serializable
enum class Daypart(val startHour: Int, val endHour: Int, val displayName: String) {
    MORNING(6, 12, "Morning"),
    DAY(12, 17, "Day"),
    EVENING(17, 21, "Evening"),
    NIGHT(21, 6, "Night");

    fun coversHour(hour: Int): Boolean =
        if (startHour <= endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour

    companion object {
        fun forHour(hour: Int): Daypart = entries.first { it.coversHour(hour) }
    }
}

internal val wallpaperPackJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun parsePack(raw: String): WallpaperPack? =
    if (raw.isBlank()) null
    else runCatching { wallpaperPackJson.decodeFromString<WallpaperPack>(raw) }.getOrNull()

internal fun serializePack(pack: WallpaperPack): String =
    wallpaperPackJson.encodeToString(pack)

internal fun activeSlotForHour(pack: WallpaperPack, hour: Int): DaypartSlot? {
    val daypart = Daypart.forHour(hour)
    return pack.slots.firstOrNull { it.daypart == daypart }
}

/**
 * Minimal catalog identity for a pack slot so the apply can be recorded in history.
 *
 * A daypart slot is just a locator — it has no provider row behind it — but without
 * an identity the coordinator skips history, which is what kept pack wallpapers out
 * of Undo and out of widget/Material You tinting (AURA-G2-14). Same shape
 * `queryLocalFolderWallpapers` builds for a folder image.
 */
internal fun wallpaperPackSlotWallpaper(locator: String): Wallpaper = Wallpaper(
    id = locator,
    source = ContentSource.LOCAL,
    thumbnailUrl = locator,
    fullUrl = locator,
    width = 0,
    height = 0,
    license = "Local User Content",
    uploaderName = "24-hour wallpaper pack",
)

@HiltWorker
class WallpaperPackWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val prefs: PreferencesManager,
    private val wallpaperApplier: WallpaperApplier,
    private val applyCoordinator: WallpaperApplyCoordinator,
    private val receiptStore: BackgroundWorkReceiptStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (!prefs.wallpaperPackEnabled.first()) {
                return Result.success()
            }

            val pack = parsePack(prefs.wallpaperPackJson.first())
            if (pack == null || pack.slots.isEmpty()) {
                // No pack configured is a benign no-op, not a failure — record
                // success so the diagnostic page doesn't show a false alarm
                // (AURA-G2-25).
                receiptStore.recordSuccess(WORK_NAME)
                return Result.success()
            }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val slot = activeSlotForHour(pack, hour)
            if (slot == null || slot.wallpaperUri.isBlank()) {
                // Clear the dedup marker so a partial pack (e.g. morning-only) is
                // re-applied when its daypart comes around again tomorrow.
                if (prefs.wallpaperPackLastAppliedDaypart.first().isNotBlank()) {
                    prefs.setWallpaperPackLastAppliedDaypart("")
                }
                receiptStore.recordSuccess(WORK_NAME)
                return Result.success()
            }

            val lastAppliedDaypart = prefs.wallpaperPackLastAppliedDaypart.first()
            if (lastAppliedDaypart == slot.daypart.name) {
                receiptStore.recordSuccess(WORK_NAME)
                return Result.success()
            }

            val target = runCatching { WallpaperTarget.valueOf(pack.target) }
                .getOrDefault(WallpaperTarget.BOTH)

            applyCoordinator.apply(
                wallpaper = wallpaperPackSlotWallpaper(slot.wallpaperUri),
                target = target,
                policy = WallpaperApplyPolicy.BACKGROUND,
            ) { wallpaperApplier.applyByLocator(slot.wallpaperUri, target) }
                .onSuccess {
                    prefs.setWallpaperPackLastAppliedDaypart(slot.daypart.name)
                    receiptStore.recordSuccess(WORK_NAME)
                }
                .onFailure { e ->
                    receiptStore.recordFailure(
                        uniqueWorkName = WORK_NAME,
                        errorClass = e.javaClass.simpleName,
                        deferralReason = e.message ?: "apply failed",
                    )
                }

            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            receiptStore.recordFailure(
                uniqueWorkName = WORK_NAME,
                errorClass = e.javaClass.simpleName,
                deferralReason = e.message ?: "unknown error",
            )
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "wallpaper_pack"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WallpaperPackWorker>(
                15L, TimeUnit.MINUTES,
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15, TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
