package com.chloemlla.aura.service

import com.chloemlla.aura.data.model.FavoriteEntity
import com.chloemlla.aura.data.model.SearchHistoryEntity
import com.chloemlla.aura.data.model.Wallpaper
import java.net.URI
import java.util.Locale

/** Backup payload versions this build can restore. */
internal const val LIBRARY_EXPORT_MIN_SUPPORTED_VERSION = 1
internal const val LIBRARY_EXPORT_VERSION = 2

/** Why a row in a backup will not be written. */
enum class LibraryImportSkipReason {
    /** Failed validation: blank id, unknown source, malformed URL. */
    INVALID,

    /**
     * Points at a file on the exporting device (`file://`, `content://`, a bare
     * path). The bytes are not in the backup, so restoring the row would produce
     * a permanently broken card on this device.
     */
    NON_PORTABLE,

    /** Already present here; the existing row wins. */
    DUPLICATE,

    /** Past the per-section import ceiling. */
    OVER_LIMIT,

    /** The field existed in an older payload version and this build no longer restores it. */
    DROPPED_BY_MIGRATION,
}

/** One row that will not be written, and why. */
data class LibraryImportSkip(
    /** Section the row came from: `favorite`, `collection`, `collectionItem`, `search`, `download`. */
    val section: String,
    /** Short human-readable identity of the row, for the preview report. */
    val label: String,
    val reason: LibraryImportSkipReason,
)

/** A collection that will be created or merged into, with the items to add. */
data class PlannedCollection(
    val name: String,
    val existingId: Long?,
    val items: List<Wallpaper>,
)

/**
 * The complete, validated result of reading a backup — computed before anything
 * is written, so the write phase is a pure replay with no decisions left in it.
 */
data class LibraryImportPlan(
    val sourceVersion: Int,
    val favorites: List<FavoriteEntity> = emptyList(),
    val collections: List<PlannedCollection> = emptyList(),
    val searchHistory: List<SearchHistoryEntity> = emptyList(),
    val wallpaperPackJson: String = "",
    val soundProfilesJson: String = "",
    val skipped: List<LibraryImportSkip> = emptyList(),
) {
    /** Rows that will actually be written. */
    val writeCount: Int
        get() = favorites.size + collections.size + collections.sumOf { it.items.size } +
            searchHistory.size +
            (if (wallpaperPackJson.isNotBlank()) 1 else 0) +
            (if (soundProfilesJson.isNotBlank()) 1 else 0)

    /** Rows the user's backup contained but this device cannot restore. */
    val nonPortable: List<LibraryImportSkip>
        get() = skipped.filter { it.reason == LibraryImportSkipReason.NON_PORTABLE }
}

/** Outcome of a completed import, including the report the UI shows. */
data class LibraryImportOutcome(
    val sourceVersion: Int,
    val written: Int,
    val skipped: List<LibraryImportSkip>,
)

/** Raised when a payload cannot be restored at all. Message is user-facing. */
class LibraryImportUnsupportedVersionException(message: String) : IllegalStateException(message)

/**
 * True when a locator names something only the exporting device has.
 *
 * Backups carry metadata, not bytes, so a `file://` AI-generated wallpaper, a
 * `content://` gallery pick, or a bare path cannot survive a device transfer.
 * Before this existed such rows were dropped indistinguishably from corrupt ones,
 * which is why users saw silent losses after a restore.
 */
internal fun isNonPortableLocator(locator: String?): Boolean {
    val trimmed = locator.orEmpty().trim()
    if (trimmed.isEmpty()) return false
    val scheme = runCatching { URI(trimmed).scheme }.getOrNull()?.lowercase(Locale.ROOT)
    return when (scheme) {
        null -> true // bare filesystem path
        "https" -> false
        else -> true // file, content, and anything else device-scoped
    }
}
