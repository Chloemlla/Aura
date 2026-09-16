package com.chloemlla.aura.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.chloemlla.aura.data.model.FitCanvasStyle
import com.chloemlla.aura.data.model.FitCanvasSize
import com.chloemlla.aura.data.model.ResolvedFitCanvas
import com.chloemlla.aura.data.model.boundedFitCanvasSize
import com.chloemlla.aura.util.rethrowIfCancelled
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

data class PreparedVideoFitCanvas(
    val file: File,
    val backgroundFile: File,
    val resolvedCanvas: ResolvedFitCanvas,
    val bakedIntoVideo: Boolean,
)

internal fun boundedVideoFrameSize(width: Int, height: Int, maxLongEdge: Int = 512): Pair<Int, Int> {
    require(maxLongEdge > 0)
    if (width <= 0 || height <= 0) return maxLongEdge to maxLongEdge
    val longest = max(width, height)
    if (longest <= maxLongEdge) return width to height
    val scale = maxLongEdge.toFloat() / longest
    return (width * scale).toInt().coerceAtLeast(1) to
        (height * scale).toInt().coerceAtLeast(1)
}

internal fun boundedBitmapSampleSize(width: Int, height: Int, maxLongEdge: Int = 512): Int {
    require(maxLongEdge > 0)
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (max(width, height) / sampleSize > maxLongEdge && sampleSize <= Int.MAX_VALUE / 2) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun boundedVideoCanvasSize(
    width: Int,
    height: Int,
    maxPixels: Long,
): FitCanvasSize {
    require(maxPixels >= 4L)
    val bounded = boundedFitCanvasSize(
        width.coerceAtLeast(2),
        height.coerceAtLeast(2),
        maxPixels,
    )
    var evenWidth = bounded.width.coerceAtLeast(2).let { it - (it % 2) }
    var evenHeight = bounded.height.coerceAtLeast(2).let { it - (it % 2) }
    if (evenWidth.toLong() * evenHeight > maxPixels) {
        if (evenWidth >= evenHeight) {
            evenWidth = (maxPixels / evenHeight).toInt().coerceAtLeast(2).let { it - (it % 2) }
        } else {
            evenHeight = (maxPixels / evenWidth).toInt().coerceAtLeast(2).let { it - (it % 2) }
        }
    }
    return FitCanvasSize(evenWidth, evenHeight)
}

internal fun decodeBoundedBitmapFile(path: String, maxLongEdge: Int = 512): Bitmap? {
    require(maxLongEdge > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = boundedBitmapSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        },
    )
}

internal fun decodeBoundedVideoFrame(path: String, maxLongEdge: Int = 512): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val (targetWidth, targetHeight) = boundedVideoFrameSize(sourceWidth, sourceHeight, maxLongEdge)
        listOf(0L, 100_000L, 500_000L, 1_000_000L).firstNotNullOfOrNull { timeUs ->
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight,
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { full ->
                            if (full.width <= targetWidth && full.height <= targetHeight) {
                                full
                            } else {
                                Bitmap.createScaledBitmap(full, targetWidth, targetHeight, true).also {
                                    if (it !== full) full.recycle()
                                }
                            }
                        }
                }
            }.getOrNull()
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun videoFitCanvasFfmpegArgs(
    ffmpegPath: String,
    backgroundPath: String,
    inputPath: String,
    outputPath: String,
    width: Int,
    height: Int,
): List<String> {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    val foreground = "[1:v]scale=$width:$height:" +
        "force_original_aspect_ratio=decrease:force_divisible_by=2[fg]"
    val composition = "[0:v][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,format=yuv420p[v]"
    return listOf(
        ffmpegPath,
        "-y",
        "-loop", "1",
        // The cached background is static, but it is clocked at Aura's default
        // live-wallpaper ceiling so overlay frames are not collapsed to 1 FPS.
        "-framerate", "30",
        "-i", backgroundPath,
        "-i", inputPath,
        "-filter_complex", "$foreground;$composition",
        "-map", "[v]",
        "-map_metadata", "1",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", "20",
        "-pix_fmt", "yuv420p",
        "-an",
        "-shortest",
        "-movflags", "+faststart",
        outputPath,
    )
}

internal fun videoCompatibilityFfmpegArgs(
    ffmpegPath: String,
    inputPath: String,
    outputPath: String,
    width: Int,
    height: Int,
): List<String> {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    return listOf(
        ffmpegPath,
        "-y",
        "-i", inputPath,
        "-map", "0:v:0",
        "-map_metadata", "0",
        "-vf", "scale=$width:$height:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", "20",
        "-pix_fmt", "yuv420p",
        "-an",
        "-movflags", "+faststart",
        "-f", "mp4",
        outputPath,
    )
}

@Singleton
class VideoFitCanvasComposer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun prepare(file: File, style: FitCanvasStyle): Result<PreparedVideoFitCanvas> =
        prepareAtSize(
            file = file,
            style = style,
            targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(2),
            targetHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(2),
        )

    internal suspend fun prepareAtSize(
        file: File,
        style: FitCanvasStyle,
        targetWidth: Int,
        targetHeight: Int,
    ): Result<PreparedVideoFitCanvas> =
        withContext(Dispatchers.IO) {
            runCatching { prepareBlocking(file, style, targetWidth, targetHeight) }
                .onFailure { it.rethrowIfCancelled() }
        }

    suspend fun writeCompatibleCopy(
        source: File,
        output: File,
        targetWidth: Int = context.resources.displayMetrics.widthPixels.coerceAtLeast(2),
        targetHeight: Int = context.resources.displayMetrics.heightPixels.coerceAtLeast(2),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = readMediaTechnicalMetadata(source)
            if (metadata.width <= 0 || metadata.height <= 0) {
                throw IOException("Video dimensions could not be read")
            }
            val maxLongEdge = (max(targetWidth, targetHeight).coerceAtLeast(2) * 2)
                .coerceAtMost(4_096)
            val (longEdgeWidth, longEdgeHeight) = boundedVideoFrameSize(
                metadata.width,
                metadata.height,
                maxLongEdge,
            )
            val bounded = boundedVideoCanvasSize(
                longEdgeWidth,
                longEdgeHeight,
                FitCanvasRenderer.MAX_OUTPUT_PIXELS,
            )
            val (ffmpeg, libraryPath) = initializeFfmpegRuntime()
            val command = videoCompatibilityFfmpegArgs(
                ffmpegPath = ffmpeg.absolutePath,
                inputPath = source.absolutePath,
                outputPath = output.absolutePath,
                width = bounded.width,
                height = bounded.height,
            )
            executeFfmpeg(command, libraryPath, "Video optimization", output)
            if (output.length() > MAX_VIDEO_WALLPAPER_BYTES) {
                throw IOException("Optimized video exceeds the video wallpaper limit")
            }
            val result = readMediaTechnicalMetadata(output, "video/mp4")
            if (result.width <= 0 || result.height <= 0) {
                throw IOException("Optimized video could not be validated")
            }
        }.onFailure { it.rethrowIfCancelled() }
    }

    private fun prepareBlocking(
        file: File,
        style: FitCanvasStyle,
        targetWidth: Int,
        targetHeight: Int,
    ): PreparedVideoFitCanvas {
        if (!file.exists() || !file.canRead()) throw IOException("Video wallpaper file is unavailable")
        val isGif = file.extension.equals("gif", ignoreCase = true)
        val target = boundedVideoCanvasSize(
            targetWidth.coerceAtLeast(2),
            targetHeight.coerceAtLeast(2),
            if (isGif) GIF_BACKGROUND_MAX_PIXELS else FitCanvasRenderer.MAX_OUTPUT_PIXELS,
        )
        val width = target.width
        val height = target.height
        val poster = extractRepresentativeFrame(file)
        val isHdr = isHdrVideo(file)
        val backgroundResult = try {
            FitCanvasRenderer.renderBackground(
                source = poster,
                targetWidth = width,
                targetHeight = height,
                style = style,
                isHdr = isHdr,
            )
        } finally {
            poster?.takeUnless { it.isRecycled }?.recycle()
        }

        val backgroundFile = File(context.filesDir, VIDEO_WALLPAPER_CANVAS_BACKGROUND_FILE)
        val backgroundTemp = File.createTempFile("aura-fit-canvas-", ".png", context.cacheDir)
        try {
            backgroundTemp.outputStream().use { output ->
                if (!backgroundResult.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Could not write Fit Canvas background")
                }
            }
        } catch (error: Throwable) {
            backgroundTemp.delete()
            throw error
        } finally {
            backgroundResult.bitmap.recycle()
        }

        try {
            if (isGif) {
                moveIntoPlace(backgroundTemp, backgroundFile)
                return PreparedVideoFitCanvas(
                    file = file,
                    backgroundFile = backgroundFile,
                    resolvedCanvas = backgroundResult.resolvedCanvas,
                    bakedIntoVideo = false,
                )
            }

            val (ffmpeg, libraryPath) = initializeFfmpegRuntime()
            val output = File(context.filesDir, "live_wallpaper.fit.mp4")
            val tempOutput = File.createTempFile("aura-fit-canvas-", ".mp4", context.cacheDir)
            try {
                val command = videoFitCanvasFfmpegArgs(
                    ffmpegPath = ffmpeg.absolutePath,
                    backgroundPath = backgroundTemp.absolutePath,
                    inputPath = file.absolutePath,
                    outputPath = tempOutput.absolutePath,
                    width = width,
                    height = height,
                )
                val builder = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .directory(context.cacheDir)
                if (libraryPath.isNotBlank()) builder.environment()["LD_LIBRARY_PATH"] = libraryPath
                val process = builder.start()
                val tail = StringBuilder()
                val drain = Thread({
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (tail.length > 4096) tail.delete(0, tail.length - 2048)
                            tail.appendLine(line)
                        }
                    }
                }, "aura-fit-canvas-ffmpeg").apply { start() }
                val completed = process.waitFor(VIDEO_FIT_CANVAS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) process.destroyForcibly()
                drain.join(2_000L)
                val exitCode = if (completed) process.exitValue() else -1
                if (exitCode != 0 || !tempOutput.exists() || tempOutput.length() < 1024L) {
                    throw IOException("Fit Canvas video render failed: ${tail.takeLast(400)}")
                }
                if (tempOutput.length() > MAX_VIDEO_WALLPAPER_BYTES) {
                    throw IOException("Fit Canvas video exceeds the video wallpaper limit")
                }
                requireValidComposedVideo(tempOutput, width, height)
                // Keep the active wallpaper intact until both staged assets have
                // been rendered and validated successfully.
                moveIntoPlace(backgroundTemp, backgroundFile)
                moveIntoPlace(tempOutput, output)
                return PreparedVideoFitCanvas(
                    file = output,
                    backgroundFile = backgroundFile,
                    resolvedCanvas = backgroundResult.resolvedCanvas,
                    bakedIntoVideo = true,
                )
            } finally {
                tempOutput.delete()
            }
        } finally {
            backgroundTemp.delete()
        }
    }

    private fun executeFfmpeg(
        command: List<String>,
        libraryPath: String,
        operation: String,
        output: File,
    ) {
        val builder = ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(context.cacheDir)
        if (libraryPath.isNotBlank()) builder.environment()["LD_LIBRARY_PATH"] = libraryPath
        val process = builder.start()
        val tail = StringBuilder()
        val drain = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (tail.length > 4096) tail.delete(0, tail.length - 2048)
                    tail.appendLine(line)
                }
            }
        }, "aura-video-ffmpeg").apply { start() }
        val completed = process.waitFor(VIDEO_FIT_CANVAS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        drain.join(2_000L)
        val exitCode = if (completed) process.exitValue() else -1
        if (exitCode != 0 || !output.exists() || output.length() < 1024L) {
            throw IOException("$operation failed: ${tail.takeLast(400)}")
        }
    }

    private fun requireValidComposedVideo(file: File, expectedWidth: Int, expectedHeight: Int) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val probe = VideoWallpaperProbe(
                hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    ?.equals("yes", ignoreCase = true) == true,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
            videoWallpaperProbeFailure(probe)?.let { throw IOException(it) }
            if (probe.width != expectedWidth || probe.height != expectedHeight) {
                throw IOException(
                    "Fit Canvas video dimensions ${probe.width}x${probe.height} do not match " +
                        "${expectedWidth}x$expectedHeight",
                )
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Fit Canvas video could not be validated", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractRepresentativeFrame(file: File): Bitmap? {
        if (file.extension.equals("gif", ignoreCase = true)) {
            return decodeBoundedBitmapFile(file.absolutePath)
        }
        return decodeBoundedVideoFrame(file.absolutePath)
    }

    private fun isHdrVideo(file: File): Boolean {
        if (file.extension.equals("gif", ignoreCase = true)) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val transfer = if (android.os.Build.VERSION.SDK_INT >= 30) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            } else {
                null
            }
            transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun initializeFfmpegRuntime(): Pair<File, String> {
        try {
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
        } catch (error: Throwable) {
            error.rethrowIfCancelled()
            throw IOException("Fit Canvas video renderer could not start", error)
        }
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val libraryDirectory = File(
            context.noBackupFilesDir,
            "youtubedl-android/packages/ffmpeg/usr/lib",
        )
        if (!ffmpeg.exists() || !ffmpeg.canExecute() || !libraryDirectory.isDirectory) {
            throw IOException("Fit Canvas video renderer is unavailable")
        }
        val supportDirectory = prepareFfmpegSupportLibrary()
        val libraryPath = listOf(
            context.applicationInfo.nativeLibraryDir,
            supportDirectory.absolutePath,
            libraryDirectory.absolutePath,
        ).joinToString(File.pathSeparator)
        return ffmpeg to libraryPath
    }

    private fun prepareFfmpegSupportLibrary(): File {
        val runtimeDirectory = File(context.noBackupFilesDir, "aura-ffmpeg-runtime")
        if (!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) {
            throw IOException("Fit Canvas support directory could not be created")
        }
        val archive = File(context.applicationInfo.nativeLibraryDir, "libpython.zip.so")
        if (!archive.exists() || !archive.canRead()) {
            throw IOException("Fit Canvas support library is unavailable")
        }
        ZipFile(archive).use { zip ->
            FFMPEG_SUPPORT_LIBRARIES.forEach { support ->
                val destination = File(runtimeDirectory, support.outputName)
                val entry = zip.getEntry(support.archiveEntry)
                    ?: throw IOException("Fit Canvas support library is missing from the app")
                if (entry.size !in 1..MAX_FFMPEG_SUPPORT_LIBRARY_BYTES) {
                    throw IOException("Fit Canvas support library has an invalid size")
                }
                if (destination.length() == entry.size) return@forEach
                val temp = File.createTempFile("aura-ffmpeg-", ".tmp", runtimeDirectory)
                try {
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output ->
                            copyStreamCapped(input, output, MAX_FFMPEG_SUPPORT_LIBRARY_BYTES)
                        }
                    }
                    if (temp.length() != entry.size) {
                        throw IOException("Fit Canvas support library could not be verified")
                    }
                    moveIntoPlace(temp, destination)
                } finally {
                    temp.delete()
                }
            }
        }
        return runtimeDirectory
    }

    private companion object {
        private const val VIDEO_FIT_CANVAS_TIMEOUT_SECONDS = 180L
        private const val GIF_BACKGROUND_MAX_PIXELS = 250_000L
        private const val MAX_FFMPEG_SUPPORT_LIBRARY_BYTES = 8L * 1024L * 1024L
        private val FFMPEG_SUPPORT_LIBRARIES = listOf(
            FfmpegSupportLibrary("libandroid-posix-semaphore.so", "usr/lib/libandroid-posix-semaphore.so"),
            FfmpegSupportLibrary("libandroid-support.so", "usr/lib/libandroid-support.so"),
            FfmpegSupportLibrary("libc++_shared.so", "usr/lib/libc++_shared.so"),
            FfmpegSupportLibrary("libcrypto.so.3", "usr/lib/libcrypto.so.3"),
            FfmpegSupportLibrary("libexpat.so.1", "usr/lib/libexpat.so.1.11.1"),
        )
    }
}

private data class FfmpegSupportLibrary(
    val outputName: String,
    val archiveEntry: String,
)
