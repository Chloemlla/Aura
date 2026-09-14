package com.freevibe.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.LocalMediaStatus
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.MediaTechnicalMetadata
import com.freevibe.data.model.RotationExclusionIndex
import com.freevibe.data.model.rotationIdentity
import com.freevibe.data.model.stableLocalMediaId
import com.freevibe.data.model.toRotationExclusion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface LocalMediaRelinkTarget {
    data class Download(val id: String) : LocalMediaRelinkTarget

    data class Favorite(
        val id: String,
        val source: String,
        val type: String,
    ) : LocalMediaRelinkTarget

    data class CatalogItem(val documentUri: String) : LocalMediaRelinkTarget
}

sealed interface LocalMediaRelinkOutcome {
    data class Relinked(val locator: String) : LocalMediaRelinkOutcome
    data class ReviewRequired(val differences: List<String>) : LocalMediaRelinkOutcome
    data class Rejected(val message: String) : LocalMediaRelinkOutcome
}

internal data class LocalMediaRelinkExpectation(
    val mediaType: String,
    val locator: String,
    val sha256: String = "",
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
)

internal data class LocalMediaRelinkCandidate(
    val locator: String,
    val sha256: String,
    val metadata: MediaTechnicalMetadata,
)

@Singleton
class LocalMediaRelinkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FreeVibeDatabase,
    private val preferencesManager: PreferencesManager,
    private val mediaCopyStore: MediaCopyStore,
) {
    suspend fun relink(
        target: LocalMediaRelinkTarget,
        candidateUri: Uri,
        acceptMismatch: Boolean = false,
    ): LocalMediaRelinkOutcome = withContext(Dispatchers.IO) {
        val expectation = loadExpectation(target)
            ?: return@withContext LocalMediaRelinkOutcome.Rejected("The saved item is no longer available")
        val staged = try {
            probeCandidate(candidateUri, expectation.mediaType)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return@withContext LocalMediaRelinkOutcome.Rejected(
                error.message.orEmpty().ifBlank { "The selected file could not be read" },
            )
        }
        try {
            val candidate = LocalMediaRelinkCandidate(
                locator = candidateUri.toString(),
                sha256 = sha256File(staged),
                metadata = readMediaTechnicalMetadata(staged, context.contentResolver.getType(candidateUri).orEmpty()),
            )
            validateCandidateMetadata(expectation.mediaType, candidate.metadata)?.let { reason ->
                return@withContext LocalMediaRelinkOutcome.Rejected(reason)
            }
            val differences = localMediaRelinkDifferences(expectation, candidate)
            if (differences.isNotEmpty() && !acceptMismatch) {
                return@withContext LocalMediaRelinkOutcome.ReviewRequired(differences)
            }
            if (!persistReadPermission(candidateUri)) {
                return@withContext LocalMediaRelinkOutcome.Rejected(
                    "Aura could not keep access to that file. Choose it again from the system file picker.",
                )
            }
            commitRelink(target, expectation, candidate, staged)
        } finally {
            staged.delete()
        }
    }

    private suspend fun loadExpectation(target: LocalMediaRelinkTarget): LocalMediaRelinkExpectation? =
        when (target) {
            is LocalMediaRelinkTarget.Download -> database.downloadDao().getById(target.id)?.let { download ->
                LocalMediaRelinkExpectation(
                    mediaType = download.type,
                    locator = download.localPath,
                    sha256 = download.originalSha256,
                    mimeType = download.originalMimeType,
                    width = download.originalWidth,
                    height = download.originalHeight,
                    durationMs = download.originalDurationMs,
                    sizeBytes = download.originalSizeBytes,
                )
            }
            is LocalMediaRelinkTarget.Favorite -> database.favoriteDao()
                .getByIdentity(target.id, target.source, target.type)
                ?.let { favorite ->
                    val locator = favorite.offlinePath.ifBlank {
                        favorite.fullUrl.takeIf {
                            favorite.source.equals(ContentSource.LOCAL.name, ignoreCase = true)
                        }.orEmpty()
                    }
                    LocalMediaRelinkExpectation(
                        mediaType = favorite.type,
                        locator = locator,
                        sha256 = favorite.localMediaSha256,
                        mimeType = favorite.fileType.orEmpty(),
                        width = favorite.width,
                        height = favorite.height,
                        durationMs = (favorite.duration * 1_000.0).toLong(),
                        sizeBytes = favorite.fileSize ?: 0L,
                    )
                }
            is LocalMediaRelinkTarget.CatalogItem -> database.localWallpaperDao()
                .get(target.documentUri)
                ?.let { item ->
                    LocalMediaRelinkExpectation(
                        mediaType = "WALLPAPER",
                        locator = item.documentUri,
                        sha256 = item.contentHash,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        sizeBytes = item.sizeBytes,
                    )
                }
        }

    private fun probeCandidate(candidateUri: Uri, mediaType: String): File {
        val maxBytes = if (mediaType.equals("VIDEO", ignoreCase = true)) {
            MAX_VIDEO_WALLPAPER_BYTES
        } else {
            MAX_RELINK_IMAGE_OR_SOUND_BYTES
        }
        val staged = stageLocalMediaLocator(
            context = context,
            locator = candidateUri.toString(),
            tempDirectoryName = "media_relink",
            prefix = "aura_relink_",
            maxBytes = maxBytes,
        )
        try {
            val sniffed = sniffMediaFile(staged)
                ?: throw IOException("The selected file is corrupt or has an unsupported format")
            val metadata = readMediaTechnicalMetadata(staged, context.contentResolver.getType(candidateUri).orEmpty())
            if (!candidateMatchesMediaType(mediaType, sniffed, metadata)) {
                throw IOException("Choose a ${mediaType.lowercase(Locale.ROOT)} file for this item")
            }
            return staged
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    private suspend fun commitRelink(
        target: LocalMediaRelinkTarget,
        expectation: LocalMediaRelinkExpectation,
        candidate: LocalMediaRelinkCandidate,
        staged: File,
    ): LocalMediaRelinkOutcome {
        var committedLocator = candidate.locator
        var createdManagedVideo: File? = null
        var staleDerivative = ""
        try {
            if (target is LocalMediaRelinkTarget.Download && expectation.mediaType.equals("VIDEO", true)) {
                val committed = commitManagedVideoOriginal(staged, candidate)
                committedLocator = committed.first.absolutePath
                if (committed.second) createdManagedVideo = committed.first
            }
            database.withTransaction {
                when (target) {
                    is LocalMediaRelinkTarget.Download -> {
                        val current = database.downloadDao().getById(target.id)
                            ?: throw IOException("The saved item changed before relink completed")
                        if (current.localPath != expectation.locator) {
                            throw IOException("The saved item changed before relink completed")
                        }
                        val sameBytes = current.originalSha256.isNotBlank() &&
                            current.originalSha256.equals(candidate.sha256, ignoreCase = true)
                        val replacement = current.copy(
                            localPath = committedLocator,
                            localMediaStatus = LocalMediaStatus.AVAILABLE,
                            localMediaReason = null,
                            originalSha256 = candidate.sha256,
                            originalMimeType = candidate.metadata.mimeType,
                            originalCodec = candidate.metadata.codec,
                            originalWidth = candidate.metadata.width,
                            originalHeight = candidate.metadata.height,
                            originalDurationMs = candidate.metadata.durationMs,
                            originalSizeBytes = candidate.metadata.sizeBytes,
                            originalHdr = candidate.metadata.isHdr,
                        ).let { if (sameBytes) it else it.withoutOptimizedCopy() }
                        if (!sameBytes) staleDerivative = current.optimizedPath
                        database.downloadDao().insert(replacement)
                    }
                    is LocalMediaRelinkTarget.Favorite -> {
                        val current = database.favoriteDao()
                            .getByIdentity(target.id, target.source, target.type)
                            ?: throw IOException("The saved item changed before relink completed")
                        val currentLocator = current.offlinePath.ifBlank {
                            current.fullUrl.takeIf {
                                current.source.equals(ContentSource.LOCAL.name, ignoreCase = true)
                            }.orEmpty()
                        }
                        if (currentLocator != expectation.locator) {
                            throw IOException("The saved item changed before relink completed")
                        }
                        database.favoriteDao().relinkLocalMedia(
                            id = current.id,
                            source = current.source,
                            type = current.type,
                            oldLocator = expectation.locator,
                            newLocator = committedLocator,
                            sha256 = candidate.sha256,
                            mimeType = candidate.metadata.mimeType,
                            sizeBytes = candidate.metadata.sizeBytes,
                            width = candidate.metadata.width,
                            height = candidate.metadata.height,
                            durationSeconds = candidate.metadata.durationMs / 1_000.0,
                        )
                        if (current.type.equals("WALLPAPER", ignoreCase = true)) {
                            relinkWallpaperAssociations(
                                stableId = current.id,
                                source = current.source,
                                oldLocator = expectation.locator,
                                newLocator = committedLocator,
                                candidate = candidate,
                                updateFavorite = false,
                            )
                        }
                    }
                    is LocalMediaRelinkTarget.CatalogItem -> {
                        val current = database.localWallpaperDao().get(target.documentUri)
                            ?: throw IOException("The saved item changed before relink completed")
                        if (current.documentUri != expectation.locator) {
                            throw IOException("The saved item changed before relink completed")
                        }
                        val stableId = current.stableLocalMediaId()
                        val existingAtNewLocator = database.localWallpaperDao().get(committedLocator)
                        if (
                            existingAtNewLocator != null &&
                            existingAtNewLocator.stableLocalMediaId() != stableId
                        ) {
                            throw IOException("That file is already linked to a different library item")
                        }
                        val documentId = runCatching { DocumentsContract.getDocumentId(Uri.parse(committedLocator)) }
                            .getOrDefault(committedLocator)
                        val replacement = current.copy(
                            documentUri = committedLocator,
                            stableId = stableId,
                            documentId = documentId,
                            mimeType = candidate.metadata.mimeType,
                            sizeBytes = candidate.metadata.sizeBytes,
                            modifiedAt = System.currentTimeMillis(),
                            contentHash = candidate.sha256,
                            width = candidate.metadata.width,
                            height = candidate.metadata.height,
                            localMediaStatus = LocalMediaStatus.AVAILABLE,
                            localMediaReason = "",
                            isStandalone = true,
                        )
                        val priorExclusion = RotationExclusionIndex(database.rotationExclusionDao().getAll())
                            .find(current.rotationIdentity())
                        database.localWallpaperDao().upsertAll(listOf(replacement))
                        if (committedLocator != current.documentUri) {
                            database.localWallpaperDao().deleteByDocumentUri(current.documentUri)
                        }
                        relinkWallpaperAssociations(
                            stableId = stableId,
                            source = ContentSource.LOCAL.name,
                            oldLocator = expectation.locator,
                            newLocator = committedLocator,
                            candidate = candidate,
                        )
                        if (priorExclusion != null) {
                            val updatedExclusion = replacement.rotationIdentity()
                                .toRotationExclusion(excludedAt = priorExclusion.excludedAt)
                            database.rotationExclusionDao().upsert(updatedExclusion)
                            if (
                                priorExclusion.stableId != updatedExclusion.stableId &&
                                priorExclusion.contentHash == updatedExclusion.contentHash
                            ) {
                                database.rotationExclusionDao().deleteByStableId(priorExclusion.stableId)
                            }
                        }
                    }
                }
            }
            if (target is LocalMediaRelinkTarget.CatalogItem ||
                target is LocalMediaRelinkTarget.Favorite && target.type.equals("WALLPAPER", true)
            ) {
                val stableId = when (target) {
                    is LocalMediaRelinkTarget.CatalogItem -> database.localWallpaperDao()
                        .get(committedLocator)?.stableLocalMediaId().orEmpty()
                    is LocalMediaRelinkTarget.Favorite -> target.id
                    else -> ""
                }
                val source = when (target) {
                    is LocalMediaRelinkTarget.Favorite -> target.source
                    else -> ContentSource.LOCAL.name
                }
                relinkThemeSlots(stableId, source, expectation.locator, committedLocator)
            }
            if (staleDerivative.isNotBlank()) mediaCopyStore.deleteManagedApplyCopy(staleDerivative)
            return LocalMediaRelinkOutcome.Relinked(committedLocator)
        } catch (error: Exception) {
            createdManagedVideo?.delete()
            if (error is CancellationException) throw error
            return LocalMediaRelinkOutcome.Rejected(
                error.message.orEmpty().ifBlank { "The item could not be relinked" },
            )
        }
    }

    internal suspend fun relinkWallpaperAssociations(
        stableId: String,
        source: String,
        oldLocator: String,
        newLocator: String,
        candidate: LocalMediaRelinkCandidate? = null,
        updateFavorite: Boolean = true,
    ) {
        if (updateFavorite) {
            database.favoriteDao().getByIdentity(stableId, source, "WALLPAPER")?.let { favorite ->
                database.favoriteDao().relinkLocalMedia(
                    id = favorite.id,
                    source = favorite.source,
                    type = favorite.type,
                    oldLocator = oldLocator,
                    newLocator = newLocator,
                    sha256 = candidate?.sha256 ?: favorite.localMediaSha256,
                    mimeType = candidate?.metadata?.mimeType ?: favorite.fileType.orEmpty(),
                    sizeBytes = candidate?.metadata?.sizeBytes ?: favorite.fileSize ?: 0L,
                    width = candidate?.metadata?.width ?: favorite.width,
                    height = candidate?.metadata?.height ?: favorite.height,
                    durationSeconds = candidate?.metadata?.durationMs?.div(1_000.0) ?: favorite.duration,
                )
            }
        }
        database.collectionDao().relinkWallpaper(stableId, source, oldLocator, newLocator)
        database.wallpaperHistoryDao().relinkWallpaper(stableId, source, oldLocator, newLocator)
        if (oldLocator.isNotBlank()) {
            database.downloadDao().relinkLocatorReferences(oldLocator, newLocator)
        }
    }

    internal suspend fun relinkThemeSlots(
        stableId: String,
        source: String,
        oldLocator: String,
        newLocator: String,
    ) {
        if (stableId.isBlank()) return
        fun updated(value: String): String {
            val parts = value.split("|", limit = 3)
            return if (
                parts.size == 3 && parts[0].equals(source, true) && parts[1] == stableId &&
                parts[2] == oldLocator
            ) {
                "${parts[0]}|${parts[1]}|$newLocator"
            } else {
                value
            }
        }
        val dark = preferencesManager.darkModeWallpaperId.first()
        val light = preferencesManager.lightModeWallpaperId.first()
        updated(dark).takeIf { it != dark }?.let { preferencesManager.setDarkModeWallpaperId(it) }
        updated(light).takeIf { it != light }?.let { preferencesManager.setLightModeWallpaperId(it) }
    }

    private fun commitManagedVideoOriginal(
        staged: File,
        candidate: LocalMediaRelinkCandidate,
    ): Pair<File, Boolean> {
        val directory = File(context.filesDir, VideoWallpaperStorage.MEDIA_ORIGINAL_DIRECTORY).apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create original-media storage")
        }
        val extension = sniffMediaFile(staged)?.extension.orEmpty().ifBlank { "mp4" }
        val destination = File(directory, "aura_relinked_video_${candidate.sha256}.$extension")
        if (destination.isFile && destination.length() == staged.length() &&
            sha256File(destination).equals(candidate.sha256, ignoreCase = true)
        ) {
            return destination to false
        }
        val pending = File.createTempFile(".aura-relink-video-", ".pending", directory)
        try {
            staged.inputStream().use { input ->
                pending.outputStream().use { output ->
                    copyStreamCapped(input, output, MAX_VIDEO_WALLPAPER_BYTES)
                }
            }
            replaceApplyCopy(pending, destination)
            if (
                destination.length() != staged.length() ||
                !sha256File(destination).equals(candidate.sha256, ignoreCase = true)
            ) {
                destination.delete()
                throw IOException("The saved video copy failed integrity verification")
            }
            return destination to true
        } finally {
            pending.delete()
        }
    }

    private fun persistReadPermission(uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return true
        val alreadyPersisted = runCatching {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)
        if (alreadyPersisted) return true
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
    }

    private companion object {
        const val MAX_RELINK_IMAGE_OR_SOUND_BYTES = 80L * 1024L * 1024L
    }
}

internal fun candidateMatchesMediaType(
    mediaType: String,
    sniffed: SniffedMediaType,
    metadata: MediaTechnicalMetadata,
): Boolean {
    val decodedFamily = when {
        metadata.mimeType.startsWith("audio/") -> "SOUND"
        metadata.mimeType.startsWith("video/") -> "VIDEO"
        metadata.mimeType.startsWith("image/") -> "WALLPAPER"
        else -> ""
    }
    return when (mediaType.trim().uppercase(Locale.ROOT)) {
        "WALLPAPER" -> decodedFamily != "SOUND" && decodedFamily != "VIDEO" &&
            sniffed.family == MediaFamily.IMAGE
        "SOUND" -> decodedFamily == "SOUND" ||
            (decodedFamily.isBlank() && sniffed.family == MediaFamily.AUDIO)
        "VIDEO" -> metadata.mimeType == "image/gif" || decodedFamily == "VIDEO" ||
            (decodedFamily.isBlank() && sniffed.family == MediaFamily.CONTAINER)
        else -> false
    }
}

internal fun validateCandidateMetadata(mediaType: String, metadata: MediaTechnicalMetadata): String? = when {
    metadata.sizeBytes <= 0L -> "The selected file is empty"
    mediaType.equals("WALLPAPER", true) && (metadata.width <= 0 || metadata.height <= 0) ->
        "The selected image is corrupt or its dimensions cannot be read"
    mediaType.equals("SOUND", true) && metadata.durationMs <= 0L ->
        "The selected sound is corrupt or its duration cannot be read"
    mediaType.equals("VIDEO", true) && metadata.mimeType != "image/gif" && metadata.durationMs <= 0L ->
        "The selected video is corrupt or its duration cannot be read"
    else -> null
}

internal fun localMediaRelinkDifferences(
    expected: LocalMediaRelinkExpectation,
    candidate: LocalMediaRelinkCandidate,
): List<String> {
    if (expected.sha256.isNotBlank() && expected.sha256.equals(candidate.sha256, ignoreCase = true)) {
        return emptyList()
    }
    return buildList {
        if (expected.sha256.isNotBlank()) add("content hash")
        if (expected.sizeBytes > 0L && expected.sizeBytes != candidate.metadata.sizeBytes) add("file size")
        if (
            expected.width > 0 && expected.height > 0 &&
            (expected.width != candidate.metadata.width || expected.height != candidate.metadata.height)
        ) {
            add("dimensions")
        }
        if (expected.durationMs > 0L && candidate.metadata.durationMs > 0L) {
            val tolerance = maxOf(1_000L, expected.durationMs / 20L)
            if (kotlin.math.abs(expected.durationMs - candidate.metadata.durationMs) > tolerance) add("duration")
        }
        val expectedFamily = expected.mimeType.substringBefore('/').lowercase(Locale.ROOT)
        val candidateFamily = candidate.metadata.mimeType.substringBefore('/').lowercase(Locale.ROOT)
        if (expectedFamily.isNotBlank() && candidateFamily.isNotBlank() && expectedFamily != candidateFamily) {
            add("media type")
        }
    }.distinct()
}
