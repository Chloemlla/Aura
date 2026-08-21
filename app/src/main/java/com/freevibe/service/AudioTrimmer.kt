package com.freevibe.service

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.freevibe.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val FFMPEG_TIMEOUT_SECONDS = 120L
private const val FFMPEG_LOG_DRAIN_LIMIT_BYTES = 256 * 1024 // cap stderr-draining to 256 KB so a misbehaving FFmpeg can't OOM us
private const val LOSSLESS_VERIFY_MAX_BYTES = 64L * 1024 * 1024
private const val LOSSLESS_FALLBACK_PACKET_BYTES = 1024 * 1024
private val SANITIZE_REGEX = Regex("[^a-zA-Z0-9_-]")

enum class AudioFadeCurve(val ffmpegValue: String) {
    LINEAR("tri"),
    SMOOTH("qsin"),
    EXPONENTIAL("exp"),
}

enum class AudioExportFormat(
    val extension: String,
    val bitratesKbps: List<Int>,
    val defaultBitrateKbps: Int?,
) {
    MP3("mp3", listOf(96, 128, 192, 256, 320), 192),
    OGG("ogg", listOf(96, 128, 192, 256), 192),
    OPUS("opus", listOf(48, 64, 96, 128, 192), 96),
    WAV("wav", emptyList(), null),
    FLAC("flac", emptyList(), null),
    M4A("m4a", listOf(96, 128, 192, 256), 192),
}

internal fun isTrimDurationWithinOneAudioFrame(
    expectedDurationMs: Long,
    actualDurationMs: Long,
    frameDurationMs: Long,
): Boolean = abs(expectedDurationMs - actualDurationMs) <= frameDurationMs.coerceAtLeast(1L)

internal fun isLosslessCutAllowed(
    fadeInMs: Long,
    fadeOutMs: Long,
    normalizationApplied: Boolean,
): Boolean = fadeInMs == 0L && fadeOutMs == 0L && !normalizationApplied

internal fun losslessCutExportFormat(inputPath: String?): AudioExportFormat? {
    val extension = inputPath
        ?.let(::File)
        ?.extension
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return null
    return AudioExportFormat.entries.firstOrNull { it.extension == extension }
}

internal fun areEncodedAudioPacketsContiguousCopy(
    sourcePackets: List<ByteArray>,
    outputPackets: List<ByteArray>,
): Boolean {
    if (sourcePackets.isEmpty() || outputPackets.isEmpty() || outputPackets.size > sourcePackets.size) {
        return false
    }
    val lastStart = sourcePackets.size - outputPackets.size
    return (0..lastStart).any { start ->
        outputPackets.indices.all { offset ->
            sourcePackets[start + offset].contentEquals(outputPackets[offset])
        }
    }
}

private fun ffmpegSeconds(milliseconds: Long): String =
    String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0)

internal fun buildFfmpegTrimCommand(
    ffmpegPath: String,
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long,
    fadeInMs: Long,
    fadeOutMs: Long,
    fadeCurve: AudioFadeCurve,
    exportFormat: AudioExportFormat,
    bitrateKbps: Int?,
): List<String> {
    require(endMs > startMs) { "End time must be after start time" }
    val durationMs = endMs - startMs
    val filters = mutableListOf(
        "atrim=start=${ffmpegSeconds(startMs)}:end=${ffmpegSeconds(endMs)}",
        "asetpts=PTS-STARTPTS",
    )
    if (fadeInMs > 0L) {
        filters += "afade=t=in:st=0:d=${ffmpegSeconds(fadeInMs.coerceAtMost(durationMs))}:curve=${fadeCurve.ffmpegValue}"
    }
    if (fadeOutMs > 0L) {
        val duration = fadeOutMs.coerceAtMost(durationMs)
        filters += "afade=t=out:st=${ffmpegSeconds((durationMs - duration).coerceAtLeast(0L))}:d=${ffmpegSeconds(duration)}:curve=${fadeCurve.ffmpegValue}"
    }

    val codec = when (exportFormat) {
        AudioExportFormat.MP3 -> listOf("-c:a", "libmp3lame", "-b:a", "${bitrateKbps ?: exportFormat.defaultBitrateKbps}k")
        AudioExportFormat.OGG -> listOf("-c:a", "libvorbis", "-b:a", "${bitrateKbps ?: exportFormat.defaultBitrateKbps}k")
        AudioExportFormat.OPUS -> listOf("-c:a", "libopus", "-b:a", "${bitrateKbps ?: exportFormat.defaultBitrateKbps}k")
        AudioExportFormat.WAV -> listOf("-c:a", "pcm_s16le")
        AudioExportFormat.FLAC -> listOf("-c:a", "flac")
        AudioExportFormat.M4A -> listOf("-c:a", "aac", "-b:a", "${bitrateKbps ?: exportFormat.defaultBitrateKbps}k")
    }

    return mutableListOf(
        ffmpegPath,
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", inputPath,
        "-map", "0:a:0",
        "-vn",
        "-af", filters.joinToString(","),
    ) + codec + if (exportFormat == AudioExportFormat.OGG) {
        listOf("-metadata", "ANDROID_LOOP=true")
    } else {
        emptyList()
    } + outputPath
}

internal fun buildFfmpegStreamCopyTrimCommand(
    ffmpegPath: String,
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long,
    outputFormat: AudioExportFormat,
): List<String> {
    require(startMs >= 0L) { "Start time must not be negative" }
    require(endMs > startMs) { "End time must be after start time" }
    val metadata = if (outputFormat == AudioExportFormat.OGG) {
        listOf("-metadata", "ANDROID_LOOP=true")
    } else {
        emptyList()
    }
    return listOf(
        ffmpegPath,
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-ss", ffmpegSeconds(startMs),
        "-i", inputPath,
        "-map", "0:a:0",
        "-vn",
        "-t", ffmpegSeconds(endMs - startMs),
        "-c:a", "copy",
    ) + metadata + outputPath
}

/**
 * Drain stdout/stderr from an FFmpeg process without buffering the full stream.
 * FFmpeg with redirectErrorStream() can emit MBs of progress text per invocation; the
 * previous `readText()` would store all of it in a String only to throw it away. We
 * only need to keep the pipe flowing so the process doesn't block on a full pipe buffer.
 */
private fun java.io.InputStream.drainBounded(limit: Int = FFMPEG_LOG_DRAIN_LIMIT_BYTES) {
    use { input ->
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n <= 0) break
            total += n
            // Keep reading (and discarding) once we pass the log-retention limit so the
            // process pipe never blocks — we just stop counting.
            if (total > Int.MAX_VALUE / 2) total = limit
        }
    }
}

@Singleton
class AudioTrimmer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Decode, sample-trim, process, and encode audio in one FFmpeg pass. */
    suspend fun trim(
        inputPath: String,
        startMs: Long,
        endMs: Long,
        outputFileName: String,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0,
        fadeCurve: AudioFadeCurve = AudioFadeCurve.LINEAR,
        exportFormat: AudioExportFormat = AudioExportFormat.MP3,
        bitrateKbps: Int? = exportFormat.defaultBitrateKbps,
        normalizationApplied: Boolean = false,
        losslessCut: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        var pendingOutput: File? = null
        runCatching {
            require(endMs > startMs) { "End time must be after start time" }
            require(!losslessCut || isLosslessCutAllowed(fadeInMs, fadeOutMs, normalizationApplied)) {
                "Lossless cut requires fades and normalization to be disabled"
            }
            val effectiveFormat = if (losslessCut) {
                losslessCutExportFormat(inputPath)
                    ?: throw Exception("Lossless cut requires a supported audio file format")
            } else {
                exportFormat
            }
            require(losslessCut || bitrateKbps == null || bitrateKbps in exportFormat.bitratesKbps) {
                "Unsupported ${exportFormat.name} bitrate: $bitrateKbps kbps"
            }

            val outputDir = File(context.cacheDir, "trimmed")
            outputDir.mkdirs()
            val outputFile = File(
                outputDir,
                "${outputFileName.replace(SANITIZE_REGEX, "_")}.${effectiveFormat.extension}",
            )
            pendingOutput = outputFile
            outputFile.delete()
            val ffmpegInfo = getYtdlpFfmpeg() ?: throw Exception("FFmpeg not available")
            val (ffmpegPath, ldLibPath) = ffmpegInfo
            val command = if (losslessCut) {
                buildFfmpegStreamCopyTrimCommand(
                    ffmpegPath = ffmpegPath.absolutePath,
                    inputPath = inputPath,
                    outputPath = outputFile.absolutePath,
                    startMs = startMs,
                    endMs = endMs,
                    outputFormat = effectiveFormat,
                )
            } else {
                buildFfmpegTrimCommand(
                    ffmpegPath = ffmpegPath.absolutePath,
                    inputPath = inputPath,
                    outputPath = outputFile.absolutePath,
                    startMs = startMs,
                    endMs = endMs,
                    fadeInMs = fadeInMs,
                    fadeOutMs = fadeOutMs,
                    fadeCurve = fadeCurve,
                    exportFormat = exportFormat,
                    bitrateKbps = bitrateKbps,
                )
            }

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(outputDir)
            if (ldLibPath.isNotEmpty()) processBuilder.environment()["LD_LIBRARY_PATH"] = ldLibPath
            val process = processBuilder.start()
            try {
                process.inputStream.drainBounded()
                val completed = process.waitFor(FFMPEG_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    throw Exception("Audio export timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
                }
                val exitCode = process.exitValue()
                if (exitCode != 0 || !outputFile.exists() || outputFile.length() <= 100L) {
                    throw Exception("Audio export failed (exit $exitCode)")
                }
                if (losslessCut && !verifyLosslessPacketCopy(inputPath, outputFile.absolutePath)) {
                    throw Exception("Lossless export verification failed: copied audio bytes changed")
                }
                val timing = readEncodedAudioTiming(outputFile.absolutePath)
                if (
                    timing.durationMs > 0L &&
                    !isTrimDurationWithinOneAudioFrame(
                        expectedDurationMs = endMs - startMs,
                        actualDurationMs = timing.durationMs,
                        frameDurationMs = timing.frameDurationMs,
                    )
                ) {
                    throw Exception(
                        "Audio export duration ${timing.durationMs}ms exceeded one-frame trim tolerance",
                    )
                }
            } finally {
                try { process.destroy() } catch (_: Exception) {}
            }
            outputFile.absolutePath
        }.onFailure {
            pendingOutput?.delete()
            it.rethrowIfCancelled()
        }
    }

    private fun verifyLosslessPacketCopy(inputPath: String, outputPath: String): Boolean {
        val outputPackets = readEncodedAudioPackets(outputPath)
        if (outputPackets.isEmpty()) return false
        val prefix = IntArray(outputPackets.size)
        for (index in 1 until outputPackets.size) {
            var candidate = prefix[index - 1]
            while (candidate > 0 && !outputPackets[index].contentEquals(outputPackets[candidate])) {
                candidate = prefix[candidate - 1]
            }
            if (outputPackets[index].contentEquals(outputPackets[candidate])) candidate++
            prefix[index] = candidate
        }

        var matched = 0
        var found = false
        forEachEncodedAudioPacket(inputPath) { sourcePacket ->
            while (matched > 0 && !sourcePacket.contentEquals(outputPackets[matched])) {
                matched = prefix[matched - 1]
            }
            if (sourcePacket.contentEquals(outputPackets[matched])) matched++
            if (matched == outputPackets.size) {
                found = true
                false
            } else {
                true
            }
        }
        return found
    }

    private fun readEncodedAudioPackets(path: String): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var totalBytes = 0L
        forEachEncodedAudioPacket(path) { packet ->
            totalBytes += packet.size
            if (totalBytes > LOSSLESS_VERIFY_MAX_BYTES) {
                throw Exception("Lossless export verification input is too large")
            }
            packets += packet
            true
        }
        return packets
    }

    private fun forEachEncodedAudioPacket(path: String, block: (ByteArray) -> Boolean): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(trackIndex)
            val fallbackBuffer = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                ByteBuffer.allocate(LOSSLESS_FALLBACK_PACKET_BYTES)
            } else {
                null
            }
            while (true) {
                val buffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val sampleSize = extractor.sampleSize
                    if (sampleSize < 0L) break
                    require(sampleSize <= Int.MAX_VALUE) { "Encoded audio sample is too large" }
                    ByteBuffer.allocate(sampleSize.toInt())
                } else {
                    fallbackBuffer!!.apply { clear() }
                }
                val bytesRead = extractor.readSampleData(buffer, 0)
                if (bytesRead < 0) break
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && bytesRead == buffer.capacity()) {
                    throw Exception("Encoded audio sample exceeds the verifier buffer")
                }
                if (bytesRead > 0 && !block(buffer.array().copyOf(bytesRead))) return false
                if (!extractor.advance()) break
            }
            true
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private data class EncodedAudioTiming(val durationMs: Long, val frameDurationMs: Long)

    private fun readEncodedAudioTiming(path: String): EncodedAudioTiming {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return EncodedAudioTiming(0L, 1L)
            val format = extractor.getTrackFormat(trackIndex)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1_000L).coerceAtLeast(0L)
            } else {
                0L
            }
            extractor.selectTrack(trackIndex)
            val firstSampleUs = extractor.sampleTime
            val frameDurationMs = if (firstSampleUs >= 0L && extractor.advance()) {
                val secondSampleUs = extractor.sampleTime
                if (secondSampleUs > firstSampleUs) {
                    ((secondSampleUs - firstSampleUs + 999L) / 1_000L).coerceAtLeast(1L)
                } else {
                    1L
                }
            } else {
                1L
            }
            EncodedAudioTiming(durationMs, frameDurationMs)
        } catch (_: Exception) {
            EncodedAudioTiming(0L, 1L)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Get FFmpeg binary and LD_LIBRARY_PATH from yt-dlp via reflection */
    private fun getYtdlpFfmpeg(): Pair<File, String>? {
        return try {
            val ytdl = com.yausername.youtubedl_android.YoutubeDL.getInstance()
            val cls = ytdl::class.java
            val ffmpegField = cls.getDeclaredField("ffmpegPath").apply { isAccessible = true }
            val ldField = cls.getDeclaredField("ENV_LD_LIBRARY_PATH").apply { isAccessible = true }
            // Try instance field first, then static
            val ffmpegPath = (ffmpegField.get(ytdl) ?: ffmpegField.get(null)) as? File ?: return null
            val ldPath = (ldField.get(ytdl) ?: ldField.get(null)) as? String ?: ""
            if (ffmpegPath.exists()) Pair(ffmpegPath, ldPath) else null
        } catch (_: Exception) { null }
    }

    /** Apply volume normalization via FFmpeg loudnorm filter */
    suspend fun normalize(inputPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ffmpegInfo = getYtdlpFfmpeg() ?: throw Exception("FFmpeg not available")
            val (ffmpegPath, ldLibPath) = ffmpegInfo
            val input = File(inputPath)
            val output = File(input.parentFile, "norm_${input.name}")

            val cmd = listOf(
                ffmpegPath.absolutePath, "-y",
                "-i", inputPath,
                "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
                "-c:a", "libmp3lame", "-q:a", "2",
                output.absolutePath,
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(input.parentFile)
            if (ldLibPath.isNotEmpty()) pb.environment()["LD_LIBRARY_PATH"] = ldLibPath
            val process = pb.start()
            val exitCode = try {
                process.inputStream.drainBounded()
                val completed = process.waitFor(FFMPEG_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    throw Exception("Normalization timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
                }
                process.exitValue()
            } finally {
                try { process.destroy() } catch (_: Exception) {}
            }

            if (exitCode == 0 && output.exists() && output.length() > 1024) {
                output.copyTo(input, overwrite = true)
                output.delete()
            } else {
                output.delete()
                throw Exception("Normalization failed (exit $exitCode)")
            }
            inputPath
        }.onFailure { it.rethrowIfCancelled() }
    }

    /** Clean up trimmed files cache */
    fun clearTrimCache() {
        File(context.cacheDir, "trimmed").deleteRecursively()
    }
}
