package com.chloemlla.aura.ui.screens.wallpapers

import com.chloemlla.aura.data.legal.isProviderAvailableInCurrentArtifact
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperPreviewPolicyTest {

    @Test
    fun `preview apply follows the installed artifact provider ceiling`() {
        assertTrue(canApplyFromWallpaperPreview(wallpaper(ContentSource.LOCAL)))
        assertFalse(canApplyFromWallpaperPreview(wallpaper(ContentSource.PICSUM)))
        assertEquals(
            isProviderAvailableInCurrentArtifact(ContentSource.AI_GENERATED),
            canApplyFromWallpaperPreview(wallpaper(ContentSource.AI_GENERATED)),
        )
    }

    private fun wallpaper(source: ContentSource) = Wallpaper(
        id = "saved_${source.name.lowercase()}",
        source = source,
        thumbnailUrl = "file:///saved/thumb.jpg",
        fullUrl = "file:///saved/full.jpg",
        width = 1080,
        height = 1920,
        license = if (source == ContentSource.LOCAL) "Local User Content" else "CC0",
        uploaderName = "Saved creator",
        sourcePageUrl = "https://example.com/source",
    )
}
