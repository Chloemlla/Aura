package com.chloemlla.aura.service

import java.io.IOException
import java.util.Locale

/**
 * Bounds applied to every archive Aura expands from an untrusted source.
 *
 * Aura only ever extracts user-supplied zips (theme packs today), so the archive
 * surface is exactly as hostile as the file the user picked. Without explicit
 * bounds a crafted zip can:
 * - write outside the staging directory (`../../databases/aura.db`),
 * - materialise a symlink and have a later entry write through it,
 * - create millions of tiny entries until the filesystem or inode table gives up,
 * - expand a few kB into gigabytes (classic zip bomb / high compression ratio).
 *
 * [ArchiveExtractionGuard] is a pure-JVM state machine so every rejection path is
 * unit-testable without a device or a real archive.
 */
data class ArchiveExtractionLimits(
    /** Hard ceiling on entries examined, including skipped ones. */
    val maxEntries: Int,
    /** Hard ceiling on the expanded size of a single entry. */
    val maxEntryBytes: Long,
    /** Hard ceiling on the expanded size of the whole archive. */
    val maxTotalBytes: Long,
    /**
     * Hard ceiling on expanded/compressed for a single entry. Deflate tops out
     * near 1032:1 on real data, so anything past this is a bomb, not a file.
     */
    val maxCompressionRatio: Long,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxCompressionRatio > 0) { "maxCompressionRatio must be positive" }
    }
}

/** Why the guard refused an entry. Stable strings so tests and logs agree. */
object ArchiveRejectionReason {
    const val TOO_MANY_ENTRIES = "too_many_entries"
    const val PATH_TRAVERSAL = "path_traversal"
    const val ABSOLUTE_PATH = "absolute_path"
    const val EMPTY_NAME = "empty_name"
    const val LINK_ENTRY = "link_entry"
    const val ENTRY_TOO_LARGE = "entry_too_large"
    const val ARCHIVE_TOO_LARGE = "archive_too_large"
    const val COMPRESSION_RATIO = "compression_ratio"
}

/** Thrown when an archive violates [ArchiveExtractionLimits]. */
class ArchiveExtractionException(
    val reason: String,
    message: String,
) : IOException(message)

object ArchiveExtractionGuard {

    /**
     * True when [name] can only ever resolve inside the staging directory.
     *
     * Rejects absolute paths, Windows drive letters, UNC prefixes, backslash
     * separators (a zip is allowed to contain them; Android will not treat them
     * as separators, but a later `File(dir, name)` on another platform would),
     * and any `..` path segment. Callers still flatten the name before use —
     * this is the belt to that suspenders.
     */
    fun isSafeEntryName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ArchiveRejectionReason.EMPTY_NAME
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            return ArchiveRejectionReason.ABSOLUTE_PATH
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.length >= 2 && lower[1] == ':' && lower[0] in 'a'..'z') {
            return ArchiveRejectionReason.ABSOLUTE_PATH
        }
        if (trimmed.any { it.isISOControl() }) return ArchiveRejectionReason.PATH_TRAVERSAL
        val segments = trimmed.split('/', '\\')
        if (segments.any { it == ".." }) return ArchiveRejectionReason.PATH_TRAVERSAL
        return null
    }

    /**
     * Creates a per-archive session. One session tracks one extraction so entry
     * counts and expanded-byte totals cannot be reset by a crafted archive.
     */
    fun newSession(limits: ArchiveExtractionLimits): Session = Session(limits)

    class Session internal constructor(private val limits: ArchiveExtractionLimits) {
        var entryCount: Int = 0
            private set

        var totalExpandedBytes: Long = 0L
            private set

        /**
         * Registers the next archive entry.
         *
         * @param name raw entry name straight from the archive.
         * @param isLink true when the archive marks the entry as a symbolic or
         *   hard link. Aura never materialises links; an archive that asks for
         *   one is either broken or hostile, so the whole import fails.
         * @throws ArchiveExtractionException when the entry violates the limits.
         */
        fun beginEntry(name: String, isLink: Boolean = false) {
            entryCount++
            if (entryCount > limits.maxEntries) {
                throw ArchiveExtractionException(
                    ArchiveRejectionReason.TOO_MANY_ENTRIES,
                    "Archive has more than ${limits.maxEntries} entries",
                )
            }
            isSafeEntryName(name)?.let { reason ->
                throw ArchiveExtractionException(reason, "Unsafe archive entry name")
            }
            if (isLink) {
                throw ArchiveExtractionException(
                    ArchiveRejectionReason.LINK_ENTRY,
                    "Archive entries may not be links",
                )
            }
        }

        /**
         * Largest number of bytes the caller may still write for the current entry.
         * Callers must stop at this value and then call [failEntryTooLarge] if the
         * source still has data.
         */
        fun remainingEntryBudget(): Long =
            minOf(limits.maxEntryBytes, limits.maxTotalBytes - totalExpandedBytes).coerceAtLeast(0L)

        fun failEntryTooLarge(): Nothing {
            val reason = if (totalExpandedBytes >= limits.maxTotalBytes - limits.maxEntryBytes) {
                ArchiveRejectionReason.ARCHIVE_TOO_LARGE
            } else {
                ArchiveRejectionReason.ENTRY_TOO_LARGE
            }
            throw ArchiveExtractionException(reason, "Archive entry exceeds the import limit")
        }

        /**
         * Commits an entry that finished within budget.
         *
         * @param expandedBytes bytes actually written to disk.
         * @param compressedBytes compressed size reported by the archive, or a
         *   non-positive value when the archive did not declare one (streamed
         *   entries only publish it after the data descriptor is read).
         */
        fun commitEntry(expandedBytes: Long, compressedBytes: Long) {
            if (expandedBytes > limits.maxEntryBytes) {
                throw ArchiveExtractionException(
                    ArchiveRejectionReason.ENTRY_TOO_LARGE,
                    "Archive entry exceeds the per-entry import limit",
                )
            }
            totalExpandedBytes += expandedBytes
            if (totalExpandedBytes > limits.maxTotalBytes) {
                throw ArchiveExtractionException(
                    ArchiveRejectionReason.ARCHIVE_TOO_LARGE,
                    "Archive exceeds the total import limit",
                )
            }
            if (compressedBytes > 0L && expandedBytes / compressedBytes > limits.maxCompressionRatio) {
                throw ArchiveExtractionException(
                    ArchiveRejectionReason.COMPRESSION_RATIO,
                    "Archive entry expands more than ${limits.maxCompressionRatio}:1",
                )
            }
        }
    }
}
