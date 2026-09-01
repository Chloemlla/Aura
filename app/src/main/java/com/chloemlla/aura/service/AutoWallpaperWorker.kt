package com.chloemlla.aura.service

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_CLOCK
import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_SINGLE
import com.chloemlla.aura.data.local.SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.WALLPAPER_SOURCE_LOCAL_FOLDER
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperTarget
import com.chloemlla.aura.data.model.stableKey
import com.chloemlla.aura.data.remote.toWallpaper
import com.chloemlla.aura.data.repository.CollectionRepository
import com.chloemlla.aura.data.repository.FavoritesRepository
import com.chloemlla.aura.data.repository.WallpaperRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

@HiltWorker
class AutoWallpaperWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val wallpaperRepo: WallpaperRepository,
    private val favoritesRepo: FavoritesRepository,
    private val collectionRepo: CollectionRepository,
    private val wallpaperApplier: WallpaperApplier,
    private val applyCoordinator: WallpaperApplyCoordinator,
    private val historyManager: WallpaperHistoryManager,
    private val prefs: PreferencesManager,
    private val localWallpaperCatalog: LocalWallpaperCatalog,
    private val receiptStore: BackgroundWorkReceiptStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val receiptWorkName = inputData.getString(RECEIPT_WORK_NAME_KEY) ?: WORK_NAME
        val attempt = runAttemptCount
        return try {
            if (prefs.wallpaperPackEnabled.first()) {
                // The 24H pack owns the wallpaper while it is on. Both features used to
                // set wallpapers on their own schedules, so a rotation was reverted at
                // the next daypart boundary and the pack looked broken (AURA-G2-13).
                // Settings now keeps the two toggles mutually exclusive; this is the
                // backstop for devices upgrading with both already enabled.
                receiptStore.recordSuccess(receiptWorkName)
                return Result.success()
            }
            val schedulerEnabled = prefs.schedulerEnabled.first()
            val legacyEnabled = prefs.autoWallpaperEnabled.first()
            val triggeredRotation = inputData.getBoolean(TRIGGERED_ROTATION_KEY, false)

            val result = when {
                schedulerEnabled -> doSchedulerWork()
                shouldRunLegacyRotation(schedulerEnabled, legacyEnabled, triggeredRotation) -> doLegacyWork()
                else -> Result.success()
            }
            if (result == Result.retry() && attempt >= MAX_FAILED_ATTEMPTS) {
                // A persistent failure (dead provider, revoked SAF grant, cleared
                // collection) would otherwise retry forever with 15min->5h exponential
                // backoff, waking the device for doomed network calls (AURA-G2-31).
                receiptStore.recordFailure(
                    uniqueWorkName = receiptWorkName,
                    errorClass = "TooManyRetries",
                    deferralReason = "wallpaper source failed $MAX_FAILED_ATTEMPTS+ consecutive attempts; check the selected source and wallpaper permission",
                )
                Result.failure()
            } else {
                receiptStore.recordWorkerResult(
                    uniqueWorkName = receiptWorkName,
                    outcome = result.toWorkOutcome(),
                    retryReason = "wallpaper source returned no usable item or apply failed; check selected source, saved collection, and wallpaper permission",
                )
                result
            }
        } catch (_: java.io.IOException) {
            if (attempt >= MAX_FAILED_ATTEMPTS) {
                receiptStore.recordFailure(
                    uniqueWorkName = receiptWorkName,
                    errorClass = "TooManyRetries",
                    deferralReason = "wallpaper source I/O failed $MAX_FAILED_ATTEMPTS+ consecutive attempts; check connection and provider availability",
                )
                Result.failure()
            } else {
                receiptStore.recordRetry(
                    uniqueWorkName = receiptWorkName,
                    errorClass = "IOException",
                    deferralReason = "network or remote wallpaper source I/O failed; check connection and provider availability",
                )
                Result.retry()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            receiptStore.recordFailure(
                uniqueWorkName = receiptWorkName,
                errorClass = e.javaClass.simpleName,
                deferralReason = "wallpaper rotation worker crashed before completing; include diagnostics bundle",
            )
            Result.failure()
        }
    }

    /** Enhanced scheduler with separate home/lock, collections, day/night */
    private suspend fun doSchedulerWork(): Result {
        val homeEnabled = prefs.schedulerHomeEnabled.first()
        val lockEnabled = prefs.schedulerLockEnabled.first()
        // Both targets disabled — succeed quietly instead of fetching/rotating
        // (AURA-G2-30).
        if (!homeEnabled && !lockEnabled) return Result.success()
        val shuffle = prefs.schedulerShuffle.first()

        val defaultSource = prefs.schedulerSource.first()
        val daySource = prefs.schedulerDaySource.first()
        val nightSource = prefs.schedulerNightSource.first()
        val source = resolveScheduledWallpaperSource(
            defaultSource = defaultSource,
            daySource = daySource,
            nightSource = nightSource,
            mode = prefs.schedulerDayNightMode.first(),
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            dayStartHour = prefs.schedulerDayStartHour.first(),
            nightStartHour = prefs.schedulerNightStartHour.first(),
            isSystemDark = applicationContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        ).normalizeWallpaperRotationSource()

        if (source == "wallhaven" && !prefs.wallhavenProviderEnabled.first()) return Result.success()
        if (source == "pixabay" && !prefs.pixabayProviderEnabled.first()) return Result.success()
        if (source == "bing" && !prefs.bingProviderEnabled.first()) return Result.success()

        val target = when {
            homeEnabled && !lockEnabled -> WallpaperTarget.HOME
            lockEnabled && !homeEnabled -> WallpaperTarget.LOCK
            else -> null
        }
        if (source == WALLPAPER_SOURCE_LOCAL_FOLDER && homeEnabled && lockEnabled) {
            val homePick = pickLocalScheduledWallpaper(WallpaperTarget.HOME, shuffle)
            // The lock pick must exclude what home took, or sequential mode picks the
            // same first() for both screens and shuffled mode can collide (AURA-G2-20).
            val lockPick = pickLocalScheduledWallpaper(
                WallpaperTarget.LOCK,
                shuffle,
                exclude = setOfNotNull(homePick?.stableKey()),
            )
            if (homePick == null && lockPick == null) return Result.retry()
            val results = buildList {
                homePick?.let { add(applyAndRecord(it, WallpaperTarget.HOME)) }
                lockPick?.let { add(applyAndRecord(it, WallpaperTarget.LOCK)) }
            }
            return if (results.any { it == Result.retry() }) Result.retry() else Result.success()
        }
        val rawWallpapers = fetchWallpapers(source, target)
        if (rawWallpapers.isEmpty()) return Result.retry()

        val wallpapers = filterRecentRepeats(rawWallpapers)
        val pick = pickScheduledWallpaper(
            wallpapers = wallpapers,
            shuffle = shuffle,
            recentKeys = recentShuffleKeys(wallpapers.size),
        ) ?: return Result.retry()

        val results = buildList {
            if (homeEnabled && lockEnabled) {
                add(applyAndRecord(pick, WallpaperTarget.BOTH))
            } else {
                // Only one target can be enabled here; both use the deterministic pick so
                // sequential (no-shuffle) rotation stays sequential in lock-only mode too.
                if (homeEnabled) add(applyAndRecord(pick, WallpaperTarget.HOME))
                if (lockEnabled) add(applyAndRecord(pick, WallpaperTarget.LOCK))
            }
        }
        return if (results.any { it == Result.retry() }) Result.retry() else Result.success()
    }

    /** Legacy auto-wallpaper (backward compatible) */
    private suspend fun doLegacyWork(): Result {
        val source = prefs.autoWallpaperSource.first().normalizeWallpaperRotationSource()
        val targetStr = prefs.autoWallpaperTarget.first()
        val target = WallpaperTarget.entries.find { it.name == targetStr } ?: WallpaperTarget.BOTH

        if (source == "wallhaven" && !prefs.wallhavenProviderEnabled.first()) return Result.success()
        if (source == "pixabay" && !prefs.pixabayProviderEnabled.first()) return Result.success()
        if (source == "bing" && !prefs.bingProviderEnabled.first()) return Result.success()

        val wallpapers = filterRecentRepeats(fetchWallpapers(source, target))
        val wallpaper = pickScheduledWallpaper(
            wallpapers = wallpapers,
            shuffle = true,
            recentKeys = recentShuffleKeys(wallpapers.size),
        ) ?: return Result.retry()

        return applyAndRecord(wallpaper, target)
    }

    private suspend fun filterRecentRepeats(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (!prefs.avoidRecentRepeats.first()) return wallpapers
        val recentIds = prefs.getRecentRotationIds().toSet()
        val remaining = excludeRecentWallpapers(wallpapers, recentIds)
        return if (remaining.isEmpty() && wallpapers.isNotEmpty()) {
            // Every candidate has already been shown — reset the no-repeat cycle.
            // Without this, sequential rotation pins to first() forever while the
            // recent-IDs FIFO keeps re-recording it.
            prefs.clearRecentRotationIds()
            wallpapers
        } else {
            remaining
        }
    }

    private suspend fun recentShuffleKeys(candidateCount: Int): Set<String> {
        val window = shuffleHistoryWindow(candidateCount)
        if (window == 0) return emptySet()
        return historyManager.getRecent(window).first().map { it.rotationKey() }.toSet()
    }

    private suspend fun pickLocalScheduledWallpaper(
        target: WallpaperTarget,
        shuffle: Boolean,
        exclude: Set<String> = emptySet(),
    ): Wallpaper? {
        val rawWallpapers = fetchWallpapers(WALLPAPER_SOURCE_LOCAL_FOLDER, target)
        if (rawWallpapers.isEmpty()) return null
        val wallpapers = filterRecentRepeats(rawWallpapers)
        val candidates = wallpapers.filterNot { it.stableKey() in exclude }
        // A single-image folder leaves the exclusion empty — fall back to the full
        // set so both screens share the one image instead of reporting a retry.
        val effective = candidates.ifEmpty { wallpapers }
        return pickScheduledWallpaper(
            wallpapers = effective,
            shuffle = shuffle,
            recentKeys = recentShuffleKeys(effective.size),
        )
    }

    private suspend fun fetchWallpapers(source: String, target: WallpaperTarget? = null): List<Wallpaper> {
        val collectionId = prefs.schedulerCollectionId.first()
        return when (source) {
            "collection" -> {
                val collectionItems = if (collectionId > 0) {
                    collectionRepo.getItems(collectionId).first().map {
                        Wallpaper(
                            id = it.wallpaperId,
                            source = try { com.chloemlla.aura.data.model.ContentSource.valueOf(it.source) }
                                catch (_: Exception) { com.chloemlla.aura.data.model.ContentSource.WALLHAVEN },
                            thumbnailUrl = it.thumbnailUrl,
                            fullUrl = it.fullUrl,
                            width = it.width,
                            height = it.height,
                        )
                    }
                } else {
                    emptyList()
                }
                collectionItems.ifEmpty { wallpaperRepo.getDiscover(page = 1).items }
            }
            "favorites" -> favoritesRepo.getWallpapers().first().map { it.toWallpaper() }
            "wallhaven" -> wallpaperRepo.getWallhaven(page = (1..5).random()).items
            "bing" -> wallpaperRepo.getBingDaily(page = 1).items
            "pixabay" -> wallpaperRepo.getPixabay(page = (1..5).random()).items
            WALLPAPER_SOURCE_LOCAL_FOLDER -> {
                localWallpaperCatalog.migrateLegacyFolder(prefs.localWallpaperFolderUri.first())
                localWallpaperCatalog.rotationWallpapers(target)
            }
            "discover" -> wallpaperRepo.getDiscover(page = (1..3).random()).items
            else -> wallpaperRepo.getDiscover(page = 1).items
        }
    }

    private suspend fun applyAndRecord(wallpaper: Wallpaper, target: WallpaperTarget): Result {
        val darkenPercent = prefs.autoWallpaperDarkenPercent.first()
        val nightVariant = shouldUseNightWallpaperVariant(
            enabled = prefs.autoWallpaperNightVariantEnabled.first(),
            schedulerEnabled = prefs.schedulerEnabled.first(),
            schedulerMode = prefs.schedulerDayNightMode.first(),
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            dayStartHour = prefs.schedulerDayStartHour.first(),
            nightStartHour = prefs.schedulerNightStartHour.first(),
            isSystemDark = applicationContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
        // Through the coordinator, not straight to the applier: it owns the
        // history/night-variant commit and the process-wide apply lock, so a rotation
        // can no longer interleave with a trigger run or the 24H pack (AURA-G2-14).
        val applied = applyCoordinator.apply(
            wallpaper = wallpaper,
            target = target,
            policy = WallpaperApplyPolicy.BACKGROUND,
            nightVariantDarkenPercent = darkenPercent,
        ) {
            wallpaperApplier.applyByLocator(
                wallpaper.fullUrl,
                target,
                darkenPercent = darkenPercent,
                nightVariant = nightVariant,
                imageFlow = MediaIngestionImageFlow.AUTO_ROTATION,
            )
        }
        if (applied.isFailure) return Result.retry()
        if (prefs.avoidRecentRepeats.first()) {
            prefs.addRecentRotationId(wallpaper.stableKey())
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "auto_wallpaper"
        const val RECEIPT_WORK_NAME_KEY = "receipt_work_name"
        const val TRIGGERED_ROTATION_KEY = "triggered_rotation"

        /**
         * Consecutive [Result.retry] attempts before a run is downgraded to
         * [Result.failure] and the receipt records a permanent failure. Keeps a
         * persistently-unavailable source (dead provider, revoked SAF grant) from
         * retrying forever under exponential backoff (AURA-G2-31).
         */
        private const val MAX_FAILED_ATTEMPTS = 5

        /**
         * Schedule with minute-based intervals (minimum 15 min, WorkManager floor).
         *
         * Suspends to read constraint prefs from DataStore so we don't block the
         * Main thread (the old non-suspend impl did three sequential runBlocking
         * calls inside a UI-thread coroutine — observable ANR risk on cold starts
         * where DataStore had to hit disk).
         *
         * Constraints flow through to WorkManager which gates execution: a worker
         * fires only when ALL constraints satisfy. Off-by-default for charging /
         * Wi-Fi / idle so existing users keep current behavior on upgrade; opt-in
         * via Settings.
         */
        suspend fun schedule(context: Context, prefs: PreferencesManager, intervalMinutes: Long = 360) {
            refreshOneShotConstraints(prefs)
            scheduleWithConstraints(
                context = context,
                intervalMinutes = intervalMinutes,
                requiresCharging = cachedRequiresCharging,
                requiresWiFiOnly = cachedRequiresWiFiOnly,
                requiresIdle = cachedRequiresIdle,
                requiresNetwork = cachedRequiresNetwork,
            )
        }

        /**
         * Constraint snapshot for the non-suspend one-shot trigger path
         * ([RotationTriggerService.enqueueRotation]). The periodic path resolves
         * its network requirement from the selected source, but a broadcast
         * receiver cannot suspend, so the trigger path reads this cache instead
         * of hard-coding [NetworkType.CONNECTED] — otherwise local-folder
         * rotations strand in ENQUEUED whenever the device is offline, and
         * Wi-Fi-only users get cellular wallpapers (AURA-G2-12).
         *
         * Defaults conservatively to network-required, matching the old
         * hard-coded constraint until the cache is first refreshed.
         */
        @Volatile
        internal var cachedRequiresCharging = false
        @Volatile
        internal var cachedRequiresWiFiOnly = false
        @Volatile
        internal var cachedRequiresIdle = false
        @Volatile
        internal var cachedRequiresNetwork = true

        /**
         * Recomputes the trigger-path constraint cache from current prefs. Kept
         * fresh by [schedule] (periodic configuration) and by every Settings
         * toggle that feeds the resolved constraints or source.
         */
        internal suspend fun refreshOneShotConstraints(prefs: PreferencesManager) {
            cachedRequiresCharging = prefs.autoWallpaperRequiresCharging.first()
            cachedRequiresWiFiOnly = prefs.autoWallpaperRequiresWiFiOnly.first()
            cachedRequiresIdle = prefs.autoWallpaperRequiresIdle.first()
            cachedRequiresNetwork = if (prefs.schedulerEnabled.first()) {
                scheduledSourceCandidates(
                    defaultSource = prefs.schedulerSource.first(),
                    daySource = prefs.schedulerDaySource.first(),
                    nightSource = prefs.schedulerNightSource.first(),
                    mode = prefs.schedulerDayNightMode.first(),
                ).any(::sourceRequiresNetwork)
            } else {
                sourceRequiresNetwork(prefs.autoWallpaperSource.first())
            }
        }

        /**
         * Non-suspend variant for the very rare callers that genuinely cannot
         * suspend (e.g. broadcast receivers or non-Hilt entry points). Caller
         * must already have the resolved constraint flags; we never block here.
         */
        fun scheduleWithConstraints(
            context: Context,
            intervalMinutes: Long = 360,
            requiresCharging: Boolean,
            requiresWiFiOnly: Boolean,
            requiresIdle: Boolean,
            requiresNetwork: Boolean = true,
        ) {
            val constraints = buildAutoWallpaperConstraints(
                requiresCharging = requiresCharging,
                requiresWiFiOnly = requiresWiFiOnly,
                requiresIdle = requiresIdle,
                requiresNetwork = requiresNetwork,
            )

            val request = PeriodicWorkRequestBuilder<AutoWallpaperWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Legacy schedule for backward compatibility */
        suspend fun scheduleHours(context: Context, prefs: PreferencesManager, intervalHours: Long = 12) {
            schedule(context, prefs, intervalHours * 60)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

internal fun shouldRunLegacyRotation(
    schedulerEnabled: Boolean,
    legacyEnabled: Boolean,
    triggeredRotation: Boolean,
): Boolean = !schedulerEnabled && (legacyEnabled || triggeredRotation)

/**
 * Pure builder for AutoWallpaper rotation constraints. Always sets
 * battery-not-low (energy-floor — was already the default, kept). Network type
 * defaults to CONNECTED but tightens to UNMETERED when [requiresWiFiOnly] is
 * set so cellular billed users aren't charged for background fetches. Charging
 * + idle are pure-additive opt-ins.
 *
 * Visible for unit testing.
 */
internal fun buildAutoWallpaperConstraints(
    requiresCharging: Boolean,
    requiresWiFiOnly: Boolean,
    requiresIdle: Boolean,
    requiresNetwork: Boolean = true,
): Constraints {
    val builder = Constraints.Builder()
        .setRequiredNetworkType(
            when {
                !requiresNetwork -> NetworkType.NOT_REQUIRED
                requiresWiFiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            },
        )
        .setRequiresBatteryNotLow(true)
    if (requiresCharging) builder.setRequiresCharging(true)
    if (requiresIdle) builder.setRequiresDeviceIdle(true)
    return builder.build()
}

internal fun String.normalizeWallpaperRotationSource(): String = when (lowercase(java.util.Locale.ROOT)) {
    // Locale.ROOT: Turkish locale turns "i" into dotless 'ı', which would desync this
    // comparison from the hardcoded keys used by the scheduler.
    "", "unsplash", "reddit" -> "discover"
    else -> this
}

internal fun sourceRequiresNetwork(source: String): Boolean =
    source.normalizeWallpaperRotationSource() != WALLPAPER_SOURCE_LOCAL_FOLDER

internal fun resolveScheduledWallpaperSource(
    defaultSource: String,
    daySource: String,
    nightSource: String,
    mode: String,
    hour: Int,
    dayStartHour: Int,
    nightStartHour: Int,
    isSystemDark: Boolean,
): String {
    val day = daySource.ifBlank { defaultSource }
    val night = nightSource.ifBlank { defaultSource }
    return when (mode) {
        SCHEDULER_DAY_NIGHT_MODE_CLOCK -> if (
            isHourInScheduledDayWindow(hour, dayStartHour, nightStartHour)
        ) day else night
        SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME -> if (isSystemDark) night else day
        else -> defaultSource
    }
}

internal fun scheduledSourceCandidates(
    defaultSource: String,
    daySource: String,
    nightSource: String,
    mode: String,
): Set<String> = when (mode) {
    SCHEDULER_DAY_NIGHT_MODE_CLOCK,
    SCHEDULER_DAY_NIGHT_MODE_SYSTEM_THEME,
    -> setOf(daySource.ifBlank { defaultSource }, nightSource.ifBlank { defaultSource })
    SCHEDULER_DAY_NIGHT_MODE_SINGLE -> setOf(defaultSource)
    else -> setOf(defaultSource)
}

internal fun isHourInScheduledDayWindow(hour: Int, dayStartHour: Int, nightStartHour: Int): Boolean {
    val normalizedHour = hour.coerceIn(0, 23)
    val dayStart = dayStartHour.coerceIn(0, 23)
    val nightStart = nightStartHour.coerceIn(0, 23)
    if (dayStart == nightStart) return true
    return if (dayStart < nightStart) {
        normalizedHour in dayStart until nightStart
    } else {
        normalizedHour >= dayStart || normalizedHour < nightStart
    }
}

internal fun shouldUseNightWallpaperVariant(
    enabled: Boolean,
    schedulerEnabled: Boolean,
    schedulerMode: String,
    hour: Int,
    dayStartHour: Int,
    nightStartHour: Int,
    isSystemDark: Boolean,
): Boolean {
    if (!enabled) return false
    if (isSystemDark) return true
    return schedulerEnabled &&
        schedulerMode == SCHEDULER_DAY_NIGHT_MODE_CLOCK &&
        !isHourInScheduledDayWindow(hour, dayStartHour, nightStartHour)
}

internal fun pickScheduledWallpaper(
    wallpapers: List<Wallpaper>,
    shuffle: Boolean,
    recentKeys: Set<String> = emptySet(),
): Wallpaper? = when {
    wallpapers.isEmpty() -> null
    shuffle -> {
        val eligible = wallpapers.filterNot { it.stableKey() in recentKeys }
        (eligible.ifEmpty { wallpapers }).random()
    }
    else -> wallpapers.first()
}

/** Keep a small pool of applied items out of shuffle without starving tiny sources. */
internal fun shuffleHistoryWindow(candidateCount: Int): Int = when {
    candidateCount <= 1 -> 0
    else -> (candidateCount / 10).coerceIn(1, 5)
}

private fun com.chloemlla.aura.data.model.WallpaperHistoryEntity.rotationKey(): String =
    "WALLPAPER::${source.uppercase(Locale.ROOT)}::$wallpaperId"

/**
 * Filters out recently applied wallpapers. Returns an EMPTY list when every
 * candidate is recent so the caller can reset the no-repeat cycle explicitly.
 */
internal fun excludeRecentWallpapers(
    wallpapers: List<Wallpaper>,
    recentIds: Set<String>,
): List<Wallpaper> {
    if (recentIds.isEmpty()) return wallpapers
    return wallpapers.filter { it.stableKey() !in recentIds }
}

internal fun queryLocalFolderWallpapers(
    context: Context,
    folderUriString: String,
): List<Wallpaper> {
    if (folderUriString.isBlank()) return emptyList()
    val treeUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: return emptyList()
    val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        .getOrNull()
        ?: return emptyList()
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    return runCatching {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val wallpapers = mutableListOf<Wallpaper>()
            while (cursor.moveToNext()) {
                val documentId = cursor.stringAt(documentIdIndex).takeUnless { it.isBlank() } ?: continue
                val displayName = cursor.stringAt(displayNameIndex)
                val mimeType = cursor.stringAt(mimeTypeIndex)
                if (!isLocalWallpaperMimeType(displayName, mimeType)) continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
                wallpapers += Wallpaper(
                    id = documentUri,
                    source = ContentSource.LOCAL,
                    thumbnailUrl = documentUri,
                    fullUrl = documentUri,
                    width = 0,
                    height = 0,
                    fileSize = cursor.longAt(sizeIndex),
                    fileType = mimeType,
                    sourcePageUrl = folderUriString,
                    license = "Local User Content",
                    uploaderName = "Local folder",
                )
            }
            wallpapers.sortedWith(compareBy<Wallpaper, String>(String.CASE_INSENSITIVE_ORDER) { it.localSortName() })
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

internal fun isLocalWallpaperMimeType(
    displayName: String?,
    mimeType: String?,
): Boolean {
    val normalizedMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    if (normalizedMime == "vnd.android.document/directory") return false
    if (normalizedMime.startsWith("image/")) return true
    return displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif")
}

private fun android.database.Cursor.stringAt(index: Int): String =
    if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

private fun android.database.Cursor.longAt(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index).coerceAtLeast(0L) else 0L

private fun Wallpaper.localSortName(): String =
    fullUrl.substringAfterLast('/').ifBlank { id }
