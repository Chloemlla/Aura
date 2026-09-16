package com.chloemlla.aura.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.chloemlla.aura.data.model.FitCanvasMode
import com.chloemlla.aura.data.model.FitCanvasStyle
import com.chloemlla.aura.data.model.WALLPAPER_PRESENTATION_FILL
import com.chloemlla.aura.data.model.WALLPAPER_PRESENTATION_FIT
import com.chloemlla.aura.data.model.decideVideoOptimization
import com.chloemlla.aura.data.model.mediaOptimizationKey
import com.chloemlla.aura.data.model.originalTechnicalMetadata
import com.chloemlla.aura.data.model.normalizeWallpaperPresentation
import com.chloemlla.aura.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VideoWallpaperSelectionResult {
    data object Preparing : VideoWallpaperSelectionResult
    data object Ready : VideoWallpaperSelectionResult
    data class Failure(val message: String) : VideoWallpaperSelectionResult
}

internal const val VIDEO_WALLPAPER_SCALE_MODE_ZOOM = "zoom"
internal const val VIDEO_WALLPAPER_SCALE_MODE_FIT = "fit"
internal const val VIDEO_WALLPAPER_DEFAULT_SCALE_MODE_PREF = "default_scale_mode"
internal const val VIDEO_WALLPAPER_DEFAULT_CANVAS_MODE_PREF = "default_canvas_mode"
internal const val VIDEO_WALLPAPER_DEFAULT_CANVAS_COLOR_PREF = "default_canvas_color"
internal const val VIDEO_WALLPAPER_CANVAS_MODE_PREF = "canvas_mode"
internal const val VIDEO_WALLPAPER_CANVAS_COLOR_PREF = "canvas_color"
internal const val VIDEO_WALLPAPER_CANVAS_BACKGROUND_PREF = "canvas_background_path"
internal const val VIDEO_WALLPAPER_CANVAS_BAKED_PREF = "canvas_baked"
internal const val VIDEO_WALLPAPER_CANVAS_BACKGROUND_FILE = "fit_canvas_background.png"

internal fun videoWallpaperMimeTypes(): Array<String> = arrayOf("video/*", "image/gif")

internal const val MAX_VIDEO_WALLPAPER_BYTES = 256L * 1024L * 1024L
private const val MIN_VIDEO_WALLPAPER_DURATION_MS = 1_000L

internal data class VideoWallpaperProbe(
    val hasVideo: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val codec: String = "",
)

internal fun normalizeVideoWallpaperScaleMode(scaleMode: String?): String =
    when (scaleMode?.trim()?.lowercase(Locale.ROOT)) {
        VIDEO_WALLPAPER_SCALE_MODE_FIT -> VIDEO_WALLPAPER_SCALE_MODE_FIT
        else -> VIDEO_WALLPAPER_SCALE_MODE_ZOOM
    }

internal fun downloadedVideoHistoryId(source: String, contentId: String): String =
    downloadHistoryId("VIDEO", "${source.lowercase(Locale.ROOT)}:${contentId.trim()}")

internal data class VideoWallpaperPresentation(
    val scaleMode: String,
    val canvasStyle: FitCanvasStyle,
)

internal fun readDefaultVideoWallpaperPresentation(context: Context): VideoWallpaperPresentation {
    val preferences = context.getSharedPreferences("freevibe_live_wp", Context.MODE_PRIVATE)
    return VideoWallpaperPresentation(
        scaleMode = when (normalizeWallpaperPresentation(
            preferences.getString(VIDEO_WALLPAPER_DEFAULT_SCALE_MODE_PREF, WALLPAPER_PRESENTATION_FILL),
        )) {
            WALLPAPER_PRESENTATION_FIT -> VIDEO_WALLPAPER_SCALE_MODE_FIT
            else -> VIDEO_WALLPAPER_SCALE_MODE_ZOOM
        },
        canvasStyle = FitCanvasStyle(
            mode = FitCanvasMode.fromPreference(
                preferences.getString(
                    VIDEO_WALLPAPER_DEFAULT_CANVAS_MODE_PREF,
                    FitCanvasMode.AMOLED_BLACK.preferenceValue,
                ),
            ),
            customColor = preferences.getInt(
                VIDEO_WALLPAPER_DEFAULT_CANVAS_COLOR_PREF,
                com.chloemlla.aura.data.model.DEFAULT_FIT_CANVAS_COLOR,
            ),
        ).normalized(),
    )
}

internal fun isGifVideoWallpaperSelection(
    mimeType: String?,
    fileName: String?,
): Boolean {
    val normalizedMime = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    val normalizedName = fileName?.lowercase(Locale.ROOT).orEmpty()
    return normalizedMime == "image/gif" || normalizedName.endsWith(".gif")
}

internal fun resolveVideoWallpaperExtension(
    mimeType: String?,
    fileName: String?,
): String {
    val normalizedMime = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    val normalizedName = fileName?.lowercase(Locale.ROOT).orEmpty()
    return when {
        isGifVideoWallpaperSelection(normalizedMime, normalizedName) -> "gif"
        normalizedMime == "video/webm" || normalizedName.endsWith(".webm") -> "webm"
        normalizedMime == "video/3gpp" || normalizedName.endsWith(".3gp") -> "3gp"
        normalizedMime == "video/ogg" || normalizedName.endsWith(".ogv") -> "ogv"
        normalizedMime == "video/quicktime" || normalizedName.endsWith(".mov") -> "mov"
        normalizedMime == "video/x-matroska" || normalizedName.endsWith(".mkv") -> "mkv"
        else -> "mp4"
    }
}

internal fun videoWallpaperProbeFailure(probe: VideoWallpaperProbe): String? =
    when {
        !probe.hasVideo -> "Selected file does not contain a video track"
        probe.durationMs < MIN_VIDEO_WALLPAPER_DURATION_MS -> "Selected video is too short"
        probe.width <= 0 || probe.height <= 0 -> "Selected video dimensions could not be read"
        else -> null
    }

internal fun hasValidGifHeader(header: ByteArray): Boolean =
    header.size >= 6 && (
        header.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF87a" ||
            header.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF89a"
        )

/**
 * Replace [destination] with [source] without ever holding both copies.
 *
 * A rename is free when the two paths share a filesystem, which they do here —
 * both are in the app's own cache directory. Copying instead would need twice
 * the video's size on disk at once, which for a 256 MB ceiling is exactly the
 * situation the ceiling exists to avoid. The copy fallback covers the case
 * where a rename is refused, and still removes the source afterwards.
 */
internal fun moveIntoPlace(source: File, destination: File) {
    destination.delete()
    if (source.renameTo(destination)) return
    try {
        source.copyTo(destination, overwrite = true)
    } finally {
        source.delete()
    }
}

internal fun persistVideoWallpaperSelection(
    context: Context,
    file: File,
    scaleMode: String = VIDEO_WALLPAPER_SCALE_MODE_ZOOM,
    canvasStyle: FitCanvasStyle = FitCanvasStyle(),
    canvasBackgroundFile: File? = null,
    canvasBaked: Boolean = false,
) {
    val normalizedStyle = canvasStyle.normalized()
    context.getSharedPreferences("freevibe_live_wp", Context.MODE_PRIVATE)
        .edit()
        .putString("video_path", file.absolutePath)
        .putString("scale_mode", normalizeVideoWallpaperScaleMode(scaleMode))
        .putString(VIDEO_WALLPAPER_CANVAS_MODE_PREF, normalizedStyle.mode.preferenceValue)
        .putInt(VIDEO_WALLPAPER_CANVAS_COLOR_PREF, normalizedStyle.customColor)
        .putString(
            VIDEO_WALLPAPER_CANVAS_BACKGROUND_PREF,
            canvasBackgroundFile?.takeIf { it.exists() }?.absolutePath.orEmpty(),
        )
        .putBoolean(VIDEO_WALLPAPER_CANVAS_BAKED_PREF, canvasBaked)
        .apply()
}

@Singleton
class VideoWallpaperStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fitCanvasComposer: VideoFitCanvasComposer,
    private val mediaCopyStore: MediaCopyStore,
) {

    suspend fun prepareFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)
            val extension = resolveVideoWallpaperExtension(mimeType, uri.lastPathSegment)
            val originalDirectory = mediaOriginalDirectory().apply {
                if (!exists() && !mkdirs()) throw IOException("Could not create original-media storage")
            }
            val tempFile = File.createTempFile(".aura-video-original-", ".$extension.pending", originalDirectory)
            var committedOriginal: CommittedOriginalVideo? = null
            var originalRecorded = false

            try {
                val advertisedSize = resolver.advertisedSize(uri)
                if (advertisedLengthExceeds(advertisedSize, MAX_VIDEO_WALLPAPER_BYTES)) {
                    throw IOException("Selected file exceeds video wallpaper limit")
                }
                resolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        copyStreamCapped(input, output, MAX_VIDEO_WALLPAPER_BYTES)
                    }
                } ?: throw IOException("Could not open the selected file")

                val verifiedExtension = resolvedMotionExtension(sniffMediaFile(tempFile), extension)
                validatePreparedMotionFile(tempFile, verifiedExtension)
                committedOriginal = commitOriginalVideo(tempFile, verifiedExtension, uri.toString())
                val targetFile = committedOriginal.file
                val metadata = readMediaTechnicalMetadata(targetFile, mimeType.orEmpty())
                val digest = sha256File(targetFile)
                if (!digest.equals(committedOriginal.sha256, ignoreCase = true)) {
                    throw IOException("Saved original video did not match its source bytes")
                }
                val recordId = downloadHistoryId("VIDEO", "local:$digest")
                val previousOriginal = mediaCopyStore.findOriginal(recordId, "").first?.localPath
                mediaCopyStore.recordOriginal(
                    id = recordId,
                    source = "LOCAL",
                    type = "VIDEO",
                    locator = targetFile.absolutePath,
                    name = resolver.displayName(uri).ifBlank { "Local video wallpaper.$verifiedExtension" },
                    provenanceUrl = uri.toString(),
                    sha256 = digest,
                    metadata = metadata,
                )
                originalRecorded = true
                previousOriginal
                    ?.takeIf { it.isNotBlank() && it != targetFile.absolutePath }
                    ?.let(::deleteManagedOriginalVideo)
                val presentation = readDefaultVideoWallpaperPresentation(context)
                preparePresentation(targetFile, presentation.scaleMode, presentation.canvasStyle)
                    .getOrElse { throw it }
            } catch (e: Exception) {
                tempFile.delete()
                if (!originalRecorded) committedOriginal?.takeIf { it.created }?.file?.delete()
                throw e
            }
        }.onFailure { it.rethrowIfCancelled() }
    }

    suspend fun prepareDownloadedVideo(
        extension: String = "mp4",
        contentId: String = "",
        source: String = "VIDEO",
        displayName: String = "Video wallpaper",
        provenanceUrl: String = "",
        writer: suspend (File) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val originalDirectory = mediaOriginalDirectory().apply {
                if (!exists() && !mkdirs()) throw IOException("Could not create original-media storage")
            }
            val tempFile = File.createTempFile(".aura-video-original-", ".$extension.pending", originalDirectory)
            var committedOriginal: CommittedOriginalVideo? = null
            var originalRecorded = false

            try {
                writer(tempFile)
                val verifiedExtension = resolvedMotionExtension(sniffMediaFile(tempFile), extension)
                validatePreparedMotionFile(tempFile, verifiedExtension)
                committedOriginal = commitOriginalVideo(
                    tempFile,
                    verifiedExtension,
                    "$source:${contentId.ifBlank { displayName }}",
                )
                val targetFile = committedOriginal.file
                val metadata = readMediaTechnicalMetadata(targetFile)
                val digest = sha256File(targetFile)
                if (!digest.equals(committedOriginal.sha256, ignoreCase = true)) {
                    throw IOException("Saved original video did not match its source bytes")
                }
                val stableContentId = contentId.trim().ifBlank { digest }
                val recordId = downloadedVideoHistoryId(source, stableContentId)
                val previousOriginal = mediaCopyStore.findOriginal(recordId, "").first?.localPath
                mediaCopyStore.recordOriginal(
                    id = recordId,
                    source = source,
                    type = "VIDEO",
                    locator = targetFile.absolutePath,
                    name = displayName.ifBlank { "Video wallpaper" },
                    provenanceUrl = provenanceUrl,
                    sha256 = digest,
                    metadata = metadata,
                )
                originalRecorded = true
                previousOriginal
                    ?.takeIf { it.isNotBlank() && it != targetFile.absolutePath }
                    ?.let(::deleteManagedOriginalVideo)
                targetFile
            } catch (e: Exception) {
                tempFile.delete()
                if (!originalRecorded) committedOriginal?.takeIf { it.created }?.file?.delete()
                throw e
            }
        }.onFailure { it.rethrowIfCancelled() }
    }

    suspend fun preparePresentation(
        file: File,
        scaleMode: String,
        canvasStyle: FitCanvasStyle,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedScaleMode = normalizeVideoWallpaperScaleMode(scaleMode)
            val (originalRecord, originalLocator) = mediaCopyStore.findOriginal(null, file.absolutePath)
            val originalFile = File(originalLocator)
            if (!originalFile.isFile) throw IOException("Saved original video is unavailable")
            if (originalRecord == null) throw IOException("Saved original video record is unavailable")
            val originalMetadata = originalRecord.originalTechnicalMetadata()
            val fitRequested = normalizedScaleMode == VIDEO_WALLPAPER_SCALE_MODE_FIT
            val originalIsGif = originalFile.extension.equals("gif", ignoreCase = true)
            val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(2)
            val targetHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(2)
            val decision = decideVideoOptimization(
                metadata = originalMetadata,
                fileExtension = originalFile.extension,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                fitRequested = fitRequested,
            )
            if (fitRequested && originalIsGif && !decision.required) {
                val gifPresentation = fitCanvasComposer.prepare(originalFile, canvasStyle)
                    .getOrElse { throw it }
                persistVideoWallpaperSelection(
                    context = context,
                    file = originalFile,
                    scaleMode = VIDEO_WALLPAPER_SCALE_MODE_FIT,
                    canvasStyle = FitCanvasStyle(
                        mode = gifPresentation.resolvedCanvas.mode,
                        customColor = gifPresentation.resolvedCanvas.color,
                    ),
                    canvasBackgroundFile = gifPresentation.backgroundFile,
                    canvasBaked = false,
                )
                return@runCatching originalFile
            }
            if (!decision.required) {
                File(context.filesDir, VIDEO_WALLPAPER_CANVAS_BACKGROUND_FILE).delete()
                persistVideoWallpaperSelection(
                    context = context,
                    file = originalFile,
                    scaleMode = VIDEO_WALLPAPER_SCALE_MODE_ZOOM,
                )
                return@runCatching originalFile
            }

            val sourceHash = originalRecord.originalSha256.ifBlank {
                sha256File(originalFile)
            }
            val optimizationKey = mediaOptimizationKey(
                "video-wallpaper",
                sourceHash,
                normalizedScaleMode,
                canvasStyle.mode.preferenceValue,
                canvasStyle.customColor,
                targetWidth,
                targetHeight,
                originalMetadata.codec,
                originalMetadata.mimeType,
            )
            var resolvedStyle = canvasStyle.normalized()
            var canvasBackground: File? = null
            val prepared = mediaCopyStore.reusableCopy(originalRecord.id, optimizationKey)
                ?: mediaCopyStore.prepareCopy(
                    downloadId = originalRecord.id,
                    sourceIdentity = sourceHash,
                    optimizationKey = optimizationKey,
                    reason = decision.reason,
                    extension = "mp4",
                    expectedBytes = originalFile.length(),
                    maxBytes = MAX_VIDEO_WALLPAPER_BYTES,
                ) { pending ->
                    if (fitRequested && !originalIsGif) {
                        val composed = fitCanvasComposer.prepare(originalFile, canvasStyle)
                            .getOrElse { throw it }
                        resolvedStyle = FitCanvasStyle(
                            mode = composed.resolvedCanvas.mode,
                            customColor = composed.resolvedCanvas.color,
                        )
                        canvasBackground = composed.backgroundFile
                        try {
                            composed.file.inputStream().use { input ->
                                pending.outputStream().use { output ->
                                    copyStreamCapped(input, output, MAX_VIDEO_WALLPAPER_BYTES)
                                }
                            }
                        } finally {
                            if (composed.file.absolutePath != originalFile.absolutePath) {
                                composed.file.delete()
                            }
                        }
                    } else {
                        fitCanvasComposer.writeCompatibleCopy(
                            source = originalFile,
                            output = pending,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                        ).getOrElse { throw it }
                    }
                }
            validatePreparedMotionFile(prepared.file, "mp4")
            if (fitRequested && originalIsGif) {
                val gifPresentation = fitCanvasComposer.prepare(originalFile, canvasStyle)
                    .getOrElse { throw it }
                resolvedStyle = FitCanvasStyle(
                    mode = gifPresentation.resolvedCanvas.mode,
                    customColor = gifPresentation.resolvedCanvas.color,
                )
                canvasBackground = gifPresentation.backgroundFile
            }
            persistVideoWallpaperSelection(
                context = context,
                file = prepared.file,
                scaleMode = normalizedScaleMode,
                canvasStyle = resolvedStyle,
                canvasBackgroundFile = canvasBackground,
                canvasBaked = fitRequested && !originalIsGif,
            )
            prepared.file
        }.onFailure { it.rethrowIfCancelled() }
    }

    /** Return a byte-verified saved feed original so reapply works offline and skips a refetch. */
    suspend fun findSavedDownloadedVideo(contentId: String, source: String): File? =
        withContext(Dispatchers.IO) {
            val stableContentId = contentId.trim().takeIf(String::isNotBlank)
                ?: return@withContext null
            val record = mediaCopyStore.findOriginal(
                downloadedVideoHistoryId(source, stableContentId),
                "",
            ).first ?: return@withContext null
            val file = managedOriginalVideo(record.localPath) ?: return@withContext null
            if (!file.isFile || file.length() <= 0L) return@withContext null
            if (record.originalSizeBytes > 0L && file.length() != record.originalSizeBytes) {
                return@withContext null
            }
            if (
                record.originalSha256.isBlank() ||
                !sha256File(file).equals(record.originalSha256, ignoreCase = true)
            ) {
                return@withContext null
            }
            file
        }

    private fun validatePreparedMotionFile(file: File, extension: String) {
        if (!file.exists() || file.length() == 0L) {
            throw IOException("Selected file is empty or invalid")
        }
        if (file.length() > MAX_VIDEO_WALLPAPER_BYTES) {
            throw IOException("Selected file exceeds video wallpaper limit")
        }
        if (extension.equals("gif", ignoreCase = true)) {
            try {
                GifStructureValidator.requireValid(file)
            } catch (e: IOException) {
                throw IOException("Selected GIF is invalid", e)
            }
            return
        }
        if (file.length() < 1024) {
            throw IOException("Selected file is empty or invalid")
        }
        val probe = probeVideoFile(file)
        videoWallpaperProbeFailure(probe)?.let { throw IOException(it) }
    }

    private fun probeVideoFile(file: File): VideoWallpaperProbe {
        val technicalMetadata = readMediaTechnicalMetadata(file)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.equals("yes", ignoreCase = true) == true
            VideoWallpaperProbe(
                hasVideo = hasVideo,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?: technicalMetadata.mimeType,
                codec = technicalMetadata.codec,
            )
        } catch (e: Exception) {
            throw IOException("Selected video could not be decoded", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun commitOriginalVideo(
        tempFile: File,
        extension: String,
        sourceIdentity: String,
    ): CommittedOriginalVideo {
        val digest = sha256File(tempFile)
        val sourceToken = mediaOptimizationKey(sourceIdentity).take(12)
        val safeExtension = extension.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).take(8)
            .ifBlank { "mp4" }
        val destination = File(
            mediaOriginalDirectory(),
            "aura_original_video_${sourceToken}_$digest.$safeExtension",
        )
        if (destination.isFile && destination.length() == tempFile.length() && sha256File(destination) == digest) {
            tempFile.delete()
            return CommittedOriginalVideo(destination, created = false, sha256 = digest)
        }
        replaceApplyCopy(tempFile, destination)
        return CommittedOriginalVideo(destination, created = true, sha256 = digest)
    }

    private fun mediaOriginalDirectory(): File = File(context.filesDir, MEDIA_ORIGINAL_DIRECTORY)

    private fun deleteManagedOriginalVideo(path: String): Boolean {
        return managedOriginalVideo(path)?.delete() ?: false
    }

    private fun managedOriginalVideo(path: String): File? {
        val root = runCatching { mediaOriginalDirectory().canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun android.content.ContentResolver.advertisedSize(uri: Uri): Long =
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
        } ?: -1L

    private fun android.content.ContentResolver.displayName(uri: Uri): String =
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else ""
        }.orEmpty()

    companion object {
        internal const val MEDIA_ORIGINAL_DIRECTORY = "media_originals"
    }

    private data class CommittedOriginalVideo(
        val file: File,
        val created: Boolean,
        val sha256: String,
    )

}

internal fun resolvedMotionExtension(
    sniffed: SniffedMediaType?,
    fallbackExtension: String,
): String = when {
    sniffed?.mimeType == "image/gif" -> "gif"
    sniffed?.family == MediaFamily.CONTAINER -> sniffed.extension
    else -> fallbackExtension.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).take(8)
        .ifBlank { "mp4" }
}
