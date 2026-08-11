package com.chloemlla.aura.service

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExporterFormatTest {

    private val adapter = Moshi.Builder().build().adapter(LibraryExportFile::class.java)

    @Test
    fun `current library export omits device local downloads`() {
        val json = adapter.toJson(
            LibraryExportFile(
                favorites = listOf(
                    FavoriteExportEntry(
                        id = "wallpaper-1",
                        source = "WALLHAVEN",
                        type = "WALLPAPER",
                    ),
                ),
            ),
        )

        assertTrue(json.contains("\"version\":2"))
        assertTrue(json.contains("\"favorites\""))
        assertFalse(json.contains("\"downloads\""))
        assertFalse(json.contains("localPath"))
    }

    @Test
    fun `legacy download rows are ignored while portable data still imports`() {
        val parsed = adapter.fromJson(
            """
                {
                  "version": 1,
                  "favorites": [{
                    "id": "wallpaper-1",
                    "source": "WALLHAVEN",
                    "type": "WALLPAPER"
                  }],
                  "downloads": [{
                    "id": "download-1",
                    "source": "WALLHAVEN",
                    "type": "WALLPAPER",
                    "localPath": "/storage/emulated/0/old-device.jpg"
                  }]
                }
            """.trimIndent(),
        )!!

        assertEquals(1, parsed.version)
        assertEquals(listOf("wallpaper-1"), parsed.favorites.map { it.id })
    }
}
