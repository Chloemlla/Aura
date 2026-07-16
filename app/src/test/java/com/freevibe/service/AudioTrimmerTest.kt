package com.freevibe.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrimmerTest {

    @Test
    fun `trim command decodes before applying exact timestamp bounds`() {
        val command = buildFfmpegTrimCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/input.mp3",
            outputPath = "/output.m4a",
            startMs = 123L,
            endMs = 1_987L,
            fadeInMs = 250L,
            fadeOutMs = 300L,
            fadeCurve = AudioFadeCurve.SMOOTH,
            exportFormat = AudioExportFormat.M4A,
            bitrateKbps = 256,
        )
        val filter = command[command.indexOf("-af") + 1]

        assertFalse(command.contains("-ss"))
        assertTrue(filter.contains("atrim=start=0.123:end=1.987"))
        assertTrue(filter.contains("asetpts=PTS-STARTPTS"))
        assertTrue(filter.contains("afade=t=in:st=0:d=0.250:curve=qsin"))
        assertTrue(filter.contains("afade=t=out:st=1.564:d=0.300:curve=qsin"))
        assertTrue(command.windowed(2).contains(listOf("-c:a", "aac")))
        assertTrue(command.windowed(2).contains(listOf("-b:a", "256k")))
    }

    @Test
    fun `all three fade curves map to supported ffmpeg filters`() {
        assertTrue(AudioFadeCurve.entries.size >= 3)
        assertTrue(AudioFadeCurve.entries.map { it.ffmpegValue }.containsAll(listOf("tri", "qsin", "exp")))
    }

    @Test
    fun `lossless export does not add a bitrate argument`() {
        val command = buildFfmpegTrimCommand(
            ffmpegPath = "/ffmpeg",
            inputPath = "/input.wav",
            outputPath = "/output.flac",
            startMs = 0L,
            endMs = 2_000L,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            fadeCurve = AudioFadeCurve.LINEAR,
            exportFormat = AudioExportFormat.FLAC,
            bitrateKbps = null,
        )

        assertFalse(command.contains("-b:a"))
        assertTrue(command.windowed(2).contains(listOf("-c:a", "flac")))
    }

    @Test
    fun `trim duration tolerance is exactly one audio frame`() {
        assertTrue(isTrimDurationWithinOneAudioFrame(5_000L, 5_023L, 23L))
        assertFalse(isTrimDurationWithinOneAudioFrame(5_000L, 5_024L, 23L))
    }
}
