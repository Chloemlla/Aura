package com.freevibe.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.LocalWallpaperFolderEntity
import com.freevibe.data.model.LocalWallpaperFolderScanStatus
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.normalizeLocalWallpaperTags
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SCAN_DEPTH = 8
private const val MAX_INDEXED_FILES = 10_000
private const val MAX_HASH_BYTES = 64L * 1024L * 1024L
private const val IMAGE_DIRECTORY_MIME = "vnd.android.document/directory"

@Singleton
class LocalWallpaperCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FreeVibeDatabase,
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
                if (scan.error.isBlank()) wallpaperDao.deleteNotSeenInScan(folderUri, scanToken)
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
            .filter { it.folderUri in allowedUris }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        val seenHashes = HashSet<String>()
        return allItems.mapNotNull { item ->
            if (item.contentHash.isNotBlank() && !seenHashes.add(item.contentHash)) return@mapNotNull null
            val folder = foldersByUri[item.folderUri] ?: return@mapNotNull null
            item.toWallpaper(folder.displayName)
        }
    }

    private fun LocalWallpaperEntity.toWallpaper(folderName: String): Wallpaper = Wallpaper(
        id = documentUri,
        source = ContentSource.LOCAL,
        thumbnailUrl = documentUri,
        fullUrl = documentUri,
        width = 0,
        height = 0,
        tags = tags.split(',').map(String::trim).filter(String::isNotBlank),
        fileSize = sizeBytes,
        fileType = mimeType,
        sourcePageUrl = folderUri,
        license = "Local User Content",
        uploaderName = folderName,
    )

    private fun scanTree(
        treeUri: Uri,
        scanToken: String,
        existing: Map<String, LocalWallpaperEntity>,
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
                    val contentHash = if (previous != null && previous.sizeBytes == sizeBytes &&
                        previous.modifiedAt == modifiedAt && previous.mimeType == mimeType &&
                        previous.displayName == displayName
                    ) {
                        previous.contentHash
                    } else {
                        hashDocument(childUri)
                    }
                    items += LocalWallpaperEntity(
                        documentUri = documentUri,
                        folderUri = treeUri.toString(),
                        documentId = documentId,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAt = modifiedAt,
                        contentHash = contentHash,
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
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_HASH_BYTES) return ""
                    digest.update(buffer, 0, read)
                }
            } ?: return ""
            digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
        }.getOrDefault("")
    }

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
