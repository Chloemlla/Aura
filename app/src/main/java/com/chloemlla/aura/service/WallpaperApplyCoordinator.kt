package com.chloemlla.aura.service

import android.content.Context
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperHistoryEntity
import com.chloemlla.aura.data.model.WallpaperTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a caller wants persisted when a wallpaper apply succeeds.
 *
 * Browsing recorded history, undo, style learning, the night-variant locator, and
 * a feedback event, while the AI, editor, and crop screens called
 * [WallpaperApplier] straight and quietly skipped all of it — so a wallpaper
 * applied from the editor never appeared in history and could not be undone.
 * Every caller now declares one of these instead of hand-rolling side effects.
 */
data class WallpaperApplyPolicy(
    /** Record the apply in wallpaper history (which also refreshes the widget). */
    val recordHistory: Boolean,
    /** Remember the locator so the night-variant re-dim can find it. */
    val recordNightVariant: Boolean,
    /** Feed the local taste profile. */
    val recordStyleSignal: Boolean,
    /** Post a user-visible apply event with an Undo target. */
    val postFeedback: Boolean,
) {
    companion object {
        /** Browsing and applying a catalog wallpaper: everything. */
        val BROWSE = WallpaperApplyPolicy(
            recordHistory = true,
            recordNightVariant = true,
            recordStyleSignal = true,
            postFeedback = true,
        )

        /**
         * Output the user produced from a source wallpaper (editor, crop, AI).
         *
         * History and undo apply exactly as they do for browsing. Style learning
         * does not: the taste profile is about which catalog wallpapers the user
         * likes, and the source already contributed its signal when it was picked.
         */
        val DERIVED = WallpaperApplyPolicy(
            recordHistory = true,
            recordNightVariant = true,
            recordStyleSignal = false,
            postFeedback = true,
        )

        /**
         * Background rotation and scheduled applies: recorded, but silent — there
         * is nobody looking at a snackbar.
         */
        val BACKGROUND = WallpaperApplyPolicy(
            recordHistory = true,
            recordNightVariant = true,
            recordStyleSignal = false,
            postFeedback = false,
        )
    }
}

/** What the coordinator committed, so callers can drive their own UI from it. */
data class WallpaperApplyReceipt(
    val target: WallpaperTarget,
    val historyRecorded: Boolean,
    val undoTarget: WallpaperHistoryEntity?,
    val feedbackMessage: String?,
)

/**
 * The single commit point for a wallpaper apply.
 *
 * The rule it enforces: nothing is persisted until the system call actually
 * succeeded, and then each effect happens exactly once. A cancelled or failed
 * apply leaves no history row, no night-variant locator, no style signal, and no
 * success feedback.
 */
@Singleton
class WallpaperApplyCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val historyManager: WallpaperHistoryManager,
    private val applyFeedbackBus: ApplyFeedbackBus,
) {

    /**
     * Serialises apply-and-persist across the whole process.
     *
     * The periodic rotation, the one-shot trigger, and the 24H pack worker can all
     * be running at once; without this, two applies interleave and the history rows
     * land in completion order, so Undo restores a wallpaper that was never on
     * screen (AURA-G2-07).
     */
    private val applyMutex = Mutex()

    /**
     * Runs [perform] and commits the declared side effects on success only.
     *
     * @param wallpaper the item being applied. Null for output with no catalog
     *   identity, which skips history rather than inventing a row.
     * @param locator locator to remember for the night variant; defaults to the
     *   wallpaper's full URL.
     * @param nightVariantDarkenPercent dim level to remember alongside the locator,
     *   so a later night re-dim reproduces what is on screen.
     * @param onStyleSignal invoked only when the policy asks for it.
     */
    suspend fun apply(
        wallpaper: Wallpaper?,
        target: WallpaperTarget,
        policy: WallpaperApplyPolicy,
        locator: String? = null,
        nightVariantDarkenPercent: Int = 0,
        onStyleSignal: suspend (Wallpaper) -> Unit = {},
        perform: suspend () -> Result<Unit>,
    ): Result<WallpaperApplyReceipt> =
        // A CoroutineWorker is killed at 10 minutes, so waiting for the lock has to
        // be bounded: report a failure rather than being stopped mid-commit.
        withTimeoutOrNull(APPLY_LOCK_TIMEOUT_MS) {
            applyMutex.withLock {
                commit(
                    wallpaper = wallpaper,
                    target = target,
                    policy = policy,
                    locator = locator,
                    nightVariantDarkenPercent = nightVariantDarkenPercent,
                    onStyleSignal = onStyleSignal,
                    perform = perform,
                )
            }
        } ?: Result.failure(IllegalStateException("Wallpaper apply timed out waiting for another apply"))

    private suspend fun commit(
        wallpaper: Wallpaper?,
        target: WallpaperTarget,
        policy: WallpaperApplyPolicy,
        locator: String?,
        nightVariantDarkenPercent: Int,
        onStyleSignal: suspend (Wallpaper) -> Unit,
        perform: suspend () -> Result<Unit>,
    ): Result<WallpaperApplyReceipt> {
        val result = try {
            perform()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            // Nothing is committed for a failed apply: a false history row is worse
            // than no record, because Undo would restore a wallpaper never applied.
            return Result.failure(failure)
        }

        var historyRecorded = false
        var undoTarget: WallpaperHistoryEntity? = null
        if (policy.recordHistory && wallpaper != null) {
            historyManager.record(wallpaper, target)
            historyRecorded = true
            // Read after recording so the undo target is the wallpaper that was
            // active before this apply, not the one two hops back.
            undoTarget = historyManager.previousSnapshot()
        }

        val nightVariantLocator = locator ?: wallpaper?.fullUrl
        if (policy.recordNightVariant && !nightVariantLocator.isNullOrBlank()) {
            prefs.setLastNightVariantWallpaper(nightVariantLocator, target.name, nightVariantDarkenPercent)
        }

        if (policy.recordStyleSignal && wallpaper != null) {
            onStyleSignal(wallpaper)
        }

        val feedbackMessage = if (policy.postFeedback) {
            context.getString(
                R.string.apply_feedback_applied_to,
                context.getString(target.applyLabelRes()),
            )
        } else {
            null
        }
        if (feedbackMessage != null) {
            applyFeedbackBus.post(ApplyFeedbackEvent(message = feedbackMessage, undoTarget = undoTarget))
        }

        return Result.success(
            WallpaperApplyReceipt(
                target = target,
                historyRecorded = historyRecorded,
                undoTarget = undoTarget,
                feedbackMessage = feedbackMessage,
            ),
        )
    }

    private companion object {
        const val APPLY_LOCK_TIMEOUT_MS = 5L * 60L * 1000L
    }
}

/** Label resource for an apply target, shared by every apply surface. */
fun WallpaperTarget.applyLabelRes(): Int = when (this) {
    WallpaperTarget.HOME -> R.string.apply_target_home
    WallpaperTarget.LOCK -> R.string.apply_target_lock
    WallpaperTarget.BOTH -> R.string.apply_target_both
}
