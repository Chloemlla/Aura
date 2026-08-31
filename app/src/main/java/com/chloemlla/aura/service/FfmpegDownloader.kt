package com.chloemlla.aura.service

import android.content.Context
import android.os.Build
import com.chloemlla.aura.util.rethrowIfCancelled
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for accessing [FfmpegDownloader] from non-Hilt classes (e.g.
 * composable functions that need a one-shot FFmpeg path).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FfmpegDownloaderEntryPoint {
    fun ffmpegDownloader(): FfmpegDownloader
}

/**
 * On-demand FFmpeg binary downloader.
 *
 * Downloads a statically-linked FFmpeg binary for the device's CPU architecture
 * on first use, caches it in [context.noBackupFilesDir]/ffmpeg/, and verifies
 * the download with a SHA-256 checksum.
 *
 * The binary is provided by the arthenica/ffmpeg-kit project
 * (https://github.com/arthenica/ffmpeg-kit).
 */
@Singleton
class FfmpegDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val ffmpegDir: File
        get() = File(context.noBackupFilesDir, FFMPEG_DIR_NAME)

    private val ffmpegFile: File
        get() = File(ffmpegDir, FFMPEG_BINARY_NAME)

    /** Check whether a cached FFmpeg binary is already available and executable. */
    fun isFfmpegAvailable(): Boolean =
        ffmpegFile.exists() && ffmpegFile.canExecute()

    /**
     * Ensure FFmpeg is available, downloading it on first use.
     * @return [Result.success] with the FFmpeg binary [File], or [Result.failure]
     *         if the download or extraction fails.
     */
    suspend fun ensureFfmpeg(): Result<File> {
        if (isFfmpegAvailable()) {
            return Result.success(ffmpegFile)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                downloadAndExtract()
                if (!ffmpegFile.setExecutable(true)) {
                    throw IOException("Failed to set FFmpeg binary as executable: ${ffmpegFile.absolutePath}")
                }
                ffmpegFile
            }.onFailure { it.rethrowIfCancelled() }
        }
    }

    /**
     * Download the FFmpeg archive for the current device ABI and extract the binary.
     */
    private fun downloadAndExtract() {
        val arch = SUPPORTED_ABIS.firstOrNull { it in ABI_URL_SUFFIX }
            ?: throw IOException(
                "No FFmpeg binary available for device ABI(s): ${SUPPORTED_ABIS.contentToString()}"
            )

        ffmpegDir.mkdirs()
        val downloadUrl = "${FFMPEG_BASE_URL}/${ABI_URL_SUFFIX[arch]}"

        val request = Request.Builder()
            .url(downloadUrl)
            .addHeader("Accept", "application/octet-stream")
            .build()

        // Stream the download to a temp file while hashing it; the Response is
        // always closed, and the multi-MB archive is never slurped into memory.
        val archiveFile = File.createTempFile("ffmpeg-download", ".zip", ffmpegDir)
        try {
            val actualDigest = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "Failed to download FFmpeg: HTTP ${response.code} from $downloadUrl"
                    )
                }
                val body = response.body ?: throw IOException("Empty response body from $downloadUrl")
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    archiveFile.outputStream().use { output ->
                        val buffer = ByteArray(ARCHIVE_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ARCHIVE_BYTES) {
                                throw IOException("FFmpeg archive exceeds the download size limit")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }

            // SHA-256 integrity check. Fail closed: an ABI without a pinned digest is
            // rejected rather than trusted, and a mismatch deletes any partial file.
            val expectedDigest = EXPECTED_SHA256[arch]
                ?: throw IOException(
                    "No pinned SHA-256 digest configured for $arch; refusing to run a downloaded FFmpeg binary"
                )
            if (actualDigest != expectedDigest) {
                ffmpegFile.delete()
                throw IOException(
                    "FFmpeg download integrity check failed for $arch: " +
                        "expected SHA-256 $expectedDigest, got $actualDigest"
                )
            }

            // Extract the ffmpeg binary from the ZIP archive, bounded by the same
            // ArchiveExtractionGuard Aura applies to every untrusted archive.
            ffmpegFile.delete()
            val session = ArchiveExtractionGuard.newSession(EXTRACTION_LIMITS)
            var extracted = false
            ZipInputStream(archiveFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    session.beginEntry(entry.name, isLink = false)
                    val entryName = entry.name
                    val binaryName = entryName.substringAfterLast('/')
                    if (!entry.isDirectory && binaryName == FFMPEG_BINARY_NAME) {
                        ffmpegFile.outputStream().use { output ->
                            copyEntryBounded(zis, output, session, entry.compressedSize.coerceAtLeast(0L))
                        }
                        extracted = true
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!extracted) {
                // Clean up the partial directory so a retry re-downloads from scratch
                ffmpegFile.delete()
                throw IOException("FFmpeg binary not found in downloaded archive")
            }
        } finally {
            archiveFile.delete()
        }
    }

    /** Copies one zip entry through the guard so an oversized entry fails the extraction. */
    private fun copyEntryBounded(
        input: InputStream,
        output: OutputStream,
        session: ArchiveExtractionGuard.Session,
        compressedBytes: Long,
    ) {
        val buffer = ByteArray(ARCHIVE_BUFFER_BYTES)
        var expanded = 0L
        while (true) {
            // remainingEntryBudget() bounds against committed entries; also cap at the
            // current entry's own allowance so an oversized entry fails before filling disk.
            val budget = minOf(session.remainingEntryBudget(), EXTRACTION_LIMITS.maxEntryBytes - expanded)
            if (budget <= 0L) {
                // Probe byte decides whether the entry is exactly at the limit (ok) or over it (fail).
                if (input.read() < 0) break
                session.failEntryTooLarge()
            }
            val toRead = minOf(budget, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            output.write(buffer, 0, read)
            expanded += read
        }
        session.commitEntry(expanded, compressedBytes)
    }

    private companion object {
        private const val FFMPEG_DIR_NAME = "ffmpeg"
        private const val FFMPEG_BINARY_NAME = "ffmpeg"
        private const val ARCHIVE_BUFFER_BYTES = 64 * 1024
        private const val MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L

        /** Bounds for extracting the archive; the binary is large but the zip is small. */
        private val EXTRACTION_LIMITS = ArchiveExtractionLimits(
            maxEntries = 64,
            maxEntryBytes = 512L * 1024L * 1024L,
            maxTotalBytes = 1024L * 1024L * 1024L,
            maxCompressionRatio = 200,
        )

        // FFmpeg release source: arthenica/ffmpeg-kit
        private const val FFMPEG_BASE_URL =
            "https://github.com/arthenica/ffmpeg-kit/releases/download/v6.1.0"

        /** Maps Android ABI to the download archive filename. */
        private val ABI_URL_SUFFIX = mapOf(
            "arm64-v8a" to "ffmpeg-kit-min-6.1.0-android-lib-arm64.zip",
            "armeabi-v7a" to "ffmpeg-kit-min-6.1.0-android-lib-armv7.zip",
            "x86_64" to "ffmpeg-kit-min-6.1.0-android-lib-x86_64.zip",
        )

        /**
         * SHA-256 digests for the expected archives.
         * These must be updated when the FFmpeg version is bumped.
         * Obtain from the release page or compute locally:
         *   certutil -hashfile <archive> SHA256
         *
         * `null` means no digest is pinned for that ABI. [downloadAndExtract] then
         * refuses to run the binary rather than trust an unverified download, so the
         * digests must be filled in before that ABI can actually be used.
         */
        private val EXPECTED_SHA256 = mapOf<String, String?>(
            "arm64-v8a" to null,
            "armeabi-v7a" to null,
            "x86_64" to null,
        )

        // Exposed for testing
        internal val SUPPORTED_ABIS: Array<String> = Build.SUPPORTED_ABIS
    }
}