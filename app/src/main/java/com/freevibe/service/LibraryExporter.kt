package com.freevibe.service

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.freevibe.data.local.FavoriteDao
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.local.SearchHistoryDao
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.SearchHistoryEntity
import com.freevibe.data.repository.CollectionRepository
import com.freevibe.util.rethrowIfCancelled
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_IMPORT_SIZE_CHARS = 10_000_000
private const val MAX_IMPORT_FAVORITES = 5_000
private const val MAX_IMPORT_COLLECTIONS = 100
private const val MAX_IMPORT_COLLECTION_ITEMS = 500
private const val MAX_IMPORT_SEARCHES = 200

@JsonClass(generateAdapter = true)
data class LibraryExportFile(
    val version: Int = LIBRARY_EXPORT_VERSION,
    val exportedAt: Long = 0,
    val favorites: List<FavoriteExportEntry> = emptyList(),
    val collections: List<CollectionExportEntry> = emptyList(),
    val searchHistory: List<SearchHistoryExportEntry> = emptyList(),
    val wallpaperPackJson: String = "",
    val soundProfilesJson: String = "",
)

/**
 * Only the fields needed to decide *whether* a payload can be restored, plus the
 * v1-only sections this build no longer writes.
 *
 * Parsed separately from [LibraryExportFile] because Moshi silently skips unknown
 * keys: without this, a v1 backup's `downloads` array would vanish with no way to
 * tell the user their downloads were not restored.
 */
@JsonClass(generateAdapter = true)
internal data class LibraryImportEnvelope(
    val version: Int? = null,
    val downloads: List<LegacyDownloadExportEntry> = emptyList(),
)

/** v1-only section. Download rows point at files on the exporting device. */
@JsonClass(generateAdapter = true)
data class LegacyDownloadExportEntry(
    val id: String = "",
    val name: String = "",
    val localPath: String = "",
)

@JsonClass(generateAdapter = true)
data class FavoriteExportEntry(
    val id: String,
    val source: String,
    val type: String,
    val thumbnailUrl: String = "",
    val fullUrl: String = "",
    val name: String = "",
    val addedAt: Long = 0,
)

@JsonClass(generateAdapter = true)
data class CollectionExportEntry(
    val id: Long,
    val name: String,
    val createdAt: Long = 0,
    val items: List<CollectionItemExportEntry> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CollectionItemExportEntry(
    val wallpaperId: String,
    val source: String = "",
    val thumbnailUrl: String = "",
    val fullUrl: String = "",
)

@JsonClass(generateAdapter = true)
data class SearchHistoryExportEntry(
    val query: String,
    val type: String = "",
    val searchedAt: Long = 0,
)

@Singleton
class LibraryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FreeVibeDatabase,
    private val favoriteDao: FavoriteDao,
    private val collectionRepo: CollectionRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val prefs: PreferencesManager,
    private val moshi: Moshi,
) {
    private val adapter = moshi.adapter(LibraryExportFile::class.java)
    private val envelopeAdapter = moshi.adapter(LibraryImportEnvelope::class.java)

    /**
     * Test seam: runs at the very end of the write transaction. Throwing from it
     * proves the whole import rolls back, which is the only way to test the
     * atomicity guarantee without corrupting a real database mid-write.
     */
    internal var failBeforeCommit: (suspend () -> Unit)? = null

    suspend fun exportLibrary(outputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val favorites = favoriteDao.getAll().first().map { it.toExportEntry() }
            val collections = exportCollections()
            val wallpaperSearches = searchHistoryDao.getRecent("WALLPAPER", 100).first()
            val soundSearches = searchHistoryDao.getRecent("SOUND", 100).first()
            val searchHistory = (wallpaperSearches + soundSearches).map { it.toExportEntry() }
            val wallpaperPack = prefs.wallpaperPackJson.first()
            val soundProfiles = prefs.soundProfilesJson.first()

            val exportFile = LibraryExportFile(
                version = LIBRARY_EXPORT_VERSION,
                exportedAt = System.currentTimeMillis(),
                favorites = favorites,
                collections = collections,
                searchHistory = searchHistory,
                wallpaperPackJson = wallpaperPack,
                soundProfilesJson = soundProfiles,
            )

            val json = adapter.indent("  ").toJson(exportFile)
            context.contentResolver.openOutputStream(outputUri)?.use { out ->
                out.write(json.toByteArray())
            } ?: throw IllegalStateException("Failed to open output stream")

            favorites.size + collections.size + searchHistory.size
        }.onFailure { it.rethrowIfCancelled() }
    }

    /**
     * Reads, version-checks, migrates, and validates a backup without writing
     * anything, so the caller can show the user exactly what a restore would do.
     */
    suspend fun planImport(inputUri: Uri): Result<LibraryImportPlan> = withContext(Dispatchers.IO) {
        runCatching { buildPlan(readPayload(inputUri)) }
            .onFailure { it.rethrowIfCancelled() }
    }

    /**
     * Restores a backup.
     *
     * The payload is fully parsed, migrated, validated, and planned before the
     * first write. The writes then run inside one Room transaction, with the two
     * DataStore-backed JSON blobs written first and restored on failure, so an
     * error anywhere leaves the pre-import state intact rather than a half-merged
     * library.
     */
    suspend fun importLibrary(inputUri: Uri): Result<LibraryImportOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val plan = buildPlan(readPayload(inputUri))
                applyPlan(plan)
                LibraryImportOutcome(
                    sourceVersion = plan.sourceVersion,
                    written = plan.writeCount,
                    skipped = plan.skipped,
                )
            }.onFailure { it.rethrowIfCancelled() }
        }

    private fun readPayload(inputUri: Uri): String =
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            val sb = StringBuilder()
            val buffer = CharArray(8192)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                sb.append(buffer, 0, read)
                if (sb.length > MAX_IMPORT_SIZE_CHARS) {
                    throw IllegalStateException("Import file too large (>${MAX_IMPORT_SIZE_CHARS / 1_000_000}MB)")
                }
            }
            sb.toString()
        } ?: throw IllegalStateException("Failed to open input stream")

    internal suspend fun buildPlan(json: String): LibraryImportPlan {
        val envelope = runCatching { envelopeAdapter.fromJson(json) }.getOrNull()
            ?: throw IllegalStateException("Invalid library backup format")
        val version = envelope.version
            ?: throw LibraryImportUnsupportedVersionException(
                "This file is missing a backup version and cannot be restored safely."
            )
        if (version > LIBRARY_EXPORT_VERSION) {
            throw LibraryImportUnsupportedVersionException(
                "This backup was written by a newer version of Aura (format $version). Update Aura and try again."
            )
        }
        if (version < LIBRARY_EXPORT_MIN_SUPPORTED_VERSION) {
            throw LibraryImportUnsupportedVersionException(
                "Backup format $version is no longer supported."
            )
        }

        val exportFile = adapter.fromJson(json)
            ?: throw IllegalStateException("Invalid library backup format")

        val skipped = mutableListOf<LibraryImportSkip>()

        // v1 -> v2: downloads were removed from the format because their localPath
        // rows only resolve on the exporting device. Report them instead of dropping
        // them silently.
        envelope.downloads.forEach { download ->
            skipped += LibraryImportSkip(
                section = "download",
                label = normalizeImportedText(download.name).ifBlank {
                    normalizeImportedText(download.id).ifBlank { "download" }
                },
                reason = LibraryImportSkipReason.DROPPED_BY_MIGRATION,
            )
        }

        val favorites = planFavorites(exportFile, skipped)
        val collections = planCollections(exportFile, skipped)
        val searchHistory = planSearchHistory(exportFile, skipped)

        return LibraryImportPlan(
            sourceVersion = version,
            favorites = favorites,
            collections = collections,
            searchHistory = searchHistory,
            wallpaperPackJson = exportFile.wallpaperPackJson,
            soundProfilesJson = exportFile.soundProfilesJson,
            skipped = skipped,
        )
    }

    private fun planFavorites(
        exportFile: LibraryExportFile,
        skipped: MutableList<LibraryImportSkip>,
    ): List<FavoriteEntity> {
        exportFile.favorites.drop(MAX_IMPORT_FAVORITES).forEach {
            skipped += LibraryImportSkip("favorite", it.label(), LibraryImportSkipReason.OVER_LIMIT)
        }
        return exportFile.favorites.take(MAX_IMPORT_FAVORITES).mapNotNull { entry ->
            val entity = entry.toEntity()
            if (entity != null) return@mapNotNull entity
            skipped += LibraryImportSkip(
                section = "favorite",
                label = entry.label(),
                reason = if (isNonPortableLocator(entry.fullUrl) || isNonPortableLocator(entry.thumbnailUrl)) {
                    LibraryImportSkipReason.NON_PORTABLE
                } else {
                    LibraryImportSkipReason.INVALID
                },
            )
            null
        }
    }

    private suspend fun planCollections(
        exportFile: LibraryExportFile,
        skipped: MutableList<LibraryImportSkip>,
    ): List<PlannedCollection> {
        exportFile.collections.drop(MAX_IMPORT_COLLECTIONS).forEach {
            skipped += LibraryImportSkip(
                "collection",
                normalizeImportedText(it.name).ifBlank { "collection" },
                LibraryImportSkipReason.OVER_LIMIT,
            )
        }
        // Merge by name so re-importing the same backup doesn't duplicate
        // collections (favorites already dedupe at the DAO layer).
        val existingByName = collectionRepo.getAll().first()
            .associateBy({ it.name }, { it.collectionId })
        val planned = mutableListOf<PlannedCollection>()
        exportFile.collections.take(MAX_IMPORT_COLLECTIONS).forEach { collection ->
            val name = normalizeImportedText(collection.name)
            if (name.isBlank()) {
                skipped += LibraryImportSkip("collection", "(unnamed)", LibraryImportSkipReason.INVALID)
                return@forEach
            }
            val existingId = existingByName[name]
            val existingItemIds = existingId
                ?.let { collectionRepo.getItems(it).first().map { item -> item.wallpaperId }.toSet() }
                .orEmpty()

            collection.items.drop(MAX_IMPORT_COLLECTION_ITEMS).forEach {
                skipped += LibraryImportSkip(
                    "collectionItem",
                    "$name / ${it.label()}",
                    LibraryImportSkipReason.OVER_LIMIT,
                )
            }
            val seen = existingItemIds.toMutableSet()
            val items = collection.items.take(MAX_IMPORT_COLLECTION_ITEMS).mapNotNull { item ->
                val wallpaper = item.toWallpaperOrNull()
                if (wallpaper == null) {
                    skipped += LibraryImportSkip(
                        section = "collectionItem",
                        label = "$name / ${item.label()}",
                        reason = if (isNonPortableLocator(item.fullUrl) || isNonPortableLocator(item.thumbnailUrl)) {
                            LibraryImportSkipReason.NON_PORTABLE
                        } else {
                            LibraryImportSkipReason.INVALID
                        },
                    )
                    return@mapNotNull null
                }
                if (!seen.add(wallpaper.id)) {
                    skipped += LibraryImportSkip(
                        "collectionItem",
                        "$name / ${item.label()}",
                        LibraryImportSkipReason.DUPLICATE,
                    )
                    return@mapNotNull null
                }
                wallpaper
            }
            planned += PlannedCollection(name = name, existingId = existingId, items = items)
        }
        return planned
    }

    private fun planSearchHistory(
        exportFile: LibraryExportFile,
        skipped: MutableList<LibraryImportSkip>,
    ): List<SearchHistoryEntity> {
        exportFile.searchHistory.drop(MAX_IMPORT_SEARCHES).forEach {
            skipped += LibraryImportSkip(
                "search",
                normalizeImportedText(it.query).ifBlank { "(blank)" },
                LibraryImportSkipReason.OVER_LIMIT,
            )
        }
        return exportFile.searchHistory.take(MAX_IMPORT_SEARCHES).mapNotNull { entry ->
            val query = normalizeImportedText(entry.query)
            if (query.isBlank()) {
                skipped += LibraryImportSkip("search", "(blank)", LibraryImportSkipReason.INVALID)
                return@mapNotNull null
            }
            SearchHistoryEntity(
                query = query,
                type = normalizeImportedText(entry.type),
                timestamp = entry.searchedAt,
            )
        }
    }

    /**
     * Replays a plan. No validation or conflict decisions happen here — by this
     * point every write is already decided, which is what makes the transaction
     * safe to roll back wholesale.
     */
    internal suspend fun applyPlan(plan: LibraryImportPlan) {
        // DataStore has no transaction, so its two values are written first and
        // restored by hand if the database transaction fails. Doing it in this
        // order means a database failure can never leave preferences ahead of the
        // library they describe.
        val previousPack = prefs.wallpaperPackJson.first()
        val previousProfiles = prefs.soundProfilesJson.first()
        var prefsWritten = false
        try {
            if (plan.wallpaperPackJson.isNotBlank()) {
                prefs.setWallpaperPackJson(plan.wallpaperPackJson)
                prefsWritten = true
            }
            if (plan.soundProfilesJson.isNotBlank()) {
                prefs.setSoundProfilesJson(plan.soundProfilesJson)
                prefsWritten = true
            }
            database.withTransaction {
                if (plan.favorites.isNotEmpty()) {
                    favoriteDao.insertAll(plan.favorites)
                }
                plan.collections.forEach { collection ->
                    val targetId = collection.existingId ?: collectionRepo.create(collection.name)
                    collection.items.forEach { collectionRepo.addWallpaper(targetId, it) }
                }
                plan.searchHistory.forEach { searchHistoryDao.insert(it) }
                failBeforeCommit?.invoke()
            }
        } catch (error: Throwable) {
            if (prefsWritten) {
                runCatching {
                    prefs.setWallpaperPackJson(previousPack)
                    prefs.setSoundProfilesJson(previousProfiles)
                }
            }
            throw error
        }
    }

    private suspend fun exportCollections(): List<CollectionExportEntry> {
        val collections = collectionRepo.getAll().first()
        return collections.map { collection ->
            val items = collectionRepo.getItems(collection.collectionId).first()
            CollectionExportEntry(
                id = collection.collectionId,
                name = collection.name,
                createdAt = collection.createdAt,
                items = items.map { item ->
                    CollectionItemExportEntry(
                        wallpaperId = item.wallpaperId,
                        source = item.source,
                        thumbnailUrl = item.thumbnailUrl,
                        fullUrl = item.fullUrl,
                    )
                },
            )
        }
    }
}

private fun FavoriteExportEntry.label(): String =
    normalizeImportedText(name).ifBlank { normalizeImportedText(id).ifBlank { "(unnamed)" } }

private fun CollectionItemExportEntry.label(): String =
    normalizeImportedText(wallpaperId).ifBlank { "(unnamed)" }

private fun FavoriteEntity.toExportEntry() = FavoriteExportEntry(
    id = id,
    source = source,
    type = type,
    thumbnailUrl = thumbnailUrl,
    fullUrl = fullUrl,
    name = name,
    addedAt = addedAt,
)

private fun SearchHistoryEntity.toExportEntry() = SearchHistoryExportEntry(
    query = query,
    type = type,
    searchedAt = timestamp,
)

/**
 * Validated import mapping — mirrors FavoritesExporter.toValidatedEntity: enum-checked
 * source, https-only URLs, bounded text. Unvalidated rows are dropped, not persisted;
 * the caller records why in the import plan.
 */
private fun FavoriteExportEntry.toEntity(): FavoriteEntity? {
    val normalizedId = normalizeImportedText(id)
    val normalizedSource = normalizeImportedContentSource(source) ?: return null
    val normalizedType = type.trim().uppercase(java.util.Locale.ROOT)
    if (normalizedId.isBlank()) return null
    if (normalizedType !in setOf("WALLPAPER", "SOUND")) return null
    val normalizedThumbnailUrl = normalizeImportedHttpsUrl(
        thumbnailUrl,
        allowBlank = normalizedType == "SOUND",
    ) ?: return null
    val normalizedFullUrl = normalizeImportedHttpsUrl(fullUrl) ?: return null
    if (normalizedFullUrl.isBlank()) return null
    if (normalizedType == "WALLPAPER" && normalizedThumbnailUrl.isBlank()) return null
    return FavoriteEntity(
        id = normalizedId,
        source = normalizedSource,
        type = normalizedType,
        thumbnailUrl = normalizedThumbnailUrl,
        fullUrl = normalizedFullUrl,
        name = normalizeImportedText(name),
        addedAt = if (addedAt > 0) addedAt else System.currentTimeMillis(),
    )
}

private fun CollectionItemExportEntry.toWallpaperOrNull(): com.freevibe.data.model.Wallpaper? {
    val normalizedId = normalizeImportedText(wallpaperId)
    if (normalizedId.isBlank()) return null
    val normalizedSource = normalizeImportedContentSource(source) ?: return null
    val normalizedThumbnailUrl = normalizeImportedHttpsUrl(thumbnailUrl) ?: return null
    val normalizedFullUrl = normalizeImportedHttpsUrl(fullUrl) ?: return null
    if (normalizedThumbnailUrl.isBlank() || normalizedFullUrl.isBlank()) return null
    val contentSource = runCatching {
        com.freevibe.data.model.ContentSource.valueOf(normalizedSource)
    }.getOrNull() ?: return null
    return com.freevibe.data.model.Wallpaper(
        id = normalizedId,
        source = contentSource,
        thumbnailUrl = normalizedThumbnailUrl,
        fullUrl = normalizedFullUrl,
        width = 0,
        height = 0,
    )
}
