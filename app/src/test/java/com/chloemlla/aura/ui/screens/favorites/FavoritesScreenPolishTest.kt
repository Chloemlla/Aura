package com.chloemlla.aura.ui.screens.favorites

import android.content.res.Resources
import com.chloemlla.aura.data.model.FavoriteEntity
import com.chloemlla.aura.data.model.LocalMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavoritesScreenPolishTest {

    private val resources: Resources
        get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `favorite wallpaper summary names selection and source health`() {
        val favorite = FavoriteEntity(
            id = "wall-1",
            source = "WALLHAVEN",
            type = "WALLPAPER",
            thumbnailUrl = "https://example.com/thumb.jpg",
            fullUrl = "https://example.com/full.jpg",
            name = "Amber lockscreen",
            width = 1440,
            height = 3200,
            category = "Minimal",
        )

        assertEquals(
            "Amber lockscreen. selected. Minimal, 1440 by 3200, Wallhaven",
            favoriteWallpaperSummary(
                favorite = favorite,
                isSelected = true,
                sourceUnavailable = false,
                resources = resources,
            ),
        )
        assertEquals(
            "Amber lockscreen. source unavailable. Minimal, 1440 by 3200, Wallhaven",
            favoriteWallpaperSummary(
                favorite = favorite,
                isSelected = false,
                sourceUnavailable = true,
                resources = resources,
            ),
        )
    }

    @Test
    fun `favorite sound summary includes duration and source`() {
        val favorite = FavoriteEntity(
            id = "tone-1",
            source = "YOUTUBE",
            type = "SOUND",
            thumbnailUrl = "",
            fullUrl = "https://example.com/sound",
            name = "Soft chime",
            duration = 12.4,
        )

        assertEquals(
            "Soft chime. saved sound. 12 seconds. YouTube",
            favoriteSoundSummary(favorite, sourceUnavailable = false, resources = resources),
        )
    }

    @Test
    fun `favorite summaries distinguish missing revoked and corrupt local media`() {
        val favorite = FavoriteEntity(
            id = "local-1",
            source = "LOCAL",
            type = "WALLPAPER",
            thumbnailUrl = "content://old/photo",
            fullUrl = "content://old/photo",
            offlinePath = "content://old/photo",
            localMediaStatus = LocalMediaStatus.PERMISSION_REVOKED,
        )

        assertEquals(
            "local-1. local file permission revoked. Local",
            favoriteWallpaperSummary(
                favorite,
                isSelected = false,
                sourceUnavailable = false,
                resources = resources,
            ),
        )
        assertEquals("local file corrupt", favoriteLocalMediaHealthLabel(LocalMediaStatus.CORRUPT))
        assertEquals("local file missing", favoriteLocalMediaHealthLabel(LocalMediaStatus.MISSING))
    }

    @Test
    fun `batch progress summary includes outcomes and current item`() {
        assertEquals(
            "Downloading favorites 3 of 10. 1 failed, 2 blocked. Current item: sky.jpg",
            favoritesBatchProgressSummary(
                processed = 3,
                total = 10,
                failed = 1,
                blocked = 2,
                currentItem = "sky.jpg",
                resources = resources,
            ),
        )
    }
}
