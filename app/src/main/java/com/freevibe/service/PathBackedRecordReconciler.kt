package com.freevibe.service

import android.content.Context
import android.net.Uri
import com.freevibe.data.local.DownloadDao
import com.freevibe.data.local.FavoriteDao
import com.freevibe.data.model.LocalMediaStatus
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.normalizeLocalMediaStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PathBackedRecordReconciliationResult(
    val favoritesUpdated: Int,
    val downloadsUpdated: Int,
)

data class PathBackedRecordProbe(
    val status: String,
    val reason: String? = null,
)

@Singleton
class PathBackedRecordReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val downloadDao: DownloadDao,
) {
    suspend fun reconcile(): PathBackedRecordReconciliationResult = withContext(Dispatchers.IO) {
        var favoritesUpdated = 0
        favoriteDao.getAll().first()
            .forEach { favorite ->
                val locator = favorite.offlinePath.ifBlank {
                    favorite.fullUrl.takeIf {
                        favorite.source.equals(ContentSource.LOCAL.name, ignoreCase = true)
                    }.orEmpty()
                }
                if (locator.isBlank()) return@forEach
                val probe = probePathBackedRecord(locator)
                if (
                    normalizeLocalMediaStatus(favorite.localMediaStatus) != probe.status ||
                    favorite.localMediaReason != probe.reason
                ) {
                    favoriteDao.updateLocalMediaStatus(
                        favorite.id,
                        favorite.source,
                        favorite.type,
                        probe.status,
                        probe.reason,
                    )
                    favoritesUpdated += 1
                }
            }

        var downloadsUpdated = 0
        downloadDao.getAll().first()
            .forEach { download ->
                if (download.localPath.isBlank()) return@forEach
                val probe = probePathBackedRecord(download.localPath)
                if (
                    normalizeLocalMediaStatus(download.localMediaStatus) != probe.status ||
                    download.localMediaReason != probe.reason
                ) {
                    downloadDao.updateLocalMediaStatus(download.id, probe.status, probe.reason)
                    downloadsUpdated += 1
                }
            }

        PathBackedRecordReconciliationResult(
            favoritesUpdated = favoritesUpdated,
            downloadsUpdated = downloadsUpdated,
        )
    }

    private fun probePathBackedRecord(rawPath: String): PathBackedRecordProbe =
        probePathBackedRecord(
            rawPath = rawPath,
            fileProbe = { path ->
                val file = File(path)
                when {
                    !file.exists() -> missingProbe()
                    !file.isFile || file.length() <= 0L -> corruptProbe()
                    else -> availableProbe()
                }
            },
            contentUriProbe = ::contentUriProbe,
        )

    private fun contentUriProbe(rawUri: String): PathBackedRecordProbe = try {
        context.contentResolver.openFileDescriptor(Uri.parse(rawUri), "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) corruptProbe() else availableProbe()
        } ?: missingProbe()
    } catch (_: SecurityException) {
        revokedProbe()
    } catch (_: FileNotFoundException) {
        missingProbe()
    } catch (_: Exception) {
        corruptProbe()
    }
}

internal fun availableProbe() = PathBackedRecordProbe(LocalMediaStatus.AVAILABLE)
internal fun missingProbe() = PathBackedRecordProbe(LocalMediaStatus.MISSING, "Local file is missing")
internal fun revokedProbe() = PathBackedRecordProbe(
    LocalMediaStatus.PERMISSION_REVOKED,
    "Android no longer grants access to this file",
)
internal fun corruptProbe() = PathBackedRecordProbe(
    LocalMediaStatus.CORRUPT,
    "Local file is empty, corrupt, or unreadable",
)

internal fun probePathBackedRecord(
    rawPath: String,
    fileProbe: (String) -> PathBackedRecordProbe,
    contentUriProbe: (String) -> PathBackedRecordProbe,
): PathBackedRecordProbe {
    val path = rawPath.trim()
    if (path.isBlank()) return availableProbe()

    return when (extractUriScheme(path)?.lowercase(Locale.ROOT)) {
        null -> fileProbe(path)
        "file" -> fileProbe(fileUriPath(path) ?: path)
        "content" -> contentUriProbe(path)
        "http", "https", "android.resource", "rawresource" -> availableProbe()
        else -> corruptProbe()
    }
}

internal fun pathBackedRecordExists(
    rawPath: String,
    fileExists: (String) -> Boolean,
    contentUriExists: (String) -> Boolean,
): Boolean {
    val probe = probePathBackedRecord(
        rawPath = rawPath,
        fileProbe = { if (fileExists(it)) availableProbe() else missingProbe() },
        contentUriProbe = { if (contentUriExists(it)) availableProbe() else missingProbe() },
    )
    return probe.status == LocalMediaStatus.AVAILABLE
}

private fun extractUriScheme(value: String): String? {
    val colonIndex = value.indexOf(':')
    if (colonIndex <= 0) return null
    if (
        colonIndex == 1 && value.first().isLetter() && value.length > 2 &&
        (value[2] == '/' || value[2] == '\\')
    ) {
        return null
    }
    val firstSeparator = value.indexOfAny(charArrayOf('/', '\\', '?', '#')).let { index ->
        if (index == -1) value.length else index
    }
    if (colonIndex > firstSeparator) return null
    val candidate = value.substring(0, colonIndex)
    return candidate.takeIf { scheme ->
        scheme.first().isLetter() &&
            scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }
}

private fun fileUriPath(rawPath: String): String? =
    runCatching { URI(rawPath).path?.takeIf { it.isNotBlank() } }.getOrNull()
