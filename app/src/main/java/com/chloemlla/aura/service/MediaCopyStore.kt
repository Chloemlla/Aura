package com.chloemlla.aura.service

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.chloemlla.aura.data.local.DownloadDao
import com.chloemlla.aura.data.model.DownloadEntity
import com.chloemlla.aura.data.model.MediaTechnicalMetadata
import com.chloemlla.aura.data.model.mediaOptimizationKey
import com.chloemlla.aura.data.model.optimizedTechnicalMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedMediaCopy(
    val file: File,
    val metadata: MediaTechnicalMetadata,
    val reused: Boolean,
)

@Singleton
class MediaCopyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    private val copyMutationMutex = Mutex()

    suspend fun recordOriginal(
        id: String,
        source: String,
        type: String,
        locator: String,
        name: String,
        provenanceUrl: String,
        sha256: String,
        metadata: MediaTechnicalMetadata,
    ): DownloadEntity = withContext(Dispatchers.IO) {
        copyMutationMutex.withLock {
            val previous = downloadDao.getById(id)
            val sameBytes = previous?.originalSha256
                ?.equals(sha256, ignoreCase = true) == true
            val record = (if (sameBytes) previous else null)?.copy(
                source = source,
                type = type,
                localPath = locator,
                name = name,
                downloadedAt = System.currentTimeMillis(),
                provenanceUrl = provenanceUrl,
                originalSha256 = sha256,
                originalMimeType = metadata.mimeType,
                originalCodec = metadata.codec,
                originalWidth = metadata.width,
                originalHeight = metadata.height,
                originalDurationMs = metadata.durationMs,
                originalSizeBytes = metadata.sizeBytes,
                originalHdr = metadata.isHdr,
            ) ?: DownloadEntity(
                id = id,
                source = source,
                type = type,
                localPath = locator,
                name = name,
                provenanceUrl = provenanceUrl,
                originalSha256 = sha256,
                originalMimeType = metadata.mimeType,
                originalCodec = metadata.codec,
                originalWidth = metadata.width,
                originalHeight = metadata.height,
                originalDurationMs = metadata.durationMs,
                originalSizeBytes = metadata.sizeBytes,
                originalHdr = metadata.isHdr,
            )
            downloadDao.insert(record)
            if (!sameBytes) previous?.optimizedPath?.let(::deleteManagedApplyCopy)
            record
        }
    }

    suspend fun findOriginal(downloadId: String?, fallbackLocator: String): Pair<DownloadEntity?, String> {
        val byId = downloadId?.takeIf(String::isNotBlank)?.let { downloadDao.getById(it) }
        val byLocator = if (byId == null && fallbackLocator.isNotBlank()) {
            downloadDao.getByLocalPath(fallbackLocator)
        } else {
            null
        }
        val record = byId ?: byLocator
        return record to record?.localPath?.takeIf(String::isNotBlank).orEmpty().ifBlank { fallbackLocator }
    }

    suspend fun reusableCopy(downloadId: String?, optimizationKey: String): PreparedMediaCopy? =
        withContext(Dispatchers.IO) {
            copyMutationMutex.withLock {
                reusableCopyLocked(downloadId, optimizationKey)
            }
        }

    suspend fun prepareCopy(
        downloadId: String,
        sourceIdentity: String,
        optimizationKey: String,
        reason: String,
        extension: String,
        expectedBytes: Long,
        maxBytes: Long,
        writer: suspend (File) -> Unit,
    ): PreparedMediaCopy = withContext(Dispatchers.IO) {
        copyMutationMutex.withLock {
            reusableCopyLocked(downloadId, optimizationKey)?.let { return@withLock it }
            val originalAtStart = downloadDao.getById(downloadId)
                ?: throw IOException("Saved original record is unavailable")
            if (
                originalAtStart.originalSha256.isNotBlank() &&
                !originalAtStart.originalSha256.equals(sourceIdentity, ignoreCase = true)
            ) {
                throw IOException("Saved original identity no longer matches the apply request")
            }

            val directory = applyCopyDirectory().apply {
                if (!exists() && !mkdirs()) throw IOException("Could not create optimized-copy storage")
            }
            requireApplyCopyCapacity(directory.usableSpace, expectedBytes)
            val safeExtension = extension.lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
                .take(8)
                .ifBlank { "bin" }
            val sourceToken = mediaOptimizationKey(sourceIdentity).take(16)
            val keyToken = mediaOptimizationKey(optimizationKey).take(16)
            // A unique final name keeps the prior derivative recoverable until the database
            // accepts its replacement. Source and settings tokens still make ownership clear.
            val destination = File(
                directory,
                "aura_apply_${sourceToken}_${keyToken}_${UUID.randomUUID()}.$safeExtension",
            )
            val pending = File.createTempFile(".aura-apply-", ".pending", directory)
            var committed = false
            try {
                writer(pending)
                if (!pending.isFile || pending.length() <= 0L) {
                    throw IOException("Optimized copy is empty")
                }
                if (pending.length() > maxBytes) {
                    throw IOException("Optimized copy exceeds the ${maxBytes}-byte limit")
                }
                val metadata = readMediaTechnicalMetadata(pending)
                val digest = sha256File(pending)
                val previous = downloadDao.getById(downloadId)
                    ?: throw IOException("Saved original record is unavailable")
                if (
                    previous.localPath != originalAtStart.localPath ||
                    previous.originalSha256 != originalAtStart.originalSha256
                ) {
                    throw IOException("Saved original changed while its optimized copy was being prepared")
                }

                replaceApplyCopy(pending, destination)
                downloadDao.insert(
                    previous.copy(
                        optimizedPath = destination.absolutePath,
                        optimizedSha256 = digest,
                        optimizedMimeType = metadata.mimeType,
                        optimizedCodec = metadata.codec,
                        optimizedWidth = metadata.width,
                        optimizedHeight = metadata.height,
                        optimizedDurationMs = metadata.durationMs,
                        optimizedSizeBytes = metadata.sizeBytes,
                        optimizedHdr = metadata.isHdr,
                        optimizationKey = optimizationKey,
                        optimizationReason = reason,
                        optimizedAt = destination.lastModified().takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                    ),
                )
                previous.optimizedPath
                    .takeIf { it.isNotBlank() && it != destination.absolutePath }
                    ?.let(::deleteManagedApplyCopy)
                committed = true
                PreparedMediaCopy(destination, metadata, reused = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw error
            } finally {
                pending.delete()
                if (!committed) destination.delete()
            }
        }
    }

    suspend fun deleteOptimizedCopy(downloadId: String): Boolean = withContext(Dispatchers.IO) {
        copyMutationMutex.withLock {
            val record = downloadDao.getById(downloadId) ?: return@withLock false
            if (record.optimizedPath.isBlank()) return@withLock false
            val managed = managedApplyCopy(record.optimizedPath)
            val staged = if (managed?.exists() == true) {
                if (!managed.isFile) return@withLock false
                File(
                    managed.parentFile,
                    ".${managed.name}.${UUID.randomUUID()}.delete-pending",
                ).also { pendingDelete ->
                    if (!managed.renameTo(pendingDelete)) return@withLock false
                }
            } else {
                null
            }
            try {
                downloadDao.insert(record.withoutOptimizedCopy())
            } catch (error: Exception) {
                if (staged?.exists() == true && managed != null && !staged.renameTo(managed)) {
                    error.addSuppressed(IOException("Could not restore optimized copy after database failure"))
                }
                throw error
            }
            staged?.delete()
            true
        }
    }

    fun deleteManagedApplyCopy(path: String): Boolean =
        managedApplyCopy(path)?.takeIf(File::exists)?.delete() ?: false

    private fun applyCopyDirectory(): File = File(context.filesDir, APPLY_COPY_DIRECTORY)

    private suspend fun reusableCopyLocked(
        downloadId: String?,
        optimizationKey: String,
    ): PreparedMediaCopy? {
        val record = downloadId?.takeIf(String::isNotBlank)?.let { downloadDao.getById(it) }
            ?: return null
        if (record.optimizationKey != optimizationKey || record.optimizedPath.isBlank()) return null
        val file = managedApplyCopy(record.optimizedPath) ?: return null
        if (!file.isFile || file.length() <= 0L || file.length() != record.optimizedSizeBytes) return null
        val modifiedAt = file.lastModified()
        if (record.optimizedAt <= 0L || modifiedAt != record.optimizedAt) {
            val digest = sha256File(file)
            if (!digest.equals(record.optimizedSha256, ignoreCase = true)) return null
            if (modifiedAt > 0L) downloadDao.insert(record.copy(optimizedAt = modifiedAt))
        }
        return PreparedMediaCopy(file, record.optimizedTechnicalMetadata(), reused = true)
    }

    private fun managedApplyCopy(path: String): File? {
        if (path.isBlank()) return null
        val root = runCatching { applyCopyDirectory().canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    companion object {
        internal const val APPLY_COPY_DIRECTORY = "apply_copies"
        internal const val MIN_FREE_BYTES_AFTER_COPY = 16L * 1024L * 1024L
    }
}

internal fun DownloadEntity.withoutOptimizedCopy(): DownloadEntity = copy(
    optimizedPath = "",
    optimizedSha256 = "",
    optimizedMimeType = "",
    optimizedCodec = "",
    optimizedWidth = 0,
    optimizedHeight = 0,
    optimizedDurationMs = 0,
    optimizedSizeBytes = 0,
    optimizedHdr = false,
    optimizationKey = "",
    optimizationReason = "",
    optimizedAt = 0L,
)

internal fun requireApplyCopyCapacity(
    usableBytes: Long,
    expectedBytes: Long,
    reserveBytes: Long = MediaCopyStore.MIN_FREE_BYTES_AFTER_COPY,
) {
    val required = expectedBytes.coerceAtLeast(1L).let { expected ->
        if (expected > Long.MAX_VALUE - reserveBytes) Long.MAX_VALUE else expected + reserveBytes
    }
    if (usableBytes < required) {
        throw IOException("Not enough storage for an optimized copy. The original was kept unchanged.")
    }
}

internal fun replaceApplyCopy(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    if (source.renameTo(destination)) return
    source.copyTo(destination, overwrite = true)
    source.delete()
}

internal fun sha256InputStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

internal fun sha256File(file: File): String = file.inputStream().use(::sha256InputStream)

internal fun readMediaTechnicalMetadata(
    file: File,
    declaredMimeType: String = "",
): MediaTechnicalMetadata {
    val sniffedMime = sniffMediaFile(file)?.mimeType.orEmpty()
    var mimeType = declaredMimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        .ifBlank { sniffedMime }
    var codec = mimeType.substringAfter('/', "").uppercase(Locale.ROOT)
    var width = 0
    var height = 0
    var durationMs = 0L
    var isHdr = false

    val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, imageBounds)
    if (imageBounds.outWidth > 0 && imageBounds.outHeight > 0) {
        width = imageBounds.outWidth
        height = imageBounds.outHeight
        if (mimeType.isBlank()) mimeType = imageBounds.outMimeType.orEmpty()
        codec = mimeType.substringAfter('/', codec).uppercase(Locale.ROOT)
        isHdr = detectUltraHdrGainmap(file, imageBounds.outWidth, imageBounds.outHeight)
    } else {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val retrievedMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.substringBefore(';')
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            if (retrievedMime.isNotBlank()) mimeType = retrievedMime
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                    ?.toIntOrNull()
                isHdr = transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    transfer == MediaFormat.COLOR_TRANSFER_ST2084
            }
        } catch (_: Exception) {
            // The bounded file was already structurally validated by its caller.
        } finally {
            runCatching { retriever.release() }
        }
        readPrimaryTrackCodec(file, mimeType)?.let { trackMime ->
            codec = trackMime.substringAfter('/').uppercase(Locale.ROOT)
        }
    }
    return MediaTechnicalMetadata(
        mimeType = mimeType,
        codec = codec,
        width = width,
        height = height,
        durationMs = durationMs,
        sizeBytes = file.length().coerceAtLeast(0L),
        isHdr = isHdr,
    )
}

private fun detectUltraHdrGainmap(file: File, width: Int, height: Int): Boolean {
    if (Build.VERSION.SDK_INT < 34 || width <= 0 || height <= 0) return false
    var sampleSize = 1
    val longEdge = maxOf(width, height).toLong()
    while (longEdge / sampleSize > 1_024L && sampleSize <= (1 shl 29)) sampleSize *= 2
    val bitmap = runCatching {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull() ?: return false
    return try {
        bitmap.hasGainmap()
    } finally {
        bitmap.recycle()
    }
}

private fun readPrimaryTrackCodec(file: File, containerMimeType: String): String? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        val wantsVideo = containerMimeType.startsWith("video/")
        val wantsAudio = containerMimeType.startsWith("audio/")
        (0 until extractor.trackCount)
            .mapNotNull { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }
            .firstOrNull { mime ->
                (wantsVideo && mime.startsWith("video/")) ||
                    (wantsAudio && mime.startsWith("audio/"))
            }
            ?: (0 until extractor.trackCount)
                .mapNotNull { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { mime -> mime.startsWith("video/") || mime.startsWith("audio/") }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}
