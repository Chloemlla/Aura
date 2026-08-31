package com.chloemlla.aura.data.repository

import com.chloemlla.aura.data.local.CollectionDao
import com.chloemlla.aura.data.local.DownloadDao
import com.chloemlla.aura.data.local.FavoriteDao
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.local.WallpaperCacheDao
import com.chloemlla.aura.data.local.WallpaperHistoryDao
import com.chloemlla.aura.service.parsePack
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * A place a managed generated asset can still be reached from.
 *
 * Aura writes generated PNGs into `filesDir/ai_wallpapers`, then lets the user
 * favourite them, add them to collections, apply them (recording history), pin
 * them to a day/night slot, and drop them into a 24H wallpaper pack. Deleting the
 * file while any of those still point at it turns a working wallpaper into a
 * broken card — and for the day/night slot or pack, into a rotation that silently
 * stops working.
 */
enum class GeneratedAssetReference {
    FAVORITE,
    COLLECTION_ITEM,
    HISTORY,
    WALLPAPER_CACHE,
    DOWNLOAD,
    DAY_NIGHT_SLOT,
    NIGHT_VARIANT,
    WALLPAPER_PACK,
}

/** Result of scanning the managed generated-asset directory. */
data class GeneratedAssetAudit(
    /** Files on disk that at least one store still points at. */
    val referencedFiles: Int = 0,
    /** Files on disk that nothing points at; safe to prune. */
    val unreferencedFiles: Int = 0,
    /**
     * Locators that a store still points at but whose file is gone. These are the
     * broken cards a user sees, and they are what "diagnose stale rows" means.
     */
    val staleReferences: Int = 0,
)

/**
 * In-memory answer to "is this managed file still referenced?", built in one pass.
 *
 * Constructed by [GeneratedAssetReferenceIndex.snapshotReferences]; membership
 * checks are plain set lookups, so looping over a whole directory does not re-hit
 * Room or DataStore once per file.
 */
class GeneratedReferenceSnapshot internal constructor(
    private val referencedLocators: Set<String>,
    private val referencedAssetIds: Set<String>,
) {
    /** True when at least one store still points at [file]. */
    fun isReferenced(file: File): Boolean {
        val assetId = file.nameWithoutExtension
        if (assetId.isNotBlank() && assetId in referencedAssetIds) return true
        return generatedLocatorSpellings(file.path).any { it in referencedLocators }
    }
}

/**
 * Answers "is this managed file still needed?" before anything deletes it.
 *
 * Every check is by locator, and a single file has more than one legal spelling
 * on disk: `java.io.File.toURI()` produces `file:/path`, `Uri.fromFile` produces
 * `file:///path`, and some rows persist the bare absolute path. All three are
 * checked so a reference cannot hide behind a spelling difference.
 */
@Singleton
class GeneratedAssetReferenceIndex @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val collectionDao: CollectionDao,
    private val historyDao: WallpaperHistoryDao,
    private val cacheDao: WallpaperCacheDao,
    private val downloadDao: DownloadDao,
    private val prefs: PreferencesManager,
) {

    /**
     * Every store that still points at [file].
     *
     * Callers that are themselves removing a reference (unfavourite, collection
     * removal) must delete their row *before* calling this, so their own row is
     * not counted as a survivor.
     */
    suspend fun referencesFor(file: File): Set<GeneratedAssetReference> {
        val locators = generatedLocatorSpellings(file.path).toList()
        val assetId = file.nameWithoutExtension
        val found = mutableSetOf<GeneratedAssetReference>()

        if (favoriteDao.countReferencingLocators(locators) > 0) {
            found += GeneratedAssetReference.FAVORITE
        }
        if (collectionDao.countReferencingLocators(locators) > 0) {
            found += GeneratedAssetReference.COLLECTION_ITEM
        }
        if (historyDao.countReferencingLocators(locators) > 0) {
            found += GeneratedAssetReference.HISTORY
        }
        if (cacheDao.countReferencingLocators(locators) > 0) {
            found += GeneratedAssetReference.WALLPAPER_CACHE
        }
        if (downloadDao.countReferencingLocators(locators) > 0) {
            found += GeneratedAssetReference.DOWNLOAD
        }

        // Day/night slots persist the wallpaper *id*, resolved through history at
        // apply time; a generated asset's id is its file stem.
        val darkSlot = prefs.darkModeWallpaperId.first()
        val lightSlot = prefs.lightModeWallpaperId.first()
        if (assetId.isNotBlank() && (darkSlot == assetId || lightSlot == assetId)) {
            found += GeneratedAssetReference.DAY_NIGHT_SLOT
        }

        val nightVariant = prefs.lastNightVariantWallpaperLocator.first()
        if (nightVariant.isNotBlank() && nightVariant in locators) {
            found += GeneratedAssetReference.NIGHT_VARIANT
        }

        if (packLocators().any { it in locators }) {
            found += GeneratedAssetReference.WALLPAPER_PACK
        }

        return found
    }

    /** True when nothing points at [file] any more, so deleting it is safe. */
    suspend fun isUnreferenced(file: File): Boolean = referencesFor(file).isEmpty()

    /**
     * Builds an in-memory snapshot of every reference that could protect one of
     * [files], using one batched pass over each store instead of the per-file
     * COUNT + DataStore reads. The returned [GeneratedReferenceSnapshot] answers
     * "is this file still referenced?" with plain set lookups.
     */
    suspend fun snapshotReferences(files: List<File>): GeneratedReferenceSnapshot {
        val referencedLocators = mutableSetOf<String>()
        fun addLocator(value: String) {
            if (value.isNotBlank() && isManagedGeneratedLocator(value)) {
                referencedLocators += value
            }
        }

        favoriteDao.getAll().first().forEach { favorite ->
            addLocator(favorite.fullUrl)
            addLocator(favorite.thumbnailUrl)
            addLocator(favorite.offlinePath)
        }
        downloadDao.getAll().first().forEach { download ->
            addLocator(download.localPath)
        }
        historyDao.getRecentSnapshot(REFERENCE_SNAPSHOT_HISTORY_LIMIT).forEach { history ->
            addLocator(history.fullUrl)
            addLocator(history.thumbnailUrl)
        }
        collectionDao.getAllCollections().first().forEach { collection ->
            collectionDao.getCollectionItems(collection.collectionId).first().forEach { item ->
                addLocator(item.fullUrl)
                addLocator(item.thumbnailUrl)
            }
        }
        // The cache stores a generated asset under its wallpaper id, which is the
        // file stem, so one batched id lookup covers every candidate file.
        val stems = files
            .map { it.nameWithoutExtension }
            .filter { it.isNotBlank() }
        if (stems.isNotEmpty()) {
            cacheDao.getByIds(stems).forEach { entry ->
                addLocator(entry.fullUrl)
                addLocator(entry.thumbnailUrl)
            }
        }

        addLocator(prefs.lastNightVariantWallpaperLocator.first())
        packLocators().forEach(::addLocator)

        val slotIds = setOfNotNull(
            prefs.darkModeWallpaperId.first().takeIf { it.isNotBlank() },
            prefs.lightModeWallpaperId.first().takeIf { it.isNotBlank() },
        )
        return GeneratedReferenceSnapshot(
            referencedLocators = referencedLocators,
            referencedAssetIds = slotIds,
        )
    }

    /**
     * Splits [files] into the ones still referenced and the ones safe to prune,
     * and counts references whose file no longer exists.
     */
    suspend fun audit(files: List<File>): GeneratedAssetAudit {
        val snapshot = snapshotReferences(files)
        var referenced = 0
        var unreferenced = 0
        files.forEach { file ->
            if (snapshot.isReferenced(file)) referenced++ else unreferenced++
        }
        return GeneratedAssetAudit(
            referencedFiles = referenced,
            unreferencedFiles = unreferenced,
            staleReferences = countStaleReferences(files),
        )
    }

    /**
     * Locators inside the managed directory that a store points at but that no
     * longer exist on disk. [presentFiles] is the current directory listing.
     */
    private suspend fun countStaleReferences(presentFiles: List<File>): Int {
        val present = presentFiles.flatMap { generatedLocatorSpellings(it.path) }.toSet()
        val slotIds = setOfNotNull(
            prefs.darkModeWallpaperId.first().takeIf { it.isNotBlank() },
            prefs.lightModeWallpaperId.first().takeIf { it.isNotBlank() },
        )
        var stale = 0
        val nightVariant = prefs.lastNightVariantWallpaperLocator.first()
        if (nightVariant.isNotBlank() && isManagedGeneratedLocator(nightVariant) && nightVariant !in present) {
            stale++
        }
        stale += packLocators().count { isManagedGeneratedLocator(it) && it !in present }
        stale += slotIds.count { id ->
            historyDao.countByWallpaperId(id) > 0 &&
                presentFiles.none { it.nameWithoutExtension == id }
        }
        return stale
    }

    private suspend fun packLocators(): List<String> =
        parsePack(prefs.wallpaperPackJson.first())
            ?.slots
            .orEmpty()
            .map { it.wallpaperUri }
            .filter { it.isNotBlank() }
}

/** Marker for the managed generated-wallpaper directory inside a locator. */
private const val GENERATED_DIR_MARKER = "/ai_wallpapers/"

/**
 * Upper bound for the history rows loaded into a reference snapshot. History is
 * already pruned to 100 rows by [com.chloemlla.aura.data.local.WallpaperHistoryDao],
 * so this is generous headroom, not a real ceiling on the user's history.
 */
private const val REFERENCE_SNAPSHOT_HISTORY_LIMIT = 10_000

/**
 * True when [locator] points inside Aura's managed generated-wallpaper directory.
 *
 * Deliberately conservative: anything the app did not generate — a `content://`
 * gallery pick, an external `file://` path, a remote URL — is never treated as
 * Aura-owned, so orphan cleanup can never delete a user's own file.
 */
internal fun isManagedGeneratedLocator(locator: String): Boolean {
    val normalized = locator.replace('\\', '/')
    val colon = normalized.indexOf(':')
    val scheme = if (colon > 0) normalized.substring(0, colon).lowercase(Locale.ROOT) else ""
    // Only a bare path or a file URI can be Aura-owned. Remote and provider-backed
    // schemes (content, http, https, anything else) are the user's, never ours.
    if (scheme.isNotEmpty() && scheme != "file") return false
    return normalized.contains(GENERATED_DIR_MARKER)
}

/**
 * Every spelling of [path] that could have been persisted for the same file.
 *
 * `File.toURI()` emits `file:/data/...`, `Uri.fromFile` emits `file:///data/...`,
 * and export/import round-trips have persisted the bare path. Built as plain
 * strings so this stays unit-testable without Robolectric.
 */
internal fun generatedLocatorSpellings(path: String): Set<String> {
    val normalized = path.replace('\\', '/')
    if (normalized.isBlank()) return emptySet()
    val absolute = if (normalized.startsWith("/")) normalized else "/$normalized"
    return setOf(
        normalized,
        "file:$absolute",
        "file://$absolute",
    )
}
