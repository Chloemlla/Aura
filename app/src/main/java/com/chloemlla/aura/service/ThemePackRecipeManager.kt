package com.chloemlla.aura.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.ContentType
import com.chloemlla.aura.data.model.WallpaperHistoryEntity
import com.chloemlla.aura.data.model.providerNetworkPoliciesBySource
import com.chloemlla.aura.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val THEME_PACK_VERSION = 1
private const val THEME_PACK_MANIFEST_ENTRY = "theme-pack.json"
private const val THEME_PACK_MAX_MANIFEST_CHARS = 1_000_000
private const val THEME_PACK_MAX_ASSET_BYTES = 64L * 1024L * 1024L
private const val THEME_PACK_MAX_TOTAL_ASSET_BYTES = 128L * 1024L * 1024L
private const val THEME_PACK_MAX_ENTRIES = 512

/**
 * Bounds for the only untrusted archive Aura expands. A theme pack is one
 * manifest plus a handful of wallpaper/sound assets, so 512 entries is already
 * generous; anything past it is a malformed or hostile pack.
 */
internal val THEME_PACK_EXTRACTION_LIMITS = ArchiveExtractionLimits(
    maxEntries = THEME_PACK_MAX_ENTRIES,
    maxEntryBytes = THEME_PACK_MAX_ASSET_BYTES,
    maxTotalBytes = THEME_PACK_MAX_TOTAL_ASSET_BYTES,
    maxCompressionRatio = 200L,
)
private const val WIDGET_PREFS = "freevibe_widget"
private const val LIVE_WALLPAPER_PREFS = "freevibe_live_wp"

@Serializable
data class ThemePackRecipe(
    val version: Int = THEME_PACK_VERSION,
    val id: String,
    val name: String,
    val exportedAt: Long = 0L,
    val media: List<ThemePackMediaReference> = emptyList(),
    val videoWallpaper: ThemePackMediaReference? = null,
    val sounds: ThemePackSoundState = ThemePackSoundState(),
    val widget: ThemePackWidgetState = ThemePackWidgetState(),
    val shortcuts: List<ThemeShortcutRecipe> = emptyList(),
    val wallpaperPackJson: String = "",
    val soundProfilesJson: String = "",
    val assetPolicy: ThemePackAssetPolicy = ThemePackAssetPolicy(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class ThemePackMediaReference(
    val role: String,
    val label: String,
    val locator: String,
    val mimeType: String = "",
    val assetKey: String = "",
    val byteCount: Long = 0L,
    val requiresReselection: Boolean = false,
)

@Serializable
data class ThemePackSoundState(
    val ringtoneUri: String = "",
    val notificationUri: String = "",
    val alarmUri: String = "",
    val profileCount: Int = 0,
)

@Serializable
data class ThemePackWidgetState(
    val primaryTint: Int = 0,
    val accentTint: Int = 0,
    val dominantTint: Int = 0,
    val previewWallpaperUri: String = "",
    val shuffleCount: Int = 0,
)

@Serializable
data class ThemeShortcutRecipe(
    val id: String,
    val label: String,
    val action: String,
    val route: String,
    val supportedOnImport: Boolean = false,
    val manualInstruction: String,
)

@Serializable
data class ThemePackAssetPolicy(
    val maxAssetBytes: Long = THEME_PACK_MAX_ASSET_BYTES,
    val embeddedAssetCount: Int = 0,
    val skippedAssetCount: Int = 0,
    val note: String = "Remote URLs stay as references; readable local file/content assets are embedded up to the pack limits.",
)

data class ThemePackExportReport(
    val exportedItemCount: Int,
    val embeddedAssetCount: Int,
    val skippedAssetCount: Int,
)

data class ThemePackImportReport(
    val importedItemCount: Int,
    val instructions: List<String>,
)

internal val themePackJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

internal fun serializeThemePackRecipe(recipe: ThemePackRecipe): String =
    themePackJson.encodeToString(recipe)

internal fun parseThemePackRecipe(raw: String): ThemePackRecipe? =
    if (raw.isBlank()) null
    else runCatching { themePackJson.decodeFromString<ThemePackRecipe>(raw) }.getOrNull()

internal fun defaultThemeShortcutRecipes(): List<ThemeShortcutRecipe> = listOf(
    ThemeShortcutRecipe(
        id = "shuffle_wallpaper",
        label = "Shuffle wallpaper",
        action = TaskerActionReceiver.ACTION_SHUFFLE_NOW,
        route = "wallpapers",
        manualInstruction = "Long-press the Aura icon and pin Shuffle wallpaper if your launcher supports static shortcuts.",
    ),
    ThemeShortcutRecipe(
        id = "rotate_now",
        label = "Rotate now",
        action = TaskerActionReceiver.ACTION_ROTATE_NOW,
        route = "wallpapers",
        manualInstruction = "Long-press the Aura icon and pin Rotate now if your launcher supports static shortcuts.",
    ),
    ThemeShortcutRecipe(
        id = "search_wallpapers",
        label = "Search wallpapers",
        action = "com.chloemlla.aura.action.SEARCH",
        route = "wallpapers",
        manualInstruction = "Long-press the Aura icon and pin Search wallpapers if your launcher supports static shortcuts.",
    ),
    ThemeShortcutRecipe(
        id = "downloads",
        label = "Downloads",
        action = "com.chloemlla.aura.action.DOWNLOADS",
        route = "downloads",
        manualInstruction = "Long-press the Aura icon and pin Downloads if your launcher supports static shortcuts.",
    ),
)

internal fun remapWallpaperPackAssetLocators(
    raw: String,
    references: List<ThemePackMediaReference>,
    assetRemaps: Map<String, String>,
): String {
    val pack = parsePack(raw) ?: return raw
    val slots = pack.slots.map { slot ->
        val remapped = remappedLocator(
            locator = slot.wallpaperUri,
            references = references,
            assetRemaps = assetRemaps,
        )
        if (remapped == slot.wallpaperUri) slot else slot.copy(wallpaperUri = remapped)
    }
    return serializePack(pack.copy(slots = slots))
}

internal fun remapSoundProfileAssetLocators(
    raw: String,
    references: List<ThemePackMediaReference>,
    assetRemaps: Map<String, String>,
): String {
    val profiles = parseProfiles(raw)
    if (profiles.isEmpty()) return raw
    val remappedProfiles = profiles.map { profile ->
        profile.copy(
            ringtoneUri = remappedLocator(profile.ringtoneUri, references, assetRemaps),
            notificationUri = remappedLocator(profile.notificationUri, references, assetRemaps),
            alarmUri = remappedLocator(profile.alarmUri, references, assetRemaps),
        )
    }
    return serializeProfiles(remappedProfiles)
}

/**
 * Hosts a theme pack is allowed to point a wallpaper at.
 *
 * A pack is the one untrusted archive Aura expands, and the 24H pack worker hands
 * its slot locators straight to [WallpaperApplier.applyByLocator] every 15 minutes.
 * An unchecked locator therefore means "set any readable file on this device as the
 * lock screen" for a `file://` path, or "beacon this URL on a schedule" for a remote
 * one, with nothing on screen to hint at it (AURA-G2-06). Derived from the provider
 * registry so it cannot drift from the hosts Aura actually serves wallpapers from.
 */
private val THEME_PACK_WALLPAPER_HOST_SUFFIXES: Set<String> = setOf(
    ContentSource.WALLHAVEN,
    ContentSource.BING,
    ContentSource.WIKIMEDIA,
    ContentSource.INTERNET_ARCHIVE,
    ContentSource.NASA,
    ContentSource.PEXELS,
    ContentSource.PIXABAY,
    ContentSource.REDDIT,
    ContentSource.LEMMY,
).flatMap { providerNetworkPoliciesBySource[it]?.hostSuffixes.orEmpty() }.toSet()

/**
 * Returns [raw] when a theme pack may keep pointing a wallpaper at it, null otherwise.
 *
 * Accepts exactly two shapes: an `https` URL on an allow-listed provider host, and a
 * file that the import itself expanded under [importDir]. The video branch of
 * [ThemePackRecipeManager.importThemePack] has always held the second rule; this is
 * the wallpaper side of it.
 */
internal fun sanitizeWallpaperLocator(
    raw: String,
    importDir: File?,
    allowedHostSuffixes: Set<String> = THEME_PACK_WALLPAPER_HOST_SUFFIXES,
): String? {
    val locator = raw.trim()
    if (locator.isBlank()) return null
    if (locator.startsWith("https://", ignoreCase = true)) {
        val host = runCatching { URI(locator).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        val allowed = allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
        return if (allowed) locator else null
    }
    val root = importDir?.let { dir -> runCatching { dir.canonicalFile }.getOrNull() } ?: return null
    val path = when {
        locator.startsWith("file://", ignoreCase = true) -> runCatching { URI(locator).path }.getOrNull()
        locator.startsWith("/") -> locator
        else -> null
    } ?: return null
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val insideImportDir = file.path.startsWith(root.path + File.separator)
    return if (insideImportDir && file.isFile) locator else null
}

internal data class SanitizedWallpaperPack(
    val json: String,
    val droppedSlotCount: Int,
)

/** Drops every 24H pack slot whose locator [sanitizeWallpaperLocator] rejects. */
internal fun sanitizeWallpaperPackLocators(raw: String, importDir: File?): SanitizedWallpaperPack {
    val pack = parsePack(raw) ?: return SanitizedWallpaperPack(raw, droppedSlotCount = 0)
    val kept = pack.slots.filter { sanitizeWallpaperLocator(it.wallpaperUri, importDir) != null }
    if (kept.size == pack.slots.size) return SanitizedWallpaperPack(raw, droppedSlotCount = 0)
    return SanitizedWallpaperPack(
        json = serializePack(pack.copy(slots = kept)),
        droppedSlotCount = pack.slots.size - kept.size,
    )
}

internal fun themePackImportInstructions(
    recipe: ThemePackRecipe,
    assetRemaps: Map<String, String>,
    unsupportedWallpaperSourceHint: String? = null,
    enableWallpaperPackHint: String? = null,
    pendingSoundRecipeHint: String? = null,
): List<String> {
    val instructions = linkedSetOf<String>()
    unsupportedWallpaperSourceHint?.let { instructions += it }
    enableWallpaperPackHint?.let { instructions += it }
    pendingSoundRecipeHint?.let { instructions += it }
    recipe.shortcuts
        .filterNot { it.supportedOnImport }
        .mapTo(instructions) { it.manualInstruction }
    recipe.videoWallpaper?.let { video ->
        if (video.locator.isNotBlank() && video.assetKey !in assetRemaps) {
            instructions += "Re-select the video wallpaper if this pack came from another device; Android does not grant reusable access to missing local video files."
        }
    }
    if (recipe.widget != ThemePackWidgetState()) {
        instructions += "Add or resize the Aura widget from your launcher if it is not already on the home screen; imported tint metadata applies after the widget renders."
    }
    if (recipe.sounds != ThemePackSoundState() || recipe.soundProfilesJson.isNotBlank()) {
        instructions += "Grant Modify system settings before imported ringtone or sound-profile recipes can apply system sounds."
    }
    recipe.media
        .filter { it.requiresReselection && it.assetKey !in assetRemaps }
        .forEach { ref ->
            instructions += "Re-select ${ref.label.ifBlank { ref.role }} if Android cannot read its local URI on this device."
        }
    return instructions.toList()
}

private fun remappedLocator(
    locator: String,
    references: List<ThemePackMediaReference>,
    assetRemaps: Map<String, String>,
): String {
    if (locator.isBlank()) return locator
    val reference = references.firstOrNull { it.locator == locator && it.assetKey.isNotBlank() }
        ?: return locator
    return assetRemaps[reference.assetKey] ?: locator
}

@Singleton
class ThemePackRecipeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val wallpaperHistoryManager: WallpaperHistoryManager,
    private val soundApplier: SoundApplier,
) {
    /**
     * True while an import has stored ringtone recipes it deliberately did not apply
     * (AURA-G2-18). Settings shows the "apply" action only while this is true, so the
     * import's "you must apply these yourself" hint has somewhere to lead.
     */
    val hasPendingSoundRecipes: Flow<Boolean> = combine(
        prefs.pendingThemePackRingtoneUri,
        prefs.pendingThemePackNotificationUri,
        prefs.pendingThemePackAlarmUri,
    ) { ringtone, notification, alarm ->
        ringtone.isNotBlank() || notification.isNotBlank() || alarm.isNotBlank()
    }

    suspend fun exportThemePack(outputUri: Uri, name: String = "Aura theme pack"): Result<ThemePackExportReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = buildRecipeSnapshot(name)
                val preparedAssets = prepareAssets(snapshot.assetCandidates())
                val embeddedByRole = preparedAssets.associateBy { it.reference.role }
                val recipe = snapshot.withEmbeddedAssets(embeddedByRole)
                writeThemePack(outputUri, recipe, preparedAssets)
                ThemePackExportReport(
                    exportedItemCount = recipe.exportedItemCount(),
                    embeddedAssetCount = preparedAssets.size,
                    skippedAssetCount = snapshot.assetCandidates().size - preparedAssets.size,
                )
            }.onFailure { it.rethrowIfCancelled() }
        }

    suspend fun importThemePack(inputUri: Uri): Result<ThemePackImportReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val imported = readThemePack(inputUri)
                val recipe = imported.recipe
                if (recipe.version > THEME_PACK_VERSION) {
                    imported.importDir?.let { runCatching { it.deleteRecursively() } }
                    throw IllegalStateException("Theme pack version ${recipe.version} is not supported yet")
                }
                val references = recipe.allMediaReferences()
                var importedCount = 0
                var droppedWallpaperSlots = 0
                var wallpaperPackNeedsEnabling = false
                var pendingSoundRecipes = false

                if (recipe.wallpaperPackJson.isNotBlank()) {
                    val sanitized = sanitizeWallpaperPackLocators(
                        raw = remapWallpaperPackAssetLocators(
                            raw = recipe.wallpaperPackJson,
                            references = references,
                            assetRemaps = imported.assetsByKey,
                        ),
                        importDir = imported.importDir,
                    )
                    droppedWallpaperSlots = sanitized.droppedSlotCount
                    prefs.setWallpaperPackJson(sanitized.json)
                    prefs.setWallpaperPackLastAppliedDaypart("")
                    // Importing does not enable the pack or schedule its worker: that
                    // would start rewriting wallpapers the user never asked for, and it
                    // fights automatic rotation (AURA-G2-13). It only counts as restored
                    // when the pack is already on — otherwise it is a stored recipe
                    // waiting behind a switch (AURA-G2-19).
                    if (prefs.wallpaperPackEnabled.first()) {
                        importedCount++
                    } else {
                        wallpaperPackNeedsEnabling = true
                    }
                }

                if (recipe.soundProfilesJson.isNotBlank()) {
                    prefs.setSoundProfilesJson(
                        remapSoundProfileAssetLocators(
                            raw = recipe.soundProfilesJson,
                            references = references,
                            assetRemaps = imported.assetsByKey,
                        ),
                    )
                    prefs.setSoundProfileLastAppliedId("")
                    importedCount++
                }

                val sounds = recipe.sounds
                val ringtone = remappedLocator(sounds.ringtoneUri, references, imported.assetsByKey)
                val notification = remappedLocator(sounds.notificationUri, references, imported.assetsByKey)
                val alarm = remappedLocator(sounds.alarmUri, references, imported.assetsByKey)
                // Pending, never lastApplied*: those keys mean "Aura really did set this
                // system sound", and RingtoneRestorationWorker forces them back at every
                // boot. Writing them here changed the user's ringtone days after an
                // import they thought only touched wallpapers (AURA-G2-18). Nothing is
                // applied until applyPendingThemePackSounds() runs, so none of these
                // count as restored.
                if (ringtone.isNotBlank()) {
                    prefs.setPendingThemePackRingtoneUri(ringtone)
                    pendingSoundRecipes = true
                }
                if (notification.isNotBlank()) {
                    prefs.setPendingThemePackNotificationUri(notification)
                    pendingSoundRecipes = true
                }
                if (alarm.isNotBlank()) {
                    prefs.setPendingThemePackAlarmUri(alarm)
                    pendingSoundRecipes = true
                }

                recipe.videoWallpaper?.let { video ->
                    val resolvedVideo = video.assetKey.takeIf { it.isNotBlank() }
                        ?.let(imported.assetsByKey::get)
                    if (!resolvedVideo.isNullOrBlank()) {
                        context.getSharedPreferences(LIVE_WALLPAPER_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString("video_path", resolvedVideo)
                            .putString("scale_mode", VIDEO_WALLPAPER_SCALE_MODE_ZOOM)
                            .apply()
                        importedCount++
                    }
                }

                if (recipe.widget != ThemePackWidgetState()) {
                    importWidget(recipe.widget)
                    importedCount++
                }

                pruneStaleImportDirs(current = imported.importDir)

                ThemePackImportReport(
                    importedItemCount = importedCount,
                    instructions = themePackImportInstructions(
                        recipe = recipe,
                        assetRemaps = imported.assetsByKey,
                        unsupportedWallpaperSourceHint = if (droppedWallpaperSlots > 0) {
                            context.getString(R.string.theme_pack_import_unsupported_wallpaper_source)
                        } else {
                            null
                        },
                        enableWallpaperPackHint = if (wallpaperPackNeedsEnabling) {
                            context.getString(R.string.theme_pack_import_enable_wallpaper_pack)
                        } else {
                            null
                        },
                        pendingSoundRecipeHint = if (pendingSoundRecipes) {
                            context.getString(R.string.theme_pack_import_pending_sounds)
                        } else {
                            null
                        },
                    ),
                )
            }.onFailure { it.rethrowIfCancelled() }
        }

    /**
     * Applies the sound recipe an import left pending, and returns how many landed.
     *
     * This is the only path that may write the `lastApplied*` keys for an imported
     * pack: they are what [RingtoneRestorationWorker] restores at boot, so they must
     * describe sounds Aura actually set, at a moment the user chose (AURA-G2-18).
     * Locators that are not files this import expanded are skipped — a `content://`
     * from the exporting device is not readable here.
     */
    suspend fun applyPendingThemePackSounds(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var applied = 0
            listOf(
                ContentType.RINGTONE to prefs.pendingThemePackRingtoneUri.first(),
                ContentType.NOTIFICATION to prefs.pendingThemePackNotificationUri.first(),
                ContentType.ALARM to prefs.pendingThemePackAlarmUri.first(),
            ).forEach { (type, locator) ->
                val file = stagedThemePackFile(locator) ?: return@forEach
                if (soundApplier.applyFromLocalFile(file.absolutePath, file.name, type).isSuccess) {
                    clearPendingThemePackSound(type)
                    applied++
                }
            }
            applied
        }.onFailure { it.rethrowIfCancelled() }
    }

    private fun stagedThemePackFile(locator: String): File? {
        val path = locator.trim().takeIf { it.startsWith("/") } ?: return null
        val root = runCatching { File(context.filesDir, "theme_packs").canonicalFile }.getOrNull()
            ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    private suspend fun clearPendingThemePackSound(type: ContentType) {
        when (type) {
            ContentType.RINGTONE -> prefs.setPendingThemePackRingtoneUri("")
            ContentType.NOTIFICATION -> prefs.setPendingThemePackNotificationUri("")
            ContentType.ALARM -> prefs.setPendingThemePackAlarmUri("")
            else -> Unit
        }
    }

    /**
     * Deletes previous `theme_packs/import-*` directories that are no longer referenced
     * by any imported configuration. Without this, every import permanently orphans the
     * previous import's assets (nothing else — including Settings "Clear cache", which
     * only touches cacheDir — ever reclaims them).
     */
    private suspend fun pruneStaleImportDirs(current: File?) {
        val root = File(context.filesDir, "theme_packs")
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("import-") } ?: return
        if (dirs.isEmpty()) return
        val referenced = buildString {
            appendLine(prefs.wallpaperPackJson.first())
            appendLine(prefs.soundProfilesJson.first())
            appendLine(prefs.lastAppliedRingtoneUri.first())
            appendLine(prefs.lastAppliedNotificationUri.first())
            appendLine(prefs.lastAppliedAlarmUri.first())
            // Pending sound recipes still point into their import dir; deleting it
            // would leave "apply theme pack sounds" with nothing to apply.
            appendLine(prefs.pendingThemePackRingtoneUri.first())
            appendLine(prefs.pendingThemePackNotificationUri.first())
            appendLine(prefs.pendingThemePackAlarmUri.first())
            appendLine(
                context.getSharedPreferences(LIVE_WALLPAPER_PREFS, Context.MODE_PRIVATE)
                    .getString("video_path", "").orEmpty(),
            )
            // Recent wallpaper-history locators: the global apply-undo flow re-applies
            // the previous wallpaper by locator, which may live in a prior import dir.
            wallpaperHistoryManager.getRecent(limit = 10).first().forEach { entry ->
                appendLine(entry.fullUrl)
                appendLine(entry.thumbnailUrl)
            }
        }
        dirs.forEach { dir ->
            if (current != null && dir.name == current.name) return@forEach
            if (!referenced.contains(dir.absolutePath)) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    private suspend fun buildRecipeSnapshot(name: String): ThemePackRecipe {
        val wallpaperPackJson = prefs.wallpaperPackJson.first()
        val soundProfilesJson = prefs.soundProfilesJson.first()
        val recentWallpaper = wallpaperHistoryManager.mostRecent().first()
        val videoPrefs = context.getSharedPreferences(LIVE_WALLPAPER_PREFS, Context.MODE_PRIVATE)
        val videoPath = videoPrefs.getString("video_path", "").orEmpty()
        val soundState = ThemePackSoundState(
            ringtoneUri = prefs.lastAppliedRingtoneUri.first(),
            notificationUri = prefs.lastAppliedNotificationUri.first(),
            alarmUri = prefs.lastAppliedAlarmUri.first(),
            profileCount = parseProfiles(soundProfilesJson).size,
        )
        val wallpaperRefs = buildWallpaperReferences(recentWallpaper, wallpaperPackJson)
        val soundRefs = buildSoundReferences(soundState, soundProfilesJson)
        val videoRef = videoPath.takeIf { it.isNotBlank() }?.let {
            mediaReference(
                role = "video_wallpaper",
                label = "Video wallpaper",
                locator = it,
                mimeType = "video/*",
            )
        }

        return ThemePackRecipe(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Aura theme pack" },
            exportedAt = System.currentTimeMillis(),
            media = wallpaperRefs + soundRefs,
            videoWallpaper = videoRef,
            sounds = soundState,
            widget = exportWidget(recentWallpaper),
            shortcuts = defaultThemeShortcutRecipes(),
            wallpaperPackJson = wallpaperPackJson,
            soundProfilesJson = soundProfilesJson,
            notes = listOf(
                "Theme packs are local files. Remote provider URLs remain references.",
                "Launcher shortcuts and widgets may require manual launcher steps after import.",
            ),
        )
    }

    private fun buildWallpaperReferences(
        recentWallpaper: WallpaperHistoryEntity?,
        wallpaperPackJson: String,
    ): List<ThemePackMediaReference> {
        val refs = mutableListOf<ThemePackMediaReference>()
        recentWallpaper?.fullUrl?.takeIf { it.isNotBlank() }?.let {
            refs += mediaReference(
                role = "current_wallpaper",
                label = "Current wallpaper",
                locator = it,
                mimeType = "image/*",
            )
        }
        parsePack(wallpaperPackJson)?.slots.orEmpty().forEach { slot ->
            if (slot.wallpaperUri.isNotBlank()) {
                refs += mediaReference(
                    role = "wallpaper_pack_${slot.daypart.name.lowercase(Locale.ROOT)}",
                    label = slot.label.ifBlank { "${slot.daypart.displayName} wallpaper" },
                    locator = slot.wallpaperUri,
                    mimeType = "image/*",
                )
            }
        }
        return refs.distinctBy { it.role to it.locator }
    }

    private fun buildSoundReferences(
        soundState: ThemePackSoundState,
        soundProfilesJson: String,
    ): List<ThemePackMediaReference> {
        val refs = mutableListOf<ThemePackMediaReference>()
        listOf(
            "ringtone" to soundState.ringtoneUri,
            "notification" to soundState.notificationUri,
            "alarm" to soundState.alarmUri,
        ).forEach { (role, uri) ->
            if (uri.isNotBlank()) {
                refs += mediaReference(
                    role = "sound_$role",
                    label = role.replaceFirstChar { it.titlecase(Locale.ROOT) },
                    locator = uri,
                    mimeType = "audio/*",
                )
            }
        }
        parseProfiles(soundProfilesJson).forEach { profile ->
            listOf(
                "ringtone" to profile.ringtoneUri,
                "notification" to profile.notificationUri,
                "alarm" to profile.alarmUri,
            ).forEach { (kind, uri) ->
                if (uri.isNotBlank()) {
                    refs += mediaReference(
                        role = "sound_profile_${profile.id}_$kind",
                        label = "${profile.name} $kind",
                        locator = uri,
                        mimeType = "audio/*",
                    )
                }
            }
        }
        return refs.distinctBy { it.role to it.locator }
    }

    private fun mediaReference(
        role: String,
        label: String,
        locator: String,
        mimeType: String,
    ): ThemePackMediaReference {
        val local = isLocalLocator(locator)
        return ThemePackMediaReference(
            role = role,
            label = label,
            locator = locator,
            mimeType = mimeType,
            requiresReselection = local,
        )
    }

    private fun exportWidget(recentWallpaper: WallpaperHistoryEntity?): ThemePackWidgetState {
        val widgetPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        return ThemePackWidgetState(
            primaryTint = widgetPrefs.getInt("tint_vibrant", 0),
            accentTint = widgetPrefs.getInt("tint_accent", 0)
                .takeIf { it != 0 } ?: widgetPrefs.getInt("tint_vibrant_light", 0),
            dominantTint = widgetPrefs.getInt("tint_dominant", 0),
            previewWallpaperUri = recentWallpaper?.thumbnailUrl.orEmpty().ifBlank {
                recentWallpaper?.fullUrl.orEmpty()
            },
            shuffleCount = widgetPrefs.getInt("shuffle_count", 0),
        )
    }

    private fun importWidget(widget: ThemePackWidgetState) {
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("tint_vibrant", widget.primaryTint)
            .putInt("tint_accent", widget.accentTint)
            .putInt("tint_dominant", widget.dominantTint)
            .putInt("shuffle_count", widget.shuffleCount.coerceAtLeast(0))
            .apply()
    }

    private fun writeThemePack(
        outputUri: Uri,
        recipe: ThemePackRecipe,
        preparedAssets: List<PreparedThemeAsset>,
    ) {
        try {
            context.contentResolver.openOutputStream(outputUri)?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    preparedAssets.forEach { asset ->
                        zip.putNextEntry(ZipEntry(asset.entryName))
                        asset.file.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                zip.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry(THEME_PACK_MANIFEST_ENTRY))
                    zip.write(serializeThemePackRecipe(recipe).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            } ?: throw IOException("Could not open theme pack output")
        } catch (e: Throwable) {
            // Don't leave a truncated pack at the user's chosen location.
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, outputUri)
            }
            throw e
        } finally {
            // Temp copies (up to 128 MB) must go regardless of export outcome.
            preparedAssets.forEach { runCatching { it.file.delete() } }
        }
    }

    private fun readThemePack(inputUri: Uri): ImportedThemePack {
        val input = context.contentResolver.openInputStream(inputUri)
            ?: throw IOException("Could not open theme pack")
        input.buffered().use { stream ->
            stream.mark(4)
            val signature = ByteArray(4)
            val count = stream.read(signature)
            stream.reset()
            return if (count >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
                readZipThemePack(ZipInputStream(stream))
            } else {
                val json = stream.reader(Charsets.UTF_8).readTextCapped(THEME_PACK_MAX_MANIFEST_CHARS)
                ImportedThemePack(
                    recipe = parseThemePackRecipe(json)
                        ?: throw IllegalStateException("Invalid theme pack JSON"),
                    assetsByKey = emptyMap(),
                )
            }
        }
    }

    private fun readZipThemePack(zip: ZipInputStream): ImportedThemePack =
        extractThemePackArchive(
            zip = zip,
            importDir = File(context.filesDir, "theme_packs/import-${System.currentTimeMillis()}"),
        )

    private fun prepareAssets(references: List<ThemePackMediaReference>): List<PreparedThemeAsset> {
        val tempDir = File(context.cacheDir, "theme-pack-export").apply { mkdirs() }
        val prepared = mutableListOf<PreparedThemeAsset>()
        var totalBytes = 0L
        references.filter { isLocalLocator(it.locator) }.forEach { reference ->
            val remaining = THEME_PACK_MAX_TOTAL_ASSET_BYTES - totalBytes
            if (remaining <= 0) return@forEach
            val asset = runCatching {
                prepareAsset(reference, tempDir, remaining.coerceAtMost(THEME_PACK_MAX_ASSET_BYTES))
            }.getOrNull()
            if (asset != null) {
                totalBytes += asset.byteCount
                prepared += asset
            }
        }
        return prepared
    }

    private fun prepareAsset(
        reference: ThemePackMediaReference,
        tempDir: File,
        maxBytes: Long,
    ): PreparedThemeAsset? {
        val input = openLocalLocator(reference.locator) ?: return null
        val extension = extensionForLocator(reference.locator, reference.mimeType)
        val entryName = "assets/${safeFileStem(reference.role)}-${shortHash(reference.locator)}.$extension"
        val temp = File(tempDir, "${shortHash(entryName)}.tmp")
        var copied = 0L
        input.use { source ->
            temp.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    copied += read
                    if (copied > maxBytes) {
                        temp.delete()
                        return null
                    }
                    target.write(buffer, 0, read)
                }
            }
        }
        if (copied == 0L) {
            temp.delete()
            return null
        }
        return PreparedThemeAsset(
            reference = reference.copy(
                assetKey = entryName,
                byteCount = copied,
                requiresReselection = false,
            ),
            file = temp,
            entryName = entryName,
            byteCount = copied,
        )
    }

    private fun openLocalLocator(locator: String): java.io.InputStream? {
        val trimmed = locator.trim()
        if (trimmed.isBlank()) return null
        return when (schemeOf(trimmed)) {
            "content" -> context.contentResolver.openInputStream(Uri.parse(trimmed))
            "file" -> Uri.parse(trimmed).path?.let(::File)?.takeIf { it.isFile }?.inputStream()
            null -> File(trimmed).takeIf { it.isFile }?.inputStream()
            else -> null
        }
    }

    private fun ThemePackRecipe.assetCandidates(): List<ThemePackMediaReference> =
        allMediaReferences().filter { isLocalLocator(it.locator) }

    private fun ThemePackRecipe.withEmbeddedAssets(
        assetsByRole: Map<String, PreparedThemeAsset>,
    ): ThemePackRecipe {
        fun ThemePackMediaReference.withAsset(): ThemePackMediaReference =
            assetsByRole[role]?.reference ?: copy(assetKey = "", byteCount = 0L, requiresReselection = isLocalLocator(locator))
        val media = this.media.map { it.withAsset() }
        val video = videoWallpaper?.withAsset()
        val skipped = assetCandidates().size - assetsByRole.size
        return copy(
            media = media,
            videoWallpaper = video,
            assetPolicy = assetPolicy.copy(
                embeddedAssetCount = assetsByRole.size,
                skippedAssetCount = skipped.coerceAtLeast(0),
            ),
        )
    }

    private fun ThemePackRecipe.allMediaReferences(): List<ThemePackMediaReference> =
        media + listOfNotNull(videoWallpaper)

    private fun ThemePackRecipe.exportedItemCount(): Int =
        media.size +
            listOfNotNull(videoWallpaper).size +
            listOf(sounds.ringtoneUri, sounds.notificationUri, sounds.alarmUri).count { it.isNotBlank() } +
            parseProfiles(soundProfilesJson).size +
            parsePack(wallpaperPackJson)?.slots.orEmpty().size +
            shortcuts.size +
            if (widget != ThemePackWidgetState()) 1 else 0

    private fun isLocalLocator(locator: String): Boolean =
        when (schemeOf(locator)) {
            null, "content", "file" -> locator.isNotBlank()
            else -> false
        }

    private fun schemeOf(locator: String): String? {
        val colon = locator.indexOf(':')
        if (colon <= 0) return null
        return locator.substring(0, colon).lowercase(Locale.ROOT)
    }

    private fun extensionForLocator(locator: String, mimeType: String): String {
        val path = when (schemeOf(locator)) {
            "content" -> queryDisplayName(locator).orEmpty()
            "file" -> Uri.parse(locator).path.orEmpty()
            else -> locator
        }
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(8)
        if (extension.isNotBlank()) return extension
        return when {
            mimeType.startsWith("audio/") -> "audio"
            mimeType.startsWith("video/") -> "mp4"
            else -> "bin"
        }
    }

    private fun queryDisplayName(locator: String): String? =
        runCatching {
            context.contentResolver.query(
                Uri.parse(locator),
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun safeFileStem(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "asset" }
            .take(48)

    private fun shortHash(value: String): String = themePackShortHash(value)

    private data class PreparedThemeAsset(
        val reference: ThemePackMediaReference,
        val file: File,
        val entryName: String,
        val byteCount: Long,
    )

}

internal data class ImportedThemePack(
    val recipe: ThemePackRecipe,
    val assetsByKey: Map<String, String>,
    val importDir: File? = null,
)

/**
 * Expands a theme-pack zip into [importDir] under [THEME_PACK_EXTRACTION_LIMITS].
 *
 * Split out of [ThemePackRecipeManager] so the hostile-archive paths (traversal,
 * entry floods, oversize entries, zip bombs) can be exercised as plain JVM tests
 * against real archives instead of only through a device import.
 *
 * On any failure the whole staging directory is removed — a rejected pack must
 * not leave partially expanded assets behind.
 */
internal fun extractThemePackArchive(zip: ZipInputStream, importDir: File): ImportedThemePack {
    importDir.mkdirs()
    try {
        val assetsByKey = mutableMapOf<String, String>()
        var manifest: String? = null
        val guard = ArchiveExtractionGuard.newSession(THEME_PACK_EXTRACTION_LIMITS)
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            // Every entry counts, including directories and ignored names, so a pack
            // cannot burn the entry budget on entries the branches below skip.
            guard.beginEntry(name)
            when {
                entry.isDirectory -> Unit
                name == THEME_PACK_MANIFEST_ENTRY -> {
                    manifest = zip.reader(Charsets.UTF_8).readTextCapped(THEME_PACK_MAX_MANIFEST_CHARS)
                }
                name.startsWith("assets/") -> {
                    // Hash-prefix the flattened name: assets/a/x.png and assets/b/x.png
                    // must map to distinct files, not silently overwrite each other.
                    val safeName = "${themePackShortHash(name)}_${name.substringAfterLast('/').take(60)}"
                    val target = File(importDir, safeName)
                    val bytes = copyZipEntryCapped(zip, target, guard.remainingEntryBudget()) {
                        target.delete()
                        guard.failEntryTooLarge()
                    }
                    // ZipInputStream backfills the compressed size once the entry
                    // body (and any data descriptor) has been consumed.
                    guard.commitEntry(expandedBytes = bytes, compressedBytes = entry.compressedSize)
                    assetsByKey[name] = target.absolutePath
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        val recipe = parseThemePackRecipe(manifest.orEmpty())
            ?: throw IllegalStateException("Theme pack manifest missing or invalid")
        return ImportedThemePack(recipe = recipe, assetsByKey = assetsByKey, importDir = importDir)
    } catch (e: Throwable) {
        // A failed import (bad manifest, oversize assets, IO error) must not orphan
        // up to 128 MB of extracted assets in filesDir — nothing else cleans it.
        runCatching { importDir.deleteRecursively() }
        throw e
    }
}

private inline fun copyZipEntryCapped(
    zip: ZipInputStream,
    target: File,
    maxBytes: Long,
    onOverflow: () -> Nothing,
): Long {
    var copied = 0L
    target.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = zip.read(buffer)
            if (read == -1) break
            copied += read
            if (copied > maxBytes) onOverflow()
            output.write(buffer, 0, read)
        }
    }
    return copied
}

private fun java.io.Reader.readTextCapped(maxChars: Int): String {
    val builder = StringBuilder()
    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        builder.append(buffer, 0, read)
        if (builder.length > maxChars) {
            throw IOException("Theme pack manifest is too large")
        }
    }
    return builder.toString()
}

internal fun themePackShortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}
