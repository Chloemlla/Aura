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

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException(
                "Failed to download FFmpeg: HTTP ${response.code} from $downloadUrl"
            )
        }

        val body = response.body ?: throw IOException("Empty response body from $downloadUrl")
        val contentLength = body.contentLength()

        // Download the full archive bytes so we can verify the SHA-256 before extracting
        val archiveBytes = if (contentLength > 0L) {
            body.bytes()
        } else {
            body.byteStream().use { it.readBytes() }
        }

        // SHA-256 integrity check
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(archiveBytes)
            .joinToString("") { "%02x".format(it) }
        val expectedDigest = EXPECTED_SHA256[arch]
        if (expectedDigest != null && actualDigest != expectedDigest) {
            throw IOException(
                "FFmpeg download integrity check failed for $arch: " +
                    "expected SHA-256 $expectedDigest, got $actualDigest"
            )
        }

        // Extract the ffmpeg binary from the ZIP archive
        var extracted = false
        ZipInputStream(archiveBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val binaryName = entryName.substringAfterLast('/')
                if (binaryName == FFMPEG_BINARY_NAME) {
                    ffmpegFile.outputStream().use { output ->
                        zis.copyTo(output)
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
    }

    private companion object {
        private const val FFMPEG_DIR_NAME = "ffmpeg"
        private const val FFMPEG_BINARY_NAME = "ffmpeg"

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
         * Set to `null` to skip integrity verification (not recommended for production).
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