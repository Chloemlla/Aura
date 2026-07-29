package com.freevibe.data.repository

import com.freevibe.data.local.CollectionDao
import com.freevibe.data.local.DownloadDao
import com.freevibe.data.local.FavoriteDao
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.local.WallpaperCacheDao
import com.freevibe.data.local.WallpaperHistoryDao
import com.freevibe.service.parsePack
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
     * Splits [files] into the ones still referenced and the ones safe to prune,
     * and counts references whose file no longer exists.
     */
    suspend fun audit(files: List<File>): GeneratedAssetAudit {
        var referenced = 0
        var unreferenced = 0
        files.forEach { file ->
            if (referencesFor(file).isEmpty()) unreferenced++ else referenced++
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
