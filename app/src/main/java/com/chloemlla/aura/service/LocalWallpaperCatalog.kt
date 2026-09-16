package com.chloemlla.aura.service

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.LocalWallpaperEntity
import com.chloemlla.aura.data.model.LocalWallpaperFolderEntity
import com.chloemlla.aura.data.model.LocalWallpaperFolderScanStatus
import com.chloemlla.aura.data.model.LocalMediaStatus
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.WallpaperTarget
import com.chloemlla.aura.data.model.normalizeLocalWallpaperTags
import com.chloemlla.aura.data.model.needsLocalMediaRelink
import com.chloemlla.aura.data.model.stableLocalMediaId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SCAN_DEPTH = 8
private const val MAX_INDEXED_FILES = 10_000
private const val MAX_FOLDER_RELINK_BATCH = 500
private const val HASH_BUFFER_BYTES = 64 * 1024
private const val IMAGE_DIRECTORY_MIME = "vnd.android.document/directory"

data class LocalWallpaperFolderRepairResult(
    val relinkedCount: Int,
    val discoveredCount: Int,
    val remainingCount: Int,
    val limited: Boolean,
)

@Singleton
class LocalWallpaperCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FreeVibeDatabase,
    private val localMediaRelinkManager: LocalMediaRelinkManager,
) {
    private val folderDao get() = database.localWallpaperFolderDao()
    private val wallpaperDao get() = database.localWallpaperDao()

    val folders: Flow<List<LocalWallpaperFolderEntity>> = folderDao.observeAll()
    val items: Flow<List<LocalWallpaperEntity>> = wallpaperDao.observeAll()

    suspend fun addFolder(uriString: String, displayName: String? = null): Result<Int> =
        withContext(Dispatchers.IO) { addFolderInternal(uriString, displayName) }

    private suspend fun addFolderInternal(uriString: String, displayName: String? = null): Result<Int> {
        val uri = parseFolderUri(uriString)
            ?: return Result.failure(IllegalArgumentException("A SAF folder URI is required"))
        val normalizedUri = uri.toString()
        val existing = folderDao.get(normalizedUri)
        folderDao.upsert(
            existing?.copy(
                displayName = displayName?.trim().orEmpty().ifBlank { existing.displayName },
                scanStatus = existing.scanStatus,
            ) ?: LocalWallpaperFolderEntity(
                folderUri = normalizedUri,
                displayName = displayName?.trim().orEmpty().ifBlank { folderDisplayName(uri) },
            ),
        )
        return rescanFolderInternal(normalizedUri)
    }

    suspend fun migrateLegacyFolder(uriString: String): Result<Int>? {
        if (uriString.isBlank() || folderDao.get(uriString.trim()) != null) return null
        return addFolder(uriString)
    }

    suspend fun removeFolder(uriString: String) {
        val normalizedUri = parseFolderUri(uriString)?.toString() ?: return
        database.withTransaction {
            wallpaperDao.deleteByFolder(normalizedUri)
            val folder = folderDao.get(normalizedUri)
            if (folder != null) folderDao.delete(folder)
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(normalizedUri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun updateFolderTarget(uriString: String, target: WallpaperTarget) {
        parseFolderUri(uriString)?.toString()?.let { folderDao.updateTarget(it, target.name) }
    }

    suspend fun updateTags(documentUri: String, tags: String) {
        wallpaperDao.updateTags(documentUri, normalizeLocalWallpaperTags(tags))
    }

    suspend fun rescanAll(): List<Result<Int>> =
        folderDao.getAll().map { rescanFolder(it.folderUri) }

    suspend fun rescanFolder(uriString: String): Result<Int> =
        withContext(Dispatchers.IO) { rescanFolderInternal(uriString) }

    suspend fun repairFolder(
        oldFolderUriString: String,
        newFolderUriString: String,
    ): Result<LocalWallpaperFolderRepairResult> = withContext(Dispatchers.IO) {
        val oldUri = parseFolderUri(oldFolderUriString)
            ?: return@withContext Result.failure(IllegalArgumentException("The old folder URI is invalid"))
        val newUri = parseFolderUri(newFolderUriString)
            ?: return@withContext Result.failure(IllegalArgumentException("A SAF folder URI is required"))
        if (!hasPersistedReadPermission(newUri)) {
            return@withContext Result.failure(SecurityException("Folder permission was not retained"))
        }
        if (oldUri == newUri) {
            return@withContext rescanFolderInternal(newUri.toString()).map { count ->
                LocalWallpaperFolderRepairResult(0, count, 0, limited = false)
            }
        }
        val oldFolder = folderDao.get(oldUri.toString())
            ?: return@withContext Result.failure(IllegalArgumentException("The folder is not in the catalog"))
        val oldItems = wallpaperDao.getByFolder(oldUri.toString())
        val existingNewFolder = folderDao.get(newUri.toString())
        val existingNew = wallpaperDao.getByFolder(newUri.toString())
            .associateBy(LocalWallpaperEntity::documentUri)
        val scanToken = UUID.randomUUID().toString()
        return@withContext runCatching {
            val matchableOldItems = oldItems.take(MAX_FOLDER_RELINK_BATCH)
            val expectedSizes = matchableOldItems
                .map(LocalWallpaperEntity::sizeBytes)
                .filter { it > 0L }
                .toSet()
            val includesUnknownSize = matchableOldItems.any { it.sizeBytes <= 0L }
            val scan = scanTree(newUri, scanToken, existingNew) { candidateSize ->
                includesUnknownSize || candidateSize <= 0L || candidateSize in expectedSizes
            }
            if (scan.error.isNotBlank() && scan.items.isEmpty()) {
                throw IOException(scan.error)
            }
            val matches = matchLocalWallpaperFolderRepairCandidates(
                oldItems = oldItems,
                candidates = scan.items,
                maxItems = MAX_FOLDER_RELINK_BATCH,
                occupiedCandidates = existingNew,
            )
            val oldByCandidateUri = matches.associate { it.second.documentUri to it.first }
            val repairedItems = scan.items.map { candidate ->
                oldByCandidateUri[candidate.documentUri]?.let { previous ->
                    candidate.copy(
                        stableId = previous.stableLocalMediaId(),
                        tags = previous.tags,
                        addedAt = previous.addedAt,
                        isStandalone = false,
                    )
                } ?: candidate
            }
            val remainingCount = (oldItems.size - matches.size).coerceAtLeast(0)
            database.withTransaction {
                folderDao.upsert(
                    existingNewFolder?.copy(target = oldFolder.target) ?: LocalWallpaperFolderEntity(
                        folderUri = newUri.toString(),
                        displayName = folderDisplayName(newUri),
                        target = oldFolder.target,
                        addedAt = oldFolder.addedAt,
                    ),
                )
                wallpaperDao.upsertAll(repairedItems)
                matches.forEach { (previous, candidate) ->
                    val relinkCandidate = LocalMediaRelinkCandidate(
                        locator = candidate.documentUri,
                        sha256 = candidate.contentHash,
                        metadata = com.chloemlla.aura.data.model.MediaTechnicalMetadata(
                            mimeType = candidate.mimeType,
                            width = candidate.width,
                            height = candidate.height,
                            sizeBytes = candidate.sizeBytes,
                        ),
                    )
                    localMediaRelinkManager.relinkWallpaperAssociations(
                        stableId = previous.stableLocalMediaId(),
                        source = ContentSource.LOCAL.name,
                        oldLocator = previous.documentUri,
                        newLocator = candidate.documentUri,
                        candidate = relinkCandidate,
                    )
                    if (previous.documentUri != candidate.documentUri) {
                        wallpaperDao.deleteByDocumentUri(previous.documentUri)
                    }
                }
                folderDao.updateScanState(
                    folderUri = newUri.toString(),
                    lastScannedAt = System.currentTimeMillis(),
                    scanStatus = when {
                        scan.error.isNotBlank() -> LocalWallpaperFolderScanStatus.READY_PARTIAL
                        scan.limited -> LocalWallpaperFolderScanStatus.READY_LIMITED
                        else -> LocalWallpaperFolderScanStatus.READY
                    },
                    lastError = scan.error,
                    itemCount = repairedItems.size,
                )
                if (remainingCount == 0) {
                    folderDao.delete(oldFolder)
                } else {
                    folderDao.updateScanState(
                        folderUri = oldFolder.folderUri,
                        lastScannedAt = System.currentTimeMillis(),
                        scanStatus = LocalWallpaperFolderScanStatus.PERMISSION_REVOKED,
                        lastError = "$remainingCount items still need relinking",
                        itemCount = remainingCount,
                    )
                }
            }
            matches.forEach { (previous, candidate) ->
                localMediaRelinkManager.relinkThemeSlots(
                    stableId = previous.stableLocalMediaId(),
                    source = ContentSource.LOCAL.name,
                    oldLocator = previous.documentUri,
                    newLocator = candidate.documentUri,
                )
            }
            if (remainingCount == 0) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        oldUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            LocalWallpaperFolderRepairResult(
                relinkedCount = matches.size,
                discoveredCount = repairedItems.size,
                remainingCount = remainingCount,
                limited = scan.limited || oldItems.size > MAX_FOLDER_RELINK_BATCH,
            )
        }
    }

    private suspend fun rescanFolderInternal(uriString: String): Result<Int> {
        val uri = parseFolderUri(uriString)
            ?: return Result.failure(IllegalArgumentException("A SAF folder URI is required"))
        val folderUri = uri.toString()
        val folder = folderDao.get(folderUri)
            ?: return Result.failure(IllegalArgumentException("The folder is not in the catalog"))
        folderDao.updateScanState(
            folderUri = folderUri,
            lastScannedAt = folder.lastScannedAt,
            scanStatus = LocalWallpaperFolderScanStatus.SCANNING,
            lastError = "",
            itemCount = folder.itemCount,
        )

        if (!hasPersistedReadPermission(uri)) {
            val error = "Folder permission was revoked"
            wallpaperDao.updateFolderMediaStatus(
                folderUri,
                LocalMediaStatus.PERMISSION_REVOKED,
                error,
            )
            folderDao.updateScanState(
                folderUri = folderUri,
                lastScannedAt = System.currentTimeMillis(),
                scanStatus = LocalWallpaperFolderScanStatus.PERMISSION_REVOKED,
                lastError = error,
                itemCount = folder.itemCount,
            )
            return Result.failure(SecurityException(error))
        }

        val existing = wallpaperDao.getByFolder(folderUri).associateBy(LocalWallpaperEntity::documentUri)
        val scanToken = UUID.randomUUID().toString()
        return try {
            val scan = scanTree(uri, scanToken, existing)
            val indexedCount = if (scan.error.isBlank()) {
                scan.items.size
            } else {
                (existing.keys + scan.items.map { it.documentUri }).toSet().size
            }
            database.withTransaction {
                wallpaperDao.upsertAll(scan.items)
                if (scan.error.isBlank()) wallpaperDao.markNotSeenInScanMissing(folderUri, scanToken)
                folderDao.updateScanState(
                    folderUri = folderUri,
                    lastScannedAt = System.currentTimeMillis(),
                    scanStatus = when {
                        scan.error.isNotBlank() -> LocalWallpaperFolderScanStatus.READY_PARTIAL
                        scan.limited -> LocalWallpaperFolderScanStatus.READY_LIMITED
                        else -> LocalWallpaperFolderScanStatus.READY
                    },
                    lastError = scan.error,
                    itemCount = indexedCount,
                )
            }
            Result.success(scan.items.size)
        } catch (error: SecurityException) {
            wallpaperDao.updateFolderMediaStatus(
                folderUri,
                LocalMediaStatus.PERMISSION_REVOKED,
                "Folder permission was revoked",
            )
            folderDao.updateScanState(
                folderUri = folderUri,
                lastScannedAt = System.currentTimeMillis(),
                scanStatus = LocalWallpaperFolderScanStatus.PERMISSION_REVOKED,
                lastError = "Folder permission was revoked",
                itemCount = folder.itemCount,
            )
            Result.failure(error)
        } catch (error: Exception) {
            folderDao.updateScanState(
                folderUri = folderUri,
                lastScannedAt = System.currentTimeMillis(),
                scanStatus = LocalWallpaperFolderScanStatus.SCAN_FAILED,
                lastError = error.message.orEmpty().ifBlank { "Folder scan failed" },
                itemCount = folder.itemCount,
            )
            Result.failure(error)
        }
    }

    suspend fun rotationWallpapers(target: WallpaperTarget? = null): List<Wallpaper> {
        val folderList = folderDao.getAll()
        val foldersByUri = folderList.associateBy(LocalWallpaperFolderEntity::folderUri)
        val activeFolders = folderList.filter { folder ->
            folder.scanStatus == LocalWallpaperFolderScanStatus.READY ||
                folder.scanStatus == LocalWallpaperFolderScanStatus.READY_LIMITED ||
                folder.scanStatus == LocalWallpaperFolderScanStatus.READY_PARTIAL
        }.filter { folder -> target == null || folder.target == WallpaperTarget.BOTH.name || folder.target == target.name }
        val allowedUris = activeFolders.mapTo(HashSet(), LocalWallpaperFolderEntity::folderUri)
        val allItems = wallpaperDao.getAll()
            .filter { item ->
                val folder = foldersByUri[item.folderUri]
                val targetMatches = target == null || folder == null ||
                    folder.target == WallpaperTarget.BOTH.name || folder.target == target.name
                !item.needsLocalMediaRelink() && targetMatches &&
                    (item.isStandalone || item.folderUri in allowedUris)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        val seenHashes = HashSet<String>()
        return allItems.mapNotNull { item ->
            if (item.contentHash.isNotBlank() && !seenHashes.add(item.contentHash)) return@mapNotNull null
            val folderName = foldersByUri[item.folderUri]?.displayName ?: "Relinked file"
            item.toWallpaper(folderName)
        }
    }

    private fun LocalWallpaperEntity.toWallpaper(folderName: String): Wallpaper = Wallpaper(
        id = stableLocalMediaId(),
        source = ContentSource.LOCAL,
        thumbnailUrl = documentUri,
        fullUrl = documentUri,
        width = width,
        height = height,
        tags = tags.split(',').map(String::trim).filter(String::isNotBlank),
        fileSize = sizeBytes,
        fileType = mimeType,
        sourcePageUrl = folderUri,
        license = "Local User Content",
        uploaderName = folderName,
        contentHash = contentHash,
    )

    private fun scanTree(
        treeUri: Uri,
        scanToken: String,
        existing: Map<String, LocalWallpaperEntity>,
        shouldHashContent: (Long) -> Boolean = { true },
    ): ScanResult {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val visited = HashSet<String>()
        val items = ArrayList<LocalWallpaperEntity>()
        var limited = false
        var partialError = ""

        fun visit(parentDocumentId: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || items.size >= MAX_INDEXED_FILES) {
                limited = true
                return
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            context.contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext() && items.size < MAX_INDEXED_FILES) {
                    val documentId = cursor.stringAt(documentIdIndex).takeUnless(String::isBlank) ?: continue
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    if (!visited.add(childUri.toString())) continue
                    val displayName = cursor.stringAt(displayNameIndex).ifBlank { documentId }
                    val mimeType = cursor.stringAt(mimeTypeIndex)
                    if (mimeType == IMAGE_DIRECTORY_MIME) {
                        visit(documentId, depth + 1)
                        continue
                    }
                    if (!isLocalWallpaperImage(displayName, mimeType)) continue
                    val sizeBytes = cursor.longAt(sizeIndex)
                    val modifiedAt = cursor.longAt(modifiedIndex)
                    val documentUri = childUri.toString()
                    val previous = existing[documentUri]
                    val metadataUnchanged = previous != null && previous.sizeBytes == sizeBytes &&
                        previous.modifiedAt == modifiedAt && previous.mimeType == mimeType &&
                        previous.displayName == displayName
                    val previousHash = previous?.contentHash.orEmpty()
                    val contentHash = when {
                        metadataUnchanged && previousHash.isNotBlank() -> previousHash
                        shouldHashContent(sizeBytes) -> hashDocument(childUri)
                        else -> ""
                    }
                    val dimensions = if (
                        metadataUnchanged && previous.width > 0 && previous.height > 0
                    ) {
                        previous.width to previous.height
                    } else {
                        readImageBounds(childUri)
                    }
                    val corrupt = dimensions.first <= 0 || dimensions.second <= 0
                    items += LocalWallpaperEntity(
                        documentUri = documentUri,
                        stableId = previous?.stableLocalMediaId() ?: documentUri,
                        folderUri = treeUri.toString(),
                        documentId = documentId,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAt = modifiedAt,
                        contentHash = contentHash,
                        width = dimensions.first,
                        height = dimensions.second,
                        localMediaStatus = if (corrupt) LocalMediaStatus.CORRUPT else LocalMediaStatus.AVAILABLE,
                        localMediaReason = if (corrupt) "Image dimensions could not be read" else "",
                        isStandalone = previous?.isStandalone ?: false,
                        tags = previous?.tags.orEmpty(),
                        lastSeenScanToken = scanToken,
                        addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                    )
                }
                if (items.size >= MAX_INDEXED_FILES) limited = true
            } ?: run {
                partialError = "The folder contents could not be read"
            }
        }

        visit(rootDocumentId, 0)
        return ScanResult(items = items, limited = limited, error = partialError)
    }

    private fun hashDocument(uri: Uri): String {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                hashLocalWallpaperStream(input)
            } ?: return ""
        }.getOrDefault("")
    }

    private fun readImageBounds(uri: Uri): Pair<Int, Int> = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }.getOrDefault(0 to 0)

    private fun hasPersistedReadPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }.getOrDefault(false)

    private fun folderDisplayName(uri: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else ""
        }.orEmpty()
    }.getOrNull()?.ifBlank { uri.lastPathSegment.orEmpty() }.orEmpty().ifBlank { "Local folder" }

    private fun parseFolderUri(value: String): Uri? = runCatching {
        Uri.parse(value.trim()).takeIf { it.scheme.equals("content", ignoreCase = true) && !it.authority.isNullOrBlank() }
    }.getOrNull()

    private data class ScanResult(
        val items: List<LocalWallpaperEntity>,
        val limited: Boolean,
        val error: String,
    )

    private companion object {
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

internal fun matchLocalWallpaperFolderRepairCandidates(
    oldItems: List<LocalWallpaperEntity>,
    candidates: List<LocalWallpaperEntity>,
    maxItems: Int,
    occupiedCandidates: Map<String, LocalWallpaperEntity> = emptyMap(),
): List<Pair<LocalWallpaperEntity, LocalWallpaperEntity>> {
    if (maxItems <= 0) return emptyList()
    val availableByHash = candidates
        .filter { it.contentHash.isNotBlank() && !it.needsLocalMediaRelink() }
        .groupBy(LocalWallpaperEntity::contentHash)
        .mapValues { (_, items) -> items.toMutableList() }
        .toMutableMap()
    return buildList {
        oldItems.take(maxItems).forEach { previous ->
            if (previous.contentHash.isBlank()) return@forEach
            val choices = availableByHash[previous.contentHash] ?: return@forEach
            if (choices.isEmpty()) return@forEach
            val compatibleChoices = choices.filter { next ->
                occupiedCandidates[next.documentUri]?.stableLocalMediaId()?.let { occupiedStableId ->
                    occupiedStableId == previous.stableLocalMediaId()
                } ?: true
            }
            if (compatibleChoices.isEmpty()) return@forEach
            val candidate = compatibleChoices.firstOrNull { next ->
                next.displayName.equals(previous.displayName, ignoreCase = true) &&
                    next.sizeBytes == previous.sizeBytes &&
                    (previous.width <= 0 || next.width == previous.width) &&
                    (previous.height <= 0 || next.height == previous.height)
            } ?: compatibleChoices.first()
            choices.remove(candidate)
            add(previous to candidate)
        }
    }
}

/** Full-file, constant-memory identity for SAF media. Large files must remain relinkable. */
internal fun hashLocalWallpaperStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

internal fun isLocalWallpaperImage(displayName: String?, mimeType: String?): Boolean {
    val normalizedMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    if (normalizedMime == IMAGE_DIRECTORY_MIME) return false
    if (normalizedMime.startsWith("image/")) return true
    return displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif")
}

private fun android.database.Cursor.stringAt(index: Int): String =
    if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

private fun android.database.Cursor.longAt(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index).coerceAtLeast(0L) else 0L
