package com.chloemlla.aura.service

import android.content.Context
import androidx.work.ListenableWorker.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class BackgroundWorkReceipt(
    val lastSuccessUtc: String? = null,
    val lastFailureUtc: String? = null,
    val lastErrorClass: String? = null,
    val lastResult: String? = null,
    val lastDeferralReason: String? = null,
)

/**
 * Explicit worker outcome for receipts. The previous API took the obfuscated
 * `ListenableWorker.Result` subclass name (`javaClass.simpleName`), which R8
 * mangles in release builds and turned every success into a recorded failure
 * (AURA-G2-04).
 */
enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

/**
 * Maps a worker's returned [Result] to a receipt outcome.
 *
 * Deliberately uses the public `Result.success()/retry()/failure()` factories
 * (which return process-wide singletons) instead of `is Result.Success` — the
 * subclasses are @RestrictTo(LIBRARY_GROUP), which lint rejects, and inspecting
 * `javaClass` names is what AURA-G2-04 flagged as R8-unstable. Reference
 * equality against the factories is stable and lint-clean.
 */
internal fun Result.toWorkOutcome(): WorkOutcome = when (this) {
    Result.success() -> WorkOutcome.SUCCESS
    Result.retry() -> WorkOutcome.RETRY
    else -> WorkOutcome.FAILURE
}

@Singleton
class BackgroundWorkReceiptStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(uniqueWorkName: String): BackgroundWorkReceipt {
        val prefix = keyPrefix(uniqueWorkName)
        return BackgroundWorkReceipt(
            lastSuccessUtc = prefs.getString("${prefix}last_success_utc", null),
            lastFailureUtc = prefs.getString("${prefix}last_failure_utc", null),
            lastErrorClass = prefs.getString("${prefix}last_error_class", null),
            lastResult = prefs.getString("${prefix}last_result", null),
            lastDeferralReason = prefs.getString("${prefix}last_deferral_reason", null),
        )
    }

    fun recordSuccess(uniqueWorkName: String) {
        val prefix = keyPrefix(uniqueWorkName)
        prefs.edit()
            .putString("${prefix}last_success_utc", utcNow())
            .putString("${prefix}last_result", "success")
            .remove("${prefix}last_error_class")
            .remove("${prefix}last_deferral_reason")
            .apply()
    }

    fun recordRetry(
        uniqueWorkName: String,
        errorClass: String? = null,
        deferralReason: String,
    ) {
        val prefix = keyPrefix(uniqueWorkName)
        prefs.edit()
            .putString("${prefix}last_failure_utc", utcNow())
            .putString("${prefix}last_result", "retry")
            .putString("${prefix}last_deferral_reason", deferralReason)
            .apply {
                if (errorClass.isNullOrBlank()) remove("${prefix}last_error_class")
                else putString("${prefix}last_error_class", errorClass)
            }
            .apply()
    }

    fun recordFailure(
        uniqueWorkName: String,
        errorClass: String,
        deferralReason: String,
    ) {
        val prefix = keyPrefix(uniqueWorkName)
        prefs.edit()
            .putString("${prefix}last_failure_utc", utcNow())
            .putString("${prefix}last_result", "failure")
            .putString("${prefix}last_error_class", errorClass)
            .putString("${prefix}last_deferral_reason", deferralReason)
            .apply()
    }

    fun recordWorkerResult(
        uniqueWorkName: String,
        outcome: WorkOutcome,
        retryReason: String,
    ) {
        when (outcome) {
            WorkOutcome.SUCCESS -> recordSuccess(uniqueWorkName)
            WorkOutcome.RETRY -> recordRetry(
                uniqueWorkName = uniqueWorkName,
                deferralReason = retryReason,
            )
            WorkOutcome.FAILURE -> recordFailure(
                uniqueWorkName = uniqueWorkName,
                errorClass = "WorkerFailure",
                deferralReason = retryReason,
            )
        }
    }

    private fun keyPrefix(uniqueWorkName: String): String =
        uniqueWorkName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifBlank { "unknown" } + "."

    private fun utcNow(): String = checkNotNull(UTC_FORMAT.get()).format(Date())

    private companion object {
        const val PREFS_NAME = "background_work_receipts"
        val UTC_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
