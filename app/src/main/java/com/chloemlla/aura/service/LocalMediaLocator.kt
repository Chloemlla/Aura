package com.chloemlla.aura.service

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

private val LOCAL_MEDIA_SCHEMES = setOf("rawresource", "android.resource", "content", "file")

internal fun isLocalMediaLocator(locator: String): Boolean =
    locator.substringBefore(':', missingDelimiterValue = "")
        .lowercase(Locale.ROOT) in LOCAL_MEDIA_SCHEMES

/** Copies an app-accessible local media locator into a bounded staging file. */
internal fun stageLocalMediaLocator(
    context: Context,
    locator: String,
    tempDirectoryName: String,
    prefix: String,
    maxBytes: Long,
): File {
    val uri = Uri.parse(locator)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme in LOCAL_MEDIA_SCHEMES) { "Unsupported local media locator" }

    val advertisedLength = localMediaLength(context, uri)
    if (advertisedLengthExceeds(advertisedLength, maxBytes)) {
        throw MediaIngestionLimitExceeded("Media exceeds size limit ($maxBytes bytes)")
    }

    val tempDirectory = File(context.cacheDir, tempDirectoryName).apply { mkdirs() }
    val tempFile = File.createTempFile(prefix, ".tmp", tempDirectory)
    try {
        openLocalMedia(context, uri).use { input ->
            tempFile.outputStream().use { output ->
                if (copyStreamCapped(input, output, maxBytes) <= 0L) {
                    throw IllegalStateException("Local media is empty")
                }
            }
        }
        return tempFile
    } catch (error: Exception) {
        tempFile.delete()
        throw error
    }
}

private fun openLocalMedia(context: Context, uri: Uri): InputStream = when (uri.scheme?.lowercase(Locale.ROOT)) {
    "rawresource" -> {
        val resourceId = uri.schemeSpecificPart.trim('/').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid raw resource locator")
        context.resources.openRawResource(resourceId)
    }
    "file" -> FileInputStream(
        File(uri.path ?: throw IllegalArgumentException("Invalid file locator")),
    )
    "android.resource", "content" -> context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Local media could not be opened")
    else -> throw IllegalArgumentException("Unsupported local media locator")
}

private fun localMediaLength(context: Context, uri: Uri): Long = runCatching {
    when (uri.scheme?.lowercase(Locale.ROOT)) {
        "rawresource" -> {
            val resourceId = uri.schemeSpecificPart.trim('/').toInt()
            context.resources.openRawResourceFd(resourceId).use { it.length }
        }
        "file" -> File(uri.path.orEmpty()).length()
        "android.resource", "content" ->
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        else -> 0L
    }
}.getOrDefault(0L)
