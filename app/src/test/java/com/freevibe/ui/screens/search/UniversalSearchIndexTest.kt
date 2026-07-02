package com.freevibe.ui.screens.search

import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.WallpaperCollectionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalSearchIndexTest {
    @Test
    fun `blank query returns no local results`() {
        assertTrue(
            buildUniversalSearchResults(
                query = " ",
                favorites = emptyList(),
                downloads = emptyList(),
                collections = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `query returns segmented offline library results`() {
        val results = buildUniversalSearchResults(
            query = "neon",
            favorites = listOf(
                FavoriteEntity(
                    id = "wall-1",
                    source = "WALLHAVEN",
                    type = "WALLPAPER",
                    thumbnailUrl = "",
                    fullUrl = "https://example.test/neon-wall.jpg",
                    name = "Neon city",
                    category = "AMOLED",
                    offlinePath = "/storage/emulated/0/Pictures/neon-wall.jpg",
                ),
                FavoriteEntity(
                    id = "sound-1",
                    source = "YOUTUBE",
                    type = "SOUND",
                    thumbnailUrl = "",
                    fullUrl = "https://example.test/neon-chime.mp3",
                    name = "Neon chime",
                    fileType = "mp3",
                ),
            ),
            downloads = listOf(
                DownloadEntity(
                    id = "video-1",
                    source = "YOUTUBE",
                    type = "VIDEO",
                    localPath = "/storage/emulated/0/Movies/neon-loop.mp4",
                    name = "Neon loop",
                ),
                DownloadEntity(
                    id = "download-1",
                    source = "WALLHAVEN",
                    type = "WALLPAPER",
                    localPath = "/storage/emulated/0/Pictures/neon-local.jpg",
                    name = "Neon local",
                ),
            ),
            collections = listOf(
                WallpaperCollectionEntity(
                    collectionId = 7,
                    name = "Neon pack",
                ),
            ),
        )

        val sections = results.map { it.section }.toSet()
        listOf(
            UniversalSearchSection.WALLPAPERS,
            UniversalSearchSection.VIDEOS,
            UniversalSearchSection.SOUNDS,
            UniversalSearchSection.COLLECTIONS,
            UniversalSearchSection.DOWNLOADS,
            UniversalSearchSection.FAVORITES,
            UniversalSearchSection.LOCAL_FILES,
        ).forEach { section ->
            assertTrue("Missing section $section in $sections", section in sections)
        }
        assertEquals(
            UniversalSearchBadge.LOCAL,
            results.first { it.section == UniversalSearchSection.DOWNLOADS }.badge,
        )
    }
}
