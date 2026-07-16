package com.freevibe.service

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeYtDlpRequestFactoryTest {

    @Test
    fun `provider URL accepts credential-free HTTPS and normalizes trailing slash`() {
        assertEquals("", normalizeYouTubePoTokenProviderUrl("  "))
        assertEquals(
            "https://pot.example.org/api",
            normalizeYouTubePoTokenProviderUrl("https://pot.example.org/api/"),
        )
        assertNull(normalizeYouTubePoTokenProviderUrl("http://pot.example.org"))
        assertNull(normalizeYouTubePoTokenProviderUrl("https://user:secret@pot.example.org"))
        assertNull(normalizeYouTubePoTokenProviderUrl("https://pot.example.org?token=secret"))
        assertNull(normalizeYouTubePoTokenProviderUrl("https://pot.example.org/#fragment"))
    }

    @Test
    fun `provider options select mweb token fetching and the configured plugin`() {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=abcdefghijk")
        val pluginDirectory = File("build/test-pot-plugins").absoluteFile

        applyYouTubePoTokenOptions(
            request = request,
            pluginDirectory = pluginDirectory,
            providerBaseUrl = "https://pot.example.org",
        )

        val command = request.buildCommand()
        assertTrue(command.windowed(2).contains(listOf("--plugin-dirs", pluginDirectory.absolutePath)))
        assertTrue(
            command.windowed(2).contains(
                listOf("--extractor-args", "youtube:player_client=mweb,default;fetch_pot=always"),
            ),
        )
        assertTrue(
            command.windowed(2).contains(
                listOf("--extractor-args", "youtubepot-bgutilhttp:base_url=https://pot.example.org"),
            ),
        )
    }

    @Test
    fun `bundled provider plugin matches the reviewed release artifact`() {
        val plugin = File("src/main/res/raw/bgutil_ytdlp_pot_provider.zip")
        assertTrue(plugin.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(plugin.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(
            "b8ceec7f76143da172aaf5ebeec0c2d218e5680c063b931586bca48567069b38",
            digest,
        )
        ZipFile(plugin).use { zip ->
            assertTrue(zip.getEntry("yt_dlp_plugins/extractor/getpot_bgutil_http.py") != null)
            assertTrue(zip.getEntry("yt_dlp_plugins/extractor/getpot_bgutil.py") != null)
        }
    }
}
