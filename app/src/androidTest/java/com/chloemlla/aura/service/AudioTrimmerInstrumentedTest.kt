package com.chloemlla.aura.service

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@LargeTest
class AudioTrimmerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureDirectory get() = File(context.cacheDir, "audio_platform_fixtures")

    // The fork resolves FFmpeg through FfmpegDownloader and routes subprocesses via
    // Clash instead of reflecting into youtubedl-android, so the trimmer takes both
    // collaborators. Every case here stays on the Media3 platform path (WAV muxing
    // and the AAC encoder), so neither one is exercised — they only have to exist.
    private val trimmer get() = AudioTrimmer(
        context = context,
        ffmpegDownloader = FfmpegDownloader(context, OkHttpClient()),
        clashProxyManager = ClashProxyManager(context),
    )

    @After
    fun cleanFixtures() {
        trimmer.clearTrimCache()
        fixtureDirectory.deleteRecursively()
    }

    @Test
    fun platformWavPipelineTrimsAndFadesByteComparableFixture() = runBlocking {
        val sampleRate = 8_000
        val input = File(fixtureDirectory, "constant.wav")
        writePcm16Wav(input, ShortArray(sampleRate) { 10_000 }, sampleRate)

        val output = trimmer.trim(
            inputPath = input.absolutePath,
            startMs = 200L,
            endMs = 800L,
            outputFileName = "platform_fade_fixture",
            fadeInMs = 200L,
            fadeOutMs = 200L,
            fadeCurve = AudioFadeCurve.LINEAR,
            exportFormat = AudioExportFormat.WAV,
        ).getOrThrow()

        val expected = ShortArray(4_800) { index ->
            val gain = when {
                index < 1_600 -> index / 1_600f
                index >= 3_200 -> (4_800 - index) / 1_600f
                else -> 1f
            }
            (10_000 * gain).roundToInt().toShort()
        }
        assertArrayEquals(expected, readPcm16Wav(File(output)))
    }

    @Test
    fun platformWavSpeedKeepsPitchAndHalvesDuration() = runBlocking {
        val sampleRate = 44_100
        val input = File(fixtureDirectory, "tone.wav")
        writePcm16Wav(input, sineFixture(sampleRate, durationMs = 1_000L), sampleRate)

        val output = trimmer.trim(
            inputPath = input.absolutePath,
            startMs = 0L,
            endMs = 1_000L,
            outputFileName = "platform_speed_fixture",
            playbackSpeed = 2f,
            exportFormat = AudioExportFormat.WAV,
        ).getOrThrow()
        val samples = readPcm16Wav(File(output))
        val durationMs = samples.size * 1_000L / sampleRate
        val zeroCrossings = (1 until samples.size).count { index ->
            val first = samples[index - 1]
            val second = samples[index]
            first < 0 && second >= 0 || first >= 0 && second < 0
        }
        val detectedFrequency = zeroCrossings * 500f / durationMs

        assertTrue(durationMs in 490L..510L)
        assertTrue(detectedFrequency in 420f..460f)
    }

    @Test
    fun platformAacPipelineConvertsWavToM4a() = runBlocking {
        val sampleRate = 44_100
        val input = File(fixtureDirectory, "convert.wav")
        writePcm16Wav(input, sineFixture(sampleRate, durationMs = 1_000L), sampleRate)

        val output = trimmer.trim(
            inputPath = input.absolutePath,
            startMs = 0L,
            endMs = 1_000L,
            outputFileName = "platform_aac_fixture",
            exportFormat = AudioExportFormat.M4A,
            bitrateKbps = 192,
        ).getOrThrow()
        val (mimeType, durationMs) = readEncodedAudio(File(output))

        assertEquals("audio/mp4a-latm", mimeType)
        assertTrue(durationMs in 975L..1_025L)
    }

    private fun sineFixture(sampleRate: Int, durationMs: Long): ShortArray {
        val sampleCount = (sampleRate * durationMs / 1_000L).toInt()
        return ShortArray(sampleCount) { index ->
            (sin(2.0 * PI * 440.0 * index / sampleRate) * 12_000.0).roundToInt().toShort()
        }
    }

    private fun writePcm16Wav(file: File, samples: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataSize = samples.size * Short.SIZE_BYTES
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + dataSize)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1.toShort())
        bytes.putShort(1.toShort())
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * Short.SIZE_BYTES)
        bytes.putShort(Short.SIZE_BYTES.toShort())
        bytes.putShort(16.toShort())
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(dataSize)
        samples.forEach { sample -> bytes.putShort(sample) }
        file.writeBytes(bytes.array())
    }

    private fun readPcm16Wav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkName = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            require(chunkSize >= 0 && offset + 8 + chunkSize <= bytes.size)
            if (chunkName == "data") {
                val data = ByteBuffer.wrap(bytes, offset + 8, chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(chunkSize / Short.SIZE_BYTES) { data.getShort() }
            }
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        error("WAV data chunk was not found")
    }

    private fun readEncodedAudio(file: File): Pair<String?, Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
            format.getString(MediaFormat.KEY_MIME) to durationMs
        } finally {
            extractor.release()
        }
    }
}
