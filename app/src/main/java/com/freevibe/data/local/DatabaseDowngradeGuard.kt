package com.freevibe.data.local

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What happened to the database the last time an older Aura opened it.
 *
 * Null fields mean no downgrade has ever been seen, which is the normal case.
 */
data class DatabaseDowngradeReceipt(
    val detectedUtc: String,
    val fromVersion: Int,
    val toVersion: Int,
    /** Where the pre-downgrade file was copied, or null when the copy failed. */
    val preservedPath: String? = null,
) {
    val dataWasPreserved: Boolean get() = preservedPath != null
}

/**
 * Survives installing an older Aura over a newer one.
 *
 * Room refuses to open a database whose on-disk schema version is ahead of the
 * one the running code knows, and it does so by throwing. Nothing caught that,
 * so an ordinary Obtainium rollback — or the `adb install -r` path the README
 * documents — left the app crashing on every launch, recoverable only by
 * clearing app data, which destroys favorites, collections, and history.
 *
 * Room's own answer is `fallbackToDestructiveMigrationOnDowngrade`, which opens
 * fine and wipes without telling anyone. That trades a visible crash for a
 * silent loss, which is worse. So before Room is allowed near the file, this
 * reads the on-disk version directly, and when it is ahead:
 *
 *  1. copies the database aside, so re-installing the newer Aura recovers
 *     everything rather than nothing;
 *  2. records a receipt the UI turns into an explicit warning.
 *
 * The version lives at a fixed offset in the SQLite header, so reading it needs
 * no SQLite connection — which matters, because opening a connection is the
 * thing that throws.
 */
object DatabaseDowngradeGuard {

    const val DATABASE_NAME = "freevibe.db"

    /** Suffix for the copy kept when a downgrade is about to reset the database. */
    const val PRESERVED_SUFFIX = ".pre-downgrade"

    /**
     * Byte offset of `user_version` in the SQLite file header, and the magic
     * string that identifies the file as SQLite at all.
     *
     * Both are fixed by the on-disk format, which is explicitly documented as
     * stable: https://www.sqlite.org/fileformat.html#the_database_header
     */
    private const val USER_VERSION_OFFSET = 60
    private const val HEADER_SIZE = 64
    /**
     * The 16-byte header string. It ends with NUL, not a space: a literal
     * written with a trailing space looks identical in a diff and matches no
     * real database. Escaped rather than embedded so it stays visible.
     */
    val SQLITE_MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** WAL and shared-memory sidecars; a copy without them can be stale. */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm")

    /**
     * Reads the schema version recorded in a SQLite file, or null when the file
     * is absent, too short, or not a SQLite database at all.
     */
    fun readOnDiskVersion(databaseFile: File): Int? {
        if (!databaseFile.isFile || databaseFile.length() < HEADER_SIZE) return null
        return try {
            databaseFile.inputStream().use { stream ->
                val header = ByteArray(HEADER_SIZE)
                var read = 0
                while (read < HEADER_SIZE) {
                    val count = stream.read(header, read, HEADER_SIZE - read)
                    if (count < 0) return null
                    read += count
                }
                if (!header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
                // Big-endian, as every multi-byte value in the header is.
                var version = 0
                for (index in USER_VERSION_OFFSET until USER_VERSION_OFFSET + 4) {
                    version = (version shl 8) or (header[index].toInt() and 0xFF)
                }
                version
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Checks the database before Room opens it and prepares for a downgrade if
     * one is happening.
     *
     * @return a receipt when the on-disk version is ahead of [currentVersion],
     *   null when the database is absent, current, or older (an ordinary upgrade,
     *   which the migration chain handles).
     */
    fun inspect(
        databaseFile: File,
        currentVersion: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): DatabaseDowngradeReceipt? {
        val onDisk = readOnDiskVersion(databaseFile) ?: return null
        if (onDisk <= currentVersion) return null
        val preserved = preserve(databaseFile)
        return DatabaseDowngradeReceipt(
            detectedUtc = formatUtc(nowMs),
            fromVersion = onDisk,
            toVersion = currentVersion,
            preservedPath = preserved?.absolutePath,
        )
    }

    /**
     * Copies the database and its sidecars aside.
     *
     * Copy rather than rename: if anything below fails, the original is still
     * where Room expects it and the app still starts. An older copy from a
     * previous downgrade is replaced, because the newest one is the only one
     * that can still be restored into a matching Aura.
     */
    private fun preserve(databaseFile: File): File? = try {
        val target = File(databaseFile.parentFile, databaseFile.name + PRESERVED_SUFFIX)
        databaseFile.copyTo(target, overwrite = true)
        for (suffix in SIDECAR_SUFFIXES) {
            val sidecar = File(databaseFile.parentFile, databaseFile.name + suffix)
            if (sidecar.isFile) {
                sidecar.copyTo(File(target.parentFile, target.name + suffix), overwrite = true)
            }
        }
        target
    } catch (_: Throwable) {
        // Out of space is the likely cause, and it must not stop the app from
        // starting. The receipt still records the downgrade, with no copy.
        null
    }

    private fun formatUtc(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timeMs))

    fun databaseFile(context: Context): File = context.getDatabasePath(DATABASE_NAME)
}
