package com.freevibe.ui.screens.editor

import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundEditorViewModelTest {

    @Test
    fun `remote audio cache file name is scoped by identity`() {
        val first = buildRemoteAudioCacheFileName(
            name = "Focus Loop",
            cacheIdentity = "SOUND::YOUTUBE::yt_focus12345",
            url = "https://example.com/audio.mp3",
        )
        val second = buildRemoteAudioCacheFileName(
            name = "Focus Loop",
            cacheIdentity = "SOUND::YOUTUBE::yt_relax12345",
            url = "https://example.com/audio.mp3",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `remote audio cache file name preserves detected extension`() {
        val fileName = buildRemoteAudioCacheFileName(
            name = "Ocean Wave",
            cacheIdentity = "SOUND::BUNDLED::ocean_wave",
            url = "https://example.com/ocean.wav?download=1",
        )

        assertTrue(fileName.endsWith(".wav"))
    }

    @Test
    fun `shouldReuseLoadedSound ignores local editor state`() {
        val shouldReuse = shouldReuseLoadedSound(
            loadedSoundKey = "SOUND::YOUTUBE::yt_focus12345",
            requestedSoundKey = "SOUND::YOUTUBE::yt_focus12345",
            state = SoundEditorState(
                localFilePath = "C:/cache/local.mp3",
                isLocalFile = true,
            ),
        )

        assertFalse(shouldReuse)
    }

    @Test
    fun `shouldReuseLoadedLocalUri reuses active local editor state`() {
        val key = buildLocalAudioEditorIdentity("content://audio/1")
        val shouldReuse = shouldReuseLoadedLocalUri(
            loadedSoundKey = key,
            requestedLocalKey = key,
            state = SoundEditorState(
                localFilePath = "C:/cache/audio.mp3",
                isLocalFile = true,
            ),
        )

        assertTrue(shouldReuse)
    }

    @Test
    fun `shouldReuseLoadedLocalUri ignores remote editor state`() {
        val key = buildLocalAudioEditorIdentity("content://audio/1")
        val shouldReuse = shouldReuseLoadedLocalUri(
            loadedSoundKey = key,
            requestedLocalKey = key,
            state = SoundEditorState(
                localFilePath = "C:/cache/audio.mp3",
                isLocalFile = false,
            ),
        )

        assertFalse(shouldReuse)
    }

    @Test
    fun `default ringtone trim keeps short clips selected`() {
        assertEquals(1f, defaultRingtoneTrimEndFraction(20_000L), 0.0001f)
    }

    @Test
    fun `default ringtone trim caps long clips at thirty seconds`() {
        assertEquals(0.5f, defaultRingtoneTrimEndFraction(60_000L), 0.0001f)
    }

    @Test
    fun `default ringtone trim keeps unknown durations selected`() {
        assertEquals(1f, defaultRingtoneTrimEndFraction(0L), 0.0001f)
    }

    @Test
    fun `default ringtone trim milliseconds preserve exact short duration`() {
        assertEquals(20_123L, defaultRingtoneTrimEndMs(20_123L))
        assertEquals(30_000L, defaultRingtoneTrimEndMs(60_000L))
    }

    @Test
    fun `numeric trim bounds clamp to one encoded frame`() {
        assertEquals(
            9_977L,
            clampTrimStartMs(
                requestedMs = 10_000L,
                trimEndMs = 10_000L,
                durationMs = 20_000L,
                frameDurationMs = 23L,
            ),
        )
        assertEquals(
            1_023L,
            clampTrimEndMs(
                requestedMs = 1_001L,
                trimStartMs = 1_000L,
                durationMs = 20_000L,
                frameDurationMs = 23L,
            ),
        )
    }

    @Test
    fun `sound editor state preserves exact millisecond bounds`() {
        val state = SoundEditorState(
            durationMs = 123_456L,
            trimStartMs = 12_345L,
            trimEndMs = 98_765L,
        )

        assertEquals(12_345L, state.trimStartMs)
        assertEquals(98_765L, state.trimEndMs)
        assertEquals(86_420L, state.trimDurationMs)
    }

    @Test
    fun `waveform zoom keeps focus anchored and clamps viewport`() {
        val zoomed = updateWaveformViewport(
            zoom = 1f,
            startFraction = 0f,
            zoomChange = 2f,
            panFraction = 0f,
            focusFraction = 0.75f,
        )
        assertEquals(2f, zoomed.zoom, 0.0001f)
        assertEquals(0.375f, zoomed.startFraction, 0.0001f)

        val clamped = updateWaveformViewport(
            zoom = zoomed.zoom,
            startFraction = zoomed.startFraction,
            zoomChange = 100f,
            panFraction = -10f,
            focusFraction = 0.5f,
        )
        assertEquals(MAX_WAVEFORM_ZOOM, clamped.zoom, 0.0001f)
        assertTrue(clamped.startFraction in 0f..(1f - 1f / MAX_WAVEFORM_ZOOM))
    }

    @Test
    fun `local audio editor identity is scoped to uri`() {
        val first = buildLocalAudioEditorIdentity("content://audio/1")
        val second = buildLocalAudioEditorIdentity("content://audio/2")

        assertNotEquals(first, second)
    }

    @Test
    fun `editor blocks sounds whose edit action is disabled`() {
        val message = soundEditorEditGateMessage(
            sound = sound(source = ContentSource.YOUTUBE, license = "YouTube"),
            editConfirmed = true,
        )

        assertNotNull(message)
        assertTrue(message!!.contains("cannot be trimmed", ignoreCase = true))
    }

    @Test
    fun `editor requires confirmation for non-commercial licensed sounds`() {
        val sound = sound(
            source = ContentSource.FREESOUND,
            license = "CC BY-NC",
            sourcePageUrl = "https://freesound.org/s/123",
            uploaderName = "creator",
        )

        assertNotNull(soundEditorEditGateMessage(sound, editConfirmed = false))
        assertNull(soundEditorEditGateMessage(sound, editConfirmed = true))
    }

    @Test
    fun `editor allows local user files without confirmation`() {
        val message = soundEditorEditGateMessage(
            sound = sound(source = ContentSource.LOCAL, license = ""),
            editConfirmed = false,
        )

        assertNull(message)
    }

    private fun sound(
        source: ContentSource,
        license: String,
        sourcePageUrl: String = "https://example.com/source",
        uploaderName: String = "uploader",
    ) = Sound(
        id = "sound_1",
        source = source,
        name = "Sound",
        previewUrl = "https://example.com/preview.mp3",
        downloadUrl = "https://example.com/download.mp3",
        license = license,
        sourcePageUrl = sourcePageUrl,
        uploaderName = uploaderName,
    )
}
