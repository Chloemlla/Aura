package com.freevibe.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.freevibe.data.model.ContentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledContentProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = BundledContentProvider()

    @Test
    fun `offline catalog has complete unique ringtone notification and alarm choices`() {
        val ringtones = provider.getRingtones()
        val notifications = provider.getNotifications()
        val alarms = provider.getAlarms()
        val all = ringtones + notifications + alarms

        assertEquals(10, ringtones.size)
        assertEquals(10, notifications.size)
        assertEquals(5, alarms.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.source == ContentSource.BUNDLED })
        assertTrue(all.all { it.fileType == "audio/ogg" && it.license == "CC0 1.0" })
        assertTrue(all.all { it.previewUrl == it.downloadUrl })
    }

    @Test
    fun `every offline catalog locator points to a packaged ogg resource`() {
        val all = provider.getRingtones() + provider.getNotifications() + provider.getAlarms()

        all.forEach { sound ->
            val uri = Uri.parse(sound.previewUrl)
            assertEquals("rawresource", uri.scheme)
            val resourceId = uri.schemeSpecificPart.trim('/').toInt()
            val header = context.resources.openRawResource(resourceId).use { input ->
                ByteArray(4).also { bytes -> assertEquals(4, input.read(bytes)) }
            }
            assertEquals("OggS", header.toString(Charsets.US_ASCII))
        }
    }
}
