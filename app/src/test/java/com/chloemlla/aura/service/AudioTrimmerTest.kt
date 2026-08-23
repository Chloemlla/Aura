package com.chloemlla.aura.service

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTrimmerTest {

    @Test
    fun `platform plans route AAC WAV Vorbis and modern Opus without FFmpeg`() {
        val availableEncoders = setOf(
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_OPUS,
        )

        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_AAC, PlatformAudioContainer.MP4, 192),
            platformAudioExportPlan(AudioExportFormat.M4A, availableEncoders, sdkInt = 26),
        )
        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_RAW, PlatformAudioContainer.WAV, null),
            platformAudioExportPlan(AudioExportFormat.WAV, emptySet(), sdkInt = 26),
        )
        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_VORBIS, PlatformAudioContainer.OGG, 192),
            platformAudioExportPlan(AudioExportFormat.OGG, availableEncoders, sdkInt = 26),
        )
        assertNull(platformAudioExportPlan(AudioExportFormat.OPUS, availableEncoders, sdkInt = 28))
        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_OPUS, PlatformAudioContainer.OGG, 96),
            platformAudioExportPlan(AudioExportFormat.OPUS, availableEncoders, sdkInt = 29),
        )
        assertNull(platformAudioExportPlan(AudioExportFormat.MP3, availableEncoders, sdkInt = 35))
        assertNull(platformAudioExportPlan(AudioExportFormat.FLAC, availableEncoders, sdkInt = 35))
    }

    @Test
    fun `lossless platform plans preserve only compatible containers`() {
        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_AAC, PlatformAudioContainer.MP4, null),
            platformAudioExportPlan(
                exportFormat = AudioExportFormat.M4A,
                availableEncoderMimeTypes = emptySet(),
                sdkInt = 26,
                losslessInputMimeType = MimeTypes.AUDIO_AAC,
            ),
        )
        assertEquals(
            PlatformAudioExportPlan(MimeTypes.AUDIO_OPUS, PlatformAudioContainer.OGG, null),
            platformAudioExportPlan(
                exportFormat = AudioExportFormat.OGG,
                availableEncoderMimeTypes = emptySet(),
                sdkInt = 26,
                losslessInputMimeType = MimeTypes.AUDIO_OPUS,
            ),
        )
        assertNull(
            platformAudioExportPlan(
                exportFormat = AudioExportFormat.MP3,
                availableEncoderMimeTypes = emptySet(),
                sdkInt = 35,
                losslessInputMimeType = MimeTypes.AUDIO_MPEG,
            ),
        )
    }

    @Test
    fun `PCM fade fixture corpus is byte comparable across all curves`() {
        val fixture = shortArrayOf(10_000, 10_000, 10_000, 10_000)

        assertArrayEquals(
            shortArrayOf(0, 5_000, 10_000, 5_000),
            processPcmFixture(fixture, AudioFadeCurve.LINEAR),
        )
        assertArrayEquals(
            shortArrayOf(0, 7_071, 10_000, 7_071),
            processPcmFixture(fixture, AudioFadeCurve.SMOOTH),
        )
        assertArrayEquals(
            shortArrayOf(0, 1_250, 10_000, 1_250),
            processPcmFixture(fixture, AudioFadeCurve.EXPONENTIAL),
        )
    }

    @Test
    fun `fade curves expose stable endpoint gains`() {
        AudioFadeCurve.entries.forEach { curve ->
            assertEquals(0f, curve.gain(0f), 0.0001f)
            assertEquals(1f, curve.gain(1f), 0.0001f)
        }
        assertEquals(0.5f, AudioFadeCurve.LINEAR.gain(0.5f), 0.0001f)
        assertEquals(0.125f, AudioFadeCurve.EXPONENTIAL.gain(0.5f), 0.0001f)
    }

    @Test
    fun `speed adjusts output duration while preserving exact one-times duration`() {
        assertEquals(4_000L, speedAdjustedDurationMs(4_000L, 1f))
        assertEquals(2_000L, speedAdjustedDurationMs(4_000L, 2f))
        assertEquals(8_000L, speedAdjustedDurationMs(4_000L, 0.5f))
    }

    @Test
    fun `FFmpeg fallback command only encodes processed audio`() {
        val command = buildFfmpegEncodeCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/processed.wav",
            outputPath = "/output.mp3",
            exportFormat = AudioExportFormat.MP3,
            bitrateKbps = 256,
        )

        assertTrue(command.windowed(2).contains(listOf("-i", "/processed.wav")))
        assertTrue(command.windowed(2).contains(listOf("-c:a", "libmp3lame")))
        assertTrue(command.windowed(2).contains(listOf("-b:a", "256k")))
        assertFalse(command.contains("-ss"))
        assertFalse(command.contains("-t"))
        assertFalse(command.contains("-af"))
        assertFalse(command.any { it.contains("atrim") || it.contains("atempo") || it.contains("afade") })
    }

    @Test
    fun `FFmpeg fallback preserves FLAC and Ogg encoding contracts`() {
        val flac = buildFfmpegEncodeCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/processed.wav",
            outputPath = "/output.flac",
            exportFormat = AudioExportFormat.FLAC,
            bitrateKbps = null,
        )
        val ogg = buildFfmpegEncodeCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/processed.wav",
            outputPath = "/output.ogg",
            exportFormat = AudioExportFormat.OGG,
            bitrateKbps = 192,
        )

        assertTrue(flac.windowed(2).contains(listOf("-c:a", "flac")))
        assertFalse(flac.contains("-b:a"))
        assertTrue(ogg.windowed(2).contains(listOf("-metadata", "ANDROID_LOOP=true")))
    }

    @Test
    fun `stream copy fallback never adds an audio filter or encoder`() {
        val command = buildFfmpegStreamCopyTrimCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/input.ogg",
            outputPath = "/output.ogg",
            startMs = 123L,
            endMs = 1_987L,
            outputFormat = AudioExportFormat.OGG,
        )

        assertTrue(command.windowed(2).contains(listOf("-ss", "0.123")))
        assertTrue(command.windowed(2).contains(listOf("-t", "1.864")))
        assertTrue(command.windowed(2).contains(listOf("-c:a", "copy")))
        assertFalse(command.contains("-af"))
        assertFalse(command.contains("-b:a"))
        assertTrue(command.windowed(2).contains(listOf("-metadata", "ANDROID_LOOP=true")))
    }

    @Test
    fun `lossless cut accepts only a contiguous packet copy without effects`() {
        val source = listOf(
            byteArrayOf(1, 2),
            byteArrayOf(3, 4),
            byteArrayOf(5, 6),
        )

        assertTrue(isLosslessCutAllowed(0L, 0L, playbackSpeed = 1f))
        assertFalse(isLosslessCutAllowed(1L, 0L, playbackSpeed = 1f))
        assertFalse(isLosslessCutAllowed(0L, 0L, playbackSpeed = 1.25f))
        assertTrue(
            areEncodedAudioPacketsContiguousCopy(
                source,
                listOf(byteArrayOf(3, 4), byteArrayOf(5, 6)),
            ),
        )
        assertFalse(
            areEncodedAudioPacketsContiguousCopy(
                source,
                listOf(byteArrayOf(3, 9), byteArrayOf(5, 6)),
            ),
        )
    }

    @Test
    fun `trim duration tolerance is exactly one audio frame`() {
        assertTrue(isTrimDurationWithinOneAudioFrame(5_000L, 5_023L, 23L))
        assertFalse(isTrimDurationWithinOneAudioFrame(5_000L, 5_024L, 23L))
    }

    private fun processPcmFixture(
        samples: ShortArray,
        curve: AudioFadeCurve,
    ): ShortArray {
        val processor = PcmFadeAudioProcessor(
            durationMs = 4L,
            fadeInMs = 2L,
            fadeOutMs = 2L,
            fadeCurve = curve,
        )
        processor.configure(
            AudioProcessor.AudioFormat(
                1_000,
                1,
                C.ENCODING_PCM_16BIT,
            ),
        )
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { sample -> input.putShort(sample) }
        input.flip()
        processor.queueInput(input)
        processor.queueEndOfStream()

        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.getShort() }
            .also { processor.reset() }
    }
}
