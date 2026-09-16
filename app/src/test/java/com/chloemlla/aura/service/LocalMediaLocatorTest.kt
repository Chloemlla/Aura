package com.chloemlla.aura.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chloemlla.aura.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaLocatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val locator = "rawresource:///${R.raw.aura_notification_gentle_ding}"

    @Test
    fun `raw resource locator stages verified audio without network`() {
        assertTrue(isLocalMediaLocator(locator))
        assertFalse(isLocalMediaLocator("https://example.com/tone.ogg"))

        val staged = stageLocalMediaLocator(
            context = context,
            locator = locator,
            tempDirectoryName = "local_locator_test",
            prefix = "tone_",
            maxBytes = 1_000_000,
        )
        try {
            assertTrue(staged.length() > 0)
            assertEquals("audio/ogg", requireSniffedMediaFile(staged, MediaFamily.AUDIO, "Sound").mimeType)
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `raw resource staging enforces the media size ceiling`() {
        assertThrows(MediaIngestionLimitExceeded::class.java) {
            stageLocalMediaLocator(
                context = context,
                locator = locator,
                tempDirectoryName = "local_locator_test",
                prefix = "tone_",
                maxBytes = 8,
            )
        }
    }
}
