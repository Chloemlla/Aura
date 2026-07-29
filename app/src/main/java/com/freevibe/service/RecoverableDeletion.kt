package com.freevibe.service

import android.content.Context
import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.WallpaperCollectionEntity
import com.freevibe.data.model.WallpaperCollectionItemEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How long a deleted download stays restorable before its bytes are released. */
const val DELETION_RETENTION_MS: Long = 24L * 60L * 60L * 1000L

/** Most entries kept at once, so a bulk delete cannot fill the disk. */
const val DELETION_TRASH_MAX_ENTRIES = 100

/** A download that has been removed from the library but not yet destroyed. */
@JsonClass(generateAdapter = true)
data class TrashedDownload(
    val id: String,
    val source: String,
    val type: String,
    val name: String,
    /** Original locator; a `content://` URI is deleted only at purge time. */
    val localPath: String,
    val downloadedAt: Long,
    /** Where the managed local file now lives, or blank for non-file locators. */
    val stagedPath: String = "",
    val deletedAtMs: Long = 0L,
) {
    fun toEntity(): DownloadEntity = DownloadEntity(
        id = id,
        source = source,
        type = type,
        localPath = localPath,
        name = name,
        downloadedAt = downloadedAt,
    )
}

/** A collection and its items, captured so a delete can be undone in full. */
data class DeletedCollectionSnapshot(
    val collection: WallpaperCollectionEntity,
    val items: List<WallpaperCollectionItemEntity>,
    val deletedAtMs: Long,
)

/**
 * Staged trash for deleted downloads.
 *
 * Deleting a download used to destroy the file immediately even though removing
 * a single item from a collection already offered Undo. Deletion now moves the
 * managed file into a staging directory and records the row, so Undo can put
 * both back; only [purgeExpired] actually destroys anything, and only after
 * [DELETION_RETENTION_MS].
 *
 * Entries live in SharedPreferences as JSON: the trash must survive process
 * death for the retention window to mean anything, and it is far too small to
 * justify a Room table and a migration.
 */
@Singleton
class DownloadTrash @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter<List<TrashedDownload>>(
        Types.newParameterizedType(List::class.java, TrashedDownload::class.java),
    )

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Staging directory for files awaiting purge. */
    val stagingDir: File
        get() = File(context.filesDir, STAGING_DIR_NAME).also { it.mkdirs() }

    @Synchronized
    fun entries(): List<TrashedDownload> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    @Synchronized
    fun add(entry: TrashedDownload) {
        // Newest first, and bounded: the oldest entries are purged for real so a
        // bulk delete cannot grow the staging directory without limit.
        val existing = entries().filterNot { it.id == entry.id }
        val kept = (listOf(entry) + existing).take(DELETION_TRASH_MAX_ENTRIES)
        (existing + entry).minus(kept.toSet()).forEach(::destroyStagedFile)
        write(kept)
    }

    @Synchronized
    fun take(id: String): TrashedDownload? {
        val entries = entries()
        val entry = entries.firstOrNull { it.id == id } ?: return null
        write(entries.filterNot { it.id == id })
        return entry
    }

    /**
     * Destroys entries older than [retentionMs].
     *
     * @return the entries that were purged, so the caller can release their
     *   non-file locators (a `content://` row is deleted here, not at delete time).
     */
    @Synchronized
    fun purgeExpired(nowMs: Long, retentionMs: Long = DELETION_RETENTION_MS): List<TrashedDownload> {
        val entries = entries()
        val (expired, kept) = entries.partition { nowMs - it.deletedAtMs >= retentionMs }
        if (expired.isEmpty()) return emptyList()
        write(kept)
        expired.forEach(::destroyStagedFile)
        return expired
    }

    @Synchronized
    fun clear(): List<TrashedDownload> {
        val entries = entries()
        write(emptyList())
        entries.forEach(::destroyStagedFile)
        return entries
    }

    private fun destroyStagedFile(entry: TrashedDownload) {
        val staged = entry.stagedPath.takeIf { it.isNotBlank() } ?: return
        val file = File(staged)
        // Never step outside the staging directory, whatever the stored path says.
        val root = runCatching { stagingDir.canonicalPath }.getOrNull() ?: return
        val candidate = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (!candidate.startsWith(root + File.separator)) return
        runCatching { file.delete() }
    }

    private fun write(entries: List<TrashedDownload>) {
        prefs.edit().putString(KEY_ENTRIES, adapter.toJson(entries)).apply()
    }

    companion object {
        private const val PREFS_NAME = "freevibe_download_trash"
        private const val KEY_ENTRIES = "entries"
        internal const val STAGING_DIR_NAME = "download_trash"
    }
}
