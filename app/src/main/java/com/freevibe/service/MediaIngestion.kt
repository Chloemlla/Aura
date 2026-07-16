package com.freevibe.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MediaIngestionLimitExceeded(
    message: String,
) : IOException(message)

class MediaIngestionImageRejected(
    message: String,
) : IOException(message)

internal fun advertisedLengthExceeds(
    contentLength: Long,
    maxBytes: Long,
): Boolean = contentLength in 1..Long.MAX_VALUE && contentLength > maxBytes

internal fun copyStreamCapped(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    bufferSize: Int = DEFAULT_MEDIA_INGESTION_BUFFER_BYTES,
): Long {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        copied += read
        if (copied > maxBytes) {
            throw MediaIngestionLimitExceeded("Media exceeds size limit ($maxBytes bytes)")
        }
        output.write(buffer, 0, read)
    }
    return copied
}

internal fun readStreamCapped(
    input: InputStream,
    maxBytes: Long,
): ByteArray {
    val output = ByteArrayOutputStream()
    copyStreamCapped(input, output, maxBytes)
    return output.toByteArray()
}

internal enum class MediaFamily {
    IMAGE,
    AUDIO,
}

internal data class SniffedMediaType(
    val family: MediaFamily,
    val mimeType: String,
    val extension: String,
)

enum class MediaIngestionImageFlow {
    AUTO_ROTATION,
    LOCAL_APPLY,
    EDITOR,
    COMMUNITY_WALLPAPER_UPLOAD,
}

internal enum class IngestedImageFormat(
    val displayName: String,
    val mimeTypes: Set<String>,
    val extensions: Set<String>,
    val minSdk: Int,
) {
    JPEG(
        displayName = "JPEG",
        mimeTypes = setOf("image/jpeg", "image/jpg"),
        extensions = setOf("jpg", "jpeg"),
        minSdk = Build.VERSION_CODES.O,
    ),
    PNG(
        displayName = "PNG",
        mimeTypes = setOf("image/png"),
        extensions = setOf("png"),
        minSdk = Build.VERSION_CODES.O,
    ),
    WEBP(
        displayName = "WebP",
        mimeTypes = setOf("image/webp"),
        extensions = setOf("webp"),
        minSdk = Build.VERSION_CODES.O,
    ),
    GIF(
        displayName = "GIF",
        mimeTypes = setOf("image/gif"),
        extensions = setOf("gif"),
        minSdk = Build.VERSION_CODES.O,
    ),
    HEIF(
        displayName = "HEIF",
        mimeTypes = setOf("image/heif", "image/heic"),
        extensions = setOf("heif", "heic"),
        minSdk = Build.VERSION_CODES.O,
    ),
    AVIF(
        displayName = "AVIF",
        mimeTypes = setOf("image/avif"),
        extensions = setOf("avif"),
        minSdk = ANDROID_14_API,
    ),
}

internal data class ImageIngestionPolicy(
    val flow: MediaIngestionImageFlow,
    val inputFormats: Set<IngestedImageFormat>,
    val outputMimeType: String? = null,
    val stripsMetadata: Boolean = false,
) {
    val acceptedMimeTypes: Set<String> = inputFormats.flatMap { it.mimeTypes }.toSet()
}

internal data class ImageFormatSupport(
    val supported: Boolean,
    val format: IngestedImageFormat?,
    val message: String,
    val outputMimeType: String?,
    val stripsMetadata: Boolean,
)

internal fun imageIngestionPolicy(flow: MediaIngestionImageFlow): ImageIngestionPolicy =
    when (flow) {
        MediaIngestionImageFlow.AUTO_ROTATION,
        MediaIngestionImageFlow.LOCAL_APPLY,
        MediaIngestionImageFlow.EDITOR ->
            ImageIngestionPolicy(
                flow = flow,
                inputFormats = setOf(
                    IngestedImageFormat.JPEG,
                    IngestedImageFormat.PNG,
                    IngestedImageFormat.WEBP,
                    IngestedImageFormat.GIF,
                    IngestedImageFormat.HEIF,
                    IngestedImageFormat.AVIF,
                ),
            )
        MediaIngestionImageFlow.COMMUNITY_WALLPAPER_UPLOAD ->
            ImageIngestionPolicy(
                flow = flow,
                inputFormats = setOf(
                    IngestedImageFormat.JPEG,
                    IngestedImageFormat.PNG,
                    IngestedImageFormat.WEBP,
                    IngestedImageFormat.HEIF,
                    IngestedImageFormat.AVIF,
                ),
                outputMimeType = "image/jpeg",
                stripsMetadata = true,
            )
    }

internal fun imageFormatSupportForFlow(
    flow: MediaIngestionImageFlow,
    mimeType: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): ImageFormatSupport {
    val policy = imageIngestionPolicy(flow)
    val normalized = normalizeMimeType(mimeType)
    val format = imageFormatForMimeType(normalized)
    if (format == null || format !in policy.inputFormats) {
        return ImageFormatSupport(
            supported = false,
            format = format,
            message = "Choose a ${acceptedImageFormatSummary(flow)} image.",
            outputMimeType = policy.outputMimeType,
            stripsMetadata = policy.stripsMetadata,
        )
    }
    if (sdkInt < format.minSdk) {
        val message = if (format == IngestedImageFormat.AVIF) {
            if (flow == MediaIngestionImageFlow.COMMUNITY_WALLPAPER_UPLOAD) {
                "AVIF images require Android 14 or newer so Aura can decode and scrub them safely."
            } else {
                "AVIF images require Android 14 or newer so Aura can decode them."
            }
        } else {
            "${format.displayName} images are not supported on this Android version."
        }
        return ImageFormatSupport(
            supported = false,
            format = format,
            message = message,
            outputMimeType = policy.outputMimeType,
            stripsMetadata = policy.stripsMetadata,
        )
    }
    return ImageFormatSupport(
        supported = true,
        format = format,
        message = if (policy.stripsMetadata) {
            "Aura will transcode this image to JPEG before upload so embedded metadata and location tags are not kept."
        } else {
            "${format.displayName} image supported."
        },
        outputMimeType = policy.outputMimeType,
        stripsMetadata = policy.stripsMetadata,
    )
}

internal fun imageMimeTypeFromExtension(extension: String): String? {
    val normalized = extension.trim().trimStart('.').lowercase(Locale.ROOT)
    return IngestedImageFormat.entries
        .firstOrNull { normalized in it.extensions }
        ?.mimeTypes
        ?.first()
}

internal fun imageFormatForMimeType(mimeType: String): IngestedImageFormat? {
    val normalized = normalizeMimeType(mimeType)
    return IngestedImageFormat.entries.firstOrNull { normalized in it.mimeTypes }
}

internal fun acceptedImageFormatSummary(flow: MediaIngestionImageFlow): String =
    imageIngestionPolicy(flow).inputFormats
        .map { it.displayName }
        .toHumanList()

internal fun imageFormatSupportForInput(
    flow: MediaIngestionImageFlow,
    header: ByteArray,
    declaredMimeType: String? = null,
    extension: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): ImageFormatSupport {
    val sniffed = sniffMediaType(header.take(MEDIA_SNIFF_BYTES).toByteArray())
    val declared = declaredMimeType?.let(::normalizeMimeType).orEmpty()
    val mimeType = when {
        sniffed != null -> sniffed.mimeType
        imageFormatForMimeType(declared) != null -> declared
        declared.isNotBlank() && declared != "application/octet-stream" -> declared
        !extension.isNullOrBlank() -> imageMimeTypeFromExtension(extension).orEmpty()
        else -> ""
    }
    return imageFormatSupportForFlow(flow, mimeType, sdkInt)
}

internal fun requireImageFormatSupport(
    flow: MediaIngestionImageFlow,
    header: ByteArray,
    declaredMimeType: String? = null,
    extension: String? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): ImageFormatSupport = imageFormatSupportForInput(
    flow = flow,
    header = header,
    declaredMimeType = declaredMimeType,
    extension = extension,
    sdkInt = sdkInt,
).also { support ->
    if (!support.supported) throw MediaIngestionImageRejected(support.message)
}

internal fun decodeImageBytesForFlow(
    bytes: ByteArray,
    flow: MediaIngestionImageFlow,
    declaredMimeType: String? = null,
    extension: String? = null,
    maxLongEdge: Int? = null,
): Bitmap {
    val support = requireImageFormatSupport(flow, bytes, declaredMimeType, extension)
    return decodeImageBytes(bytes, maxLongEdge)
        ?: throw MediaIngestionImageRejected(
            "${support.format?.displayName ?: "Image"} image could not be decoded on this device.",
        )
}

internal fun decodeImageUriForFlow(
    context: Context,
    uri: Uri,
    flow: MediaIngestionImageFlow,
    maxLongEdge: Int? = null,
): Bitmap {
    val header = context.contentResolver.openInputStream(uri)?.use(::readImageHeader) ?: byteArrayOf()
    val extension = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")
    val support = requireImageFormatSupport(
        flow = flow,
        header = header,
        declaredMimeType = context.contentResolver.getType(uri),
        extension = extension,
    )
    return decodeImageUri(context, uri, maxLongEdge)
        ?: throw MediaIngestionImageRejected(
            "${support.format?.displayName ?: "Image"} image could not be decoded on this device.",
        )
}

internal fun decodeImageFileForFlow(
    file: File,
    flow: MediaIngestionImageFlow,
    maxLongEdge: Int? = null,
): Bitmap {
    val header = FileInputStream(file).use(::readImageHeader)
    val support = requireImageFormatSupport(
        flow = flow,
        header = header,
        extension = file.extension,
    )
    return decodeImageFile(file, maxLongEdge)
        ?: throw MediaIngestionImageRejected(
            "${support.format?.displayName ?: "Image"} image could not be decoded on this device.",
        )
}

internal fun decodeImageBytes(
    bytes: ByteArray,
    maxLongEdge: Int? = null,
): Bitmap? {
    if (bytes.isEmpty()) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxLongEdge)
    } else {
        decodeBytesWithBitmapFactory(bytes, maxLongEdge)
    }
}

internal fun decodeImageUri(
    context: Context,
    uri: Uri,
    maxLongEdge: Int? = null,
): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(ImageDecoder.createSource(context.contentResolver, uri), maxLongEdge)
    } else {
        decodeStreamWithBitmapFactory(
            maxLongEdge = maxLongEdge,
            openStream = { context.contentResolver.openInputStream(uri) },
        )
    }

internal fun decodeImageFile(
    file: File,
    maxLongEdge: Int? = null,
): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(ImageDecoder.createSource(file), maxLongEdge)
    } else {
        decodeFileWithBitmapFactory(file, maxLongEdge)
    }

internal fun sniffMediaType(header: ByteArray): SniffedMediaType? {
    if (header.startsWith(0xFF, 0xD8, 0xFF)) {
        return SniffedMediaType(MediaFamily.IMAGE, "image/jpeg", "jpg")
    }
    if (header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
        return SniffedMediaType(MediaFamily.IMAGE, "image/png", "png")
    }
    if (header.asciiAt(0, "GIF87a") || header.asciiAt(0, "GIF89a")) {
        return SniffedMediaType(MediaFamily.IMAGE, "image/gif", "gif")
    }
    if (header.asciiAt(0, "RIFF") && header.asciiAt(8, "WEBP")) {
        return SniffedMediaType(MediaFamily.IMAGE, "image/webp", "webp")
    }
    if (header.hasAacAdtsSync()) {
        return SniffedMediaType(MediaFamily.AUDIO, "audio/aac", "aac")
    }
    if (header.asciiAt(0, "ID3") || header.hasMp3FrameSync()) {
        return SniffedMediaType(MediaFamily.AUDIO, "audio/mpeg", "mp3")
    }
    if (header.asciiAt(0, "OggS")) {
        return SniffedMediaType(MediaFamily.AUDIO, "audio/ogg", "ogg")
    }
    if (header.asciiAt(0, "RIFF") && header.asciiAt(8, "WAVE")) {
        return SniffedMediaType(MediaFamily.AUDIO, "audio/wav", "wav")
    }
    if (header.asciiAt(0, "fLaC")) {
        return SniffedMediaType(MediaFamily.AUDIO, "audio/flac", "flac")
    }
    if (header.asciiAt(4, "ftyp")) {
        return sniffFtypBrand(header)
    }
    return null
}

private fun sniffFtypBrand(header: ByteArray): SniffedMediaType {
    val brand = if (header.size >= 12) {
        String(header, 8, 4, Charsets.US_ASCII).trim().lowercase(Locale.ROOT)
    } else {
        ""
    }
    return when {
        brand.startsWith("heic") || brand.startsWith("heix") || brand == "mif1" ->
            SniffedMediaType(MediaFamily.IMAGE, "image/heif", "heic")
        brand.startsWith("avif") || brand.startsWith("avis") ->
            SniffedMediaType(MediaFamily.IMAGE, "image/avif", "avif")
        else ->
            SniffedMediaType(MediaFamily.AUDIO, "audio/mp4", "m4a")
    }
}

internal fun sniffMediaFile(file: File): SniffedMediaType? {
    val header = ByteArray(MEDIA_SNIFF_BYTES)
    val read = FileInputStream(file).use { it.read(header) }
    return if (read > 0) sniffMediaType(header.copyOf(read)) else null
}

internal fun requireSniffedMediaFile(
    file: File,
    expectedFamily: MediaFamily,
    label: String,
): SniffedMediaType {
    val sniffed = sniffMediaFile(file)
        ?: throw IOException("$label content type could not be verified")
    if (sniffed.family != expectedFamily) {
        throw IOException("$label content type mismatch: expected ${expectedFamily.name.lowercase(Locale.ROOT)}")
    }
    return sniffed
}

internal fun normalizeMediaFileName(
    fileName: String,
    sniffed: SniffedMediaType,
): String {
    val trimmed = fileName.trim().ifBlank { "aura_media" }
    val base = trimmed.substringBeforeLast('.', trimmed).ifBlank { "aura_media" }
    return "$base.${sniffed.extension}"
}

private const val DEFAULT_MEDIA_INGESTION_BUFFER_BYTES = 8 * 1024
private const val MEDIA_SNIFF_BYTES = 64
private const val ANDROID_14_API = 34

private fun normalizeMimeType(mimeType: String): String =
    mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)

private fun readImageHeader(input: InputStream): ByteArray {
    val header = ByteArray(MEDIA_SNIFF_BYTES)
    val read = input.read(header)
    return if (read > 0) header.copyOf(read) else byteArrayOf()
}

private fun List<String>.toHumanList(): String =
    when (size) {
        0 -> ""
        1 -> single()
        2 -> "${this[0]} or ${this[1]}"
        else -> dropLast(1).joinToString(", ") + ", or " + last()
    }

@Suppress("NewApi")
private fun decodeWithImageDecoder(
    source: ImageDecoder.Source,
    maxLongEdge: Int?,
): Bitmap? =
    try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val targetLongEdge = maxLongEdge?.takeIf { it > 0 }
            val width = info.size.width
            val height = info.size.height
            if (targetLongEdge != null && width > 0 && height > 0) {
                val longEdge = max(width, height)
                if (longEdge > targetLongEdge) {
                    val scale = targetLongEdge.toFloat() / longEdge.toFloat()
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
    } catch (_: Exception) {
        null
    }

private fun decodeBytesWithBitmapFactory(
    bytes: ByteArray,
    maxLongEdge: Int?,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun decodeStreamWithBitmapFactory(
    maxLongEdge: Int?,
    openStream: () -> InputStream?,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun decodeFileWithBitmapFactory(
    file: File,
    maxLongEdge: Int?,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun decodeSampleSize(
    rawWidth: Int,
    rawHeight: Int,
    maxLongEdge: Int?,
): Int {
    val target = maxLongEdge?.takeIf { it > 0 } ?: return 1
    var sample = 1
    var width = rawWidth
    var height = rawHeight
    while (max(width, height) / 2 >= target && sample < (1 shl 28)) {
        sample *= 2
        width /= 2
        height /= 2
    }
    return sample.coerceAtLeast(1)
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> this[index].toInt() and 0xFF == expected[index] }

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean {
    if (offset < 0 || size < offset + value.length) return false
    return value.indices.all { index -> this[offset + index].toInt() == value[index].code }
}

private fun ByteArray.hasMp3FrameSync(): Boolean {
    if (size < 2) return false
    val first = this[0].toInt() and 0xFF
    val second = this[1].toInt() and 0xFF
    val layerBits = second and 0x06
    return first == 0xFF && second and 0xE0 == 0xE0 && layerBits != 0
}

private fun ByteArray.hasAacAdtsSync(): Boolean {
    if (size < 2) return false
    val first = this[0].toInt() and 0xFF
    val second = this[1].toInt() and 0xFF
    return first == 0xFF && second and 0xF6 == 0xF0
}
