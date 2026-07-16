package com.freevibe.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityAiDisclosureTest {
    @Test
    fun `legacy unlabeled content remains visible when AI content is hidden`() {
        assertTrue(shouldShowCommunityContent(isAiGenerated = null, hideAiGenerated = true))
        assertTrue(shouldShowCommunityContent(isAiGenerated = false, hideAiGenerated = true))
        assertFalse(shouldShowCommunityContent(isAiGenerated = true, hideAiGenerated = true))
        assertTrue(shouldShowCommunityContent(isAiGenerated = true, hideAiGenerated = false))
    }

    @Test
    fun `community models default AI disclosure to unlabeled`() {
        val wallpaper = Wallpaper(
            id = "legacy-wallpaper",
            source = ContentSource.COMMUNITY,
            thumbnailUrl = "https://example.com/thumb.jpg",
            fullUrl = "https://example.com/full.jpg",
            width = 1080,
            height = 1920,
        )
        val sound = Sound(
            id = "legacy-sound",
            source = ContentSource.COMMUNITY,
            name = "Legacy tone",
            previewUrl = "https://example.com/preview.mp3",
            downloadUrl = "https://example.com/tone.mp3",
        )

        assertNull(wallpaper.isAiGenerated)
        assertNull(sound.isAiGenerated)
    }
}
