package com.chloemlla.aura.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

internal enum class ExternalMediaKind {
    IMAGE,
    AUDIO,
}

internal data class ExternalMediaRequest(
    val uri: Uri,
    val declaredMimeType: String?,
    val expectedKind: ExternalMediaKind?,
)

internal data class IngestedExternalMedia(
    val uri: Uri,
    val kind: ExternalMediaKind,
)

internal data class PublishedExternalMedia(
    val file: File,
    val kind: ExternalMediaKind,
)

/**
 * Extracts one user-owned media URI from a SEND or EDIT intent.
 *
 * A URI from another app is accepted only when it is provider-backed with a
 * read grant, or is a local file URI. HTTP and other remote schemes stay
 * link-only and never enter the editor path.
 */
internal fun parseExternalMediaIntent(intent: Intent?): Result<ExternalMediaRequest>? {
    if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_EDIT)) return null

    val declaredMimeType = intent.type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (declaredMimeType?.contains("json") == true || intent.data?.toString()?.endsWith(".json", ignoreCase = true) == true) {
        return null
    }

    val clipData = intent.clipData
    if (clipData != null && clipData.itemCount > 1) {
        return Result.failure(MediaIngestionMediaRejected("Aura opens one file at a time"))
    }
    val clipUri = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    val uri = clipUri ?: when {
        intent.action == Intent.ACTION_EDIT -> intent.data ?: intent.streamUri()
        else -> intent.streamUri() ?: intent.data
    } ?: return Result.failure(MediaIngestionMediaRejected("No shared file was provided"))

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val isReadableUri = when (scheme) {
        "file" -> true
        "content" -> !uri.authority.isNullOrBlank() && intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        else -> false
    }
    if (!isReadableUri) {
        return Result.failure(MediaIngestionMediaRejected("The shared file is not readable"))
    }

    val expectedKind = when {
        declaredMimeType.isNullOrBlank() || declaredMimeType == "*/*" -> null
        declaredMimeType.startsWith("image/") -> ExternalMediaKind.IMAGE
        declaredMimeType.startsWith("audio/") -> ExternalMediaKind.AUDIO
        else -> return Result.failure(MediaIngestionMediaRejected("Aura accepts image and audio files"))
    }
    return Result.success(
        ExternalMediaRequest(
            uri = uri,
            declaredMimeType = declaredMimeType,
            expectedKind = expectedKind,
        ),
    )
}

/** Copies, sniffs, and publishes one external file into the app-owned share outbox. */
internal fun ingestExternalMedia(
    context: Context,
    request: ExternalMediaRequest,
): IngestedExternalMedia {
    ShareOutbox.pruneStaleFiles(context)
    val outputDirectory = ShareOutbox.directory(context, "external_media")
    val token = UUID.randomUUID().toString().replace('-', '_')
    var publishedFile: File? = null

    try {
        val resolver = context.contentResolver
        val sourceName = displayName(resolver, request.uri)
            ?: request.uri.lastPathSegment
            ?: "aura_media"
        val sourceStream = resolver.openInputStream(request.uri)
            ?: throw MediaIngestionMediaRejected("The shared file could not be opened")

        val published = sourceStream.use { input ->
            copySniffedExternalMedia(
                input = input,
                outputDirectory = outputDirectory,
                sourceName = sourceName,
                expectedKind = request.expectedKind,
                token = token,
            )
        }
        publishedFile = published.file

        return IngestedExternalMedia(
            uri = ShareOutbox.uriFor(context, published.file),
            kind = published.kind,
        )
    } catch (error: Exception) {
        publishedFile?.delete()
        throw error
    }
}

internal fun copySniffedExternalMedia(
    input: InputStream,
    outputDirectory: File,
    sourceName: String,
    expectedKind: ExternalMediaKind?,
    token: String = UUID.randomUUID().toString().replace('-', '_'),
): PublishedExternalMedia {
    outputDirectory.mkdirs()
    val tempFile = File(outputDirectory, ".external_${token}.part")
    var publishedFile: File? = null
    var resolvedKind: ExternalMediaKind? = null
    try {
        BufferedInputStream(input).use { bufferedInput ->
            val header = readHeader(bufferedInput)
            val sniffed = sniffMediaType(header)
                ?: throw MediaIngestionMediaRejected("The shared file type could not be verified")
            val kind = resolveKind(sniffed.family, expectedKind)
            resolvedKind = kind
            val outputName = normalizeMediaFileName(sanitizeFileName(sourceName), sniffed)
            publishedFile = File(outputDirectory, "external_${token}_$outputName")
            FileOutputStream(tempFile).use { output ->
                output.write(header)
                copyStreamCapped(
                    input = bufferedInput,
                    output = output,
                    maxBytes = MAX_EXTERNAL_MEDIA_BYTES - header.size,
                )
            }
        }
        val target = requireNotNull(publishedFile)
        if (!tempFile.renameTo(target)) {
            throw MediaIngestionMediaRejected("The shared file could not be prepared")
        }
        return PublishedExternalMedia(file = target, kind = requireNotNull(resolvedKind))
    } catch (error: Exception) {
        tempFile.delete()
        publishedFile?.delete()
        throw error
    }
}

private fun resolveKind(
    family: MediaFamily,
    expectedKind: ExternalMediaKind?,
): ExternalMediaKind = when (family) {
    MediaFamily.IMAGE -> {
        if (expectedKind == ExternalMediaKind.AUDIO) {
            throw MediaIngestionMediaRejected("The shared file is not an audio file")
        }
        ExternalMediaKind.IMAGE
    }
    MediaFamily.AUDIO -> {
        if (expectedKind == ExternalMediaKind.IMAGE) {
            throw MediaIngestionMediaRejected("The shared file is not an image file")
        }
        ExternalMediaKind.AUDIO
    }
    MediaFamily.CONTAINER -> {
        if (expectedKind != ExternalMediaKind.AUDIO) {
            throw MediaIngestionMediaRejected("The shared container is not identified as audio")
        }
        ExternalMediaKind.AUDIO
    }
}

private fun displayName(
    resolver: android.content.ContentResolver,
    uri: Uri,
): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        index.takeIf { it >= 0 }?.let(cursor::getString)
    }
}.getOrNull()

private fun sanitizeFileName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(SHARED_FILE_NAME_REGEX, "_")
        .take(MAX_SHARED_FILE_NAME_LENGTH)
        .ifBlank { "aura_media" }

private fun readHeader(input: InputStream): ByteArray {
    val header = ByteArray(EXTERNAL_MEDIA_HEADER_BYTES)
    var offset = 0
    while (offset < header.size) {
        val read = input.read(header, offset, header.size - offset)
        if (read <= 0) break
        offset += read
    }
    return header.copyOf(offset)
}

@Suppress("DEPRECATION")
private fun Intent.streamUri(): Uri? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}.getOrNull()

private val SHARED_FILE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
private const val EXTERNAL_MEDIA_HEADER_BYTES = 64
private const val MAX_SHARED_FILE_NAME_LENGTH = 96
private const val MAX_EXTERNAL_MEDIA_BYTES = 64L * 1024 * 1024
