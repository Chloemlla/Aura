package com.freevibe.service

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalMediaDispatcherTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val imageUri = Uri.parse("content://picker.example/images/1")
    private val audioUri = Uri.parse("content://picker.example/audio/1")

    @Test
    fun `send image accepts clipdata with a read grant`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("image", imageUri)
        }

        val request = parseExternalMediaIntent(intent)?.getOrThrow()

        assertEquals(imageUri, request?.uri)
        assertEquals(ExternalMediaKind.IMAGE, request?.expectedKind)
    }

    @Test
    fun `edit audio accepts data uri with a read grant`() {
        val intent = Intent(Intent.ACTION_EDIT)
            .setDataAndType(audioUri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertEquals(audioUri, intent.data)
        assertEquals("audio/mpeg", intent.type)

        val request = parseExternalMediaIntent(intent)?.getOrThrow()

        assertEquals(audioUri, request?.uri)
        assertEquals(ExternalMediaKind.AUDIO, request?.expectedKind)
    }

    @Test
    fun `generic mime type falls back to sniffed family`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, imageUri)

        val request = parseExternalMediaIntent(intent)?.getOrThrow()

        assertEquals(imageUri, request?.uri)
        assertEquals(null, request?.expectedKind)
    }

    @Test
    fun `remote uri is rejected before any stream is opened`() {
        val intent = Intent(Intent.ACTION_SEND, Uri.parse("https://example.com/image.jpg"))
            .setType("image/jpeg")

        val result = parseExternalMediaIntent(intent)

        assertTrue(result?.isFailure == true)
    }

    @Test
    fun `content uri without a read grant is rejected`() {
        val intent = Intent(Intent.ACTION_SEND, imageUri).setType("image/jpeg")

        val result = parseExternalMediaIntent(intent)

        assertTrue(result?.isFailure == true)
    }

    @Test
    fun `json shares remain available to collection import`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://picker.example/aura.json"))

        assertEquals(null, parseExternalMediaIntent(intent))
    }

    @Test
    fun `ingestion copies and publishes a sniffed image through the app outbox`() {
        val sourceBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
        val published = copySniffedExternalMedia(
            input = ByteArrayInputStream(sourceBytes),
            outputDirectory = temp.newFolder("external_media"),
            sourceName = "camera.jpg",
            expectedKind = ExternalMediaKind.IMAGE,
            token = "test_image",
        )

        assertEquals(ExternalMediaKind.IMAGE, published.kind)
        assertArrayEquals(sourceBytes, published.file.readBytes())
        assertTrue(published.file.name.endsWith(".jpg"))
    }

    @Test
    fun `malformed payload is rejected and partial output is removed`() {
        val outputDirectory = temp.newFolder("malformed_media")

        assertThrows(MediaIngestionMediaRejected::class.java) {
            copySniffedExternalMedia(
                input = ByteArrayInputStream("not an image".toByteArray()),
                outputDirectory = outputDirectory,
                sourceName = "camera.jpg",
                expectedKind = ExternalMediaKind.IMAGE,
                token = "malformed",
            )
        }

        assertFalse(File(outputDirectory, ".external_malformed.part").exists())
        assertEquals(0, outputDirectory.listFiles()?.size ?: 0)
    }
}
