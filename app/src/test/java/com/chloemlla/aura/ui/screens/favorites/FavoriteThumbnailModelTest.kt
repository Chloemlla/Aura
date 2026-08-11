package com.chloemlla.aura.ui.screens.favorites

import com.chloemlla.aura.data.model.FavoriteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The favorites grid asked for the remote thumbnail even when the wallpaper had
 * already been cached to a managed local file, so airplane mode showed a grid of
 * broken cards over bytes that were sitting on disk.
 */
class FavoriteThumbnailModelTest {

    private fun favorite(
        type: String = "WALLPAPER",
        offlinePath: String = "",
    ) = FavoriteEntity(
        id = "wp-1",
        source = "WALLHAVEN",
        type = type,
        thumbnailUrl = "https://w.example/t.jpg",
        fullUrl = "https://w.example/f.jpg",
        offlinePath = offlinePath,
    )

    @Test
    fun `an offline wallpaper renders from its managed local file`() {
        val model = favoriteThumbnailModel(
            favorite(offlinePath = "/data/files/offline/wp-1.jpg"),
            fileExists = { true },
        )

        assertEquals("/data/files/offline/wp-1.jpg", model)
    }

    @Test
    fun `a wallpaper with no offline copy still uses the remote thumbnail`() {
        assertEquals(
            "https://w.example/t.jpg",
            favoriteThumbnailModel(favorite(), fileExists = { true }),
        )
    }

    @Test
    fun `an evicted offline file falls back to the remote thumbnail`() {
        assertEquals(
            "https://w.example/t.jpg",
            favoriteThumbnailModel(
                favorite(offlinePath = "/data/files/offline/gone.jpg"),
                fileExists = { false },
            ),
        )
    }

    @Test
    fun `a blank offline path is ignored`() {
        assertEquals(
            "https://w.example/t.jpg",
            favoriteThumbnailModel(favorite(offlinePath = "   "), fileExists = { true }),
        )
    }

    @Test
    fun `sound favorites are untouched because their offline copy is audio`() {
        assertEquals(
            "https://w.example/t.jpg",
            favoriteThumbnailModel(
                favorite(type = "SOUND", offlinePath = "/data/files/offline/sound.mp3"),
                fileExists = { true },
            ),
        )
    }
}
