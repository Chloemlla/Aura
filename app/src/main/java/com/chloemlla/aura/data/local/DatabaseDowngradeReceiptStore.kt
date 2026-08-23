package com.chloemlla.aura.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that a downgrade reset the library, until the user acknowledges it.
 *
 * The reset happens during database construction, long before any screen exists,
 * and the user's next move is usually to wonder where their favorites went. The
 * receipt outlives that gap: it survives process death, and it is cleared only
 * when someone has actually been shown it.
 */
@Singleton
class DatabaseDowngradeReceiptStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(receipt: DatabaseDowngradeReceipt) {
        prefs.edit()
            .putString(KEY_DETECTED_UTC, receipt.detectedUtc)
            .putInt(KEY_FROM_VERSION, receipt.fromVersion)
            .putInt(KEY_TO_VERSION, receipt.toVersion)
            .putString(KEY_PRESERVED_PATH, receipt.preservedPath)
            .apply()
    }

    fun read(): DatabaseDowngradeReceipt? {
        val detected = prefs.getString(KEY_DETECTED_UTC, null) ?: return null
        return DatabaseDowngradeReceipt(
            detectedUtc = detected,
            fromVersion = prefs.getInt(KEY_FROM_VERSION, 0),
            toVersion = prefs.getInt(KEY_TO_VERSION, 0),
            preservedPath = prefs.getString(KEY_PRESERVED_PATH, null),
        )
    }

    /** Called once the warning has been shown and dismissed, never before. */
    fun acknowledge() {
        prefs.edit()
            .remove(KEY_DETECTED_UTC)
            .remove(KEY_FROM_VERSION)
            .remove(KEY_TO_VERSION)
            .remove(KEY_PRESERVED_PATH)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "database_downgrade_receipt"
        const val KEY_DETECTED_UTC = "detected_utc"
        const val KEY_FROM_VERSION = "from_version"
        const val KEY_TO_VERSION = "to_version"
        const val KEY_PRESERVED_PATH = "preserved_path"
    }
}
