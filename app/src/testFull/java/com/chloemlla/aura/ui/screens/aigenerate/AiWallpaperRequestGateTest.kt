package com.chloemlla.aura.ui.screens.aigenerate

import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.CommunityReportReason
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.repository.AiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWallpaperRequestGateTest {

    @Test
    fun `disabled generated source wins before prompt and key checks`() {
        assertEquals(
            R.string.ai_feedback_provider_disabled,
            generatedWallpaperRequestError(
                providerEnabled = false,
                prompt = "",
                apiKey = "",
                disclosureAccepted = false,
            ),
        )
    }

    @Test
    fun `enabled generated source still requires prompt and key`() {
        assertEquals(
            R.string.ai_feedback_prompt_required,
            generatedWallpaperRequestError(
                providerEnabled = true,
                prompt = "",
                apiKey = "",
                disclosureAccepted = false,
            ),
        )

        assertEquals(
            R.string.ai_feedback_key_required,
            generatedWallpaperRequestError(
                providerEnabled = true,
                prompt = "misty canyon",
                apiKey = "",
                disclosureAccepted = false,
            ),
        )
    }

    @Test
    fun `enabled generated source requires disclosure acceptance before request`() {
        assertEquals(
            R.string.ai_feedback_disclosure_required,
            generatedWallpaperRequestError(
                providerEnabled = true,
                prompt = "misty canyon",
                apiKey = "sk-test",
                disclosureAccepted = false,
            ),
        )
    }

    @Test
    fun `enabled generated source accepts populated prompt key and disclosure`() {
        assertNull(
            generatedWallpaperRequestError(
                providerEnabled = true,
                prompt = "misty canyon",
                apiKey = "sk-test",
                disclosureAccepted = true,
            ),
        )
    }

    @Test
    fun `active generation blocks duplicate submits before another request`() {
        assertEquals(
            R.string.ai_feedback_generation_in_progress,
            generatedWallpaperRequestError(
                providerEnabled = true,
                prompt = "misty canyon",
                apiKey = "sk-test",
                disclosureAccepted = true,
                isGenerating = true,
            ),
        )
    }

    @Test
    fun `duplicate confirmation matches normalized prompt and style`() {
        val last = generatedWallpaperRequestSignature(
            prompt = "Misty canyon at dawn",
            style = AiStyle.PHOTOGRAPHIC,
        )

        val confirmation = duplicateGenerationConfirmation(
            prompt = "  misty   canyon at dawn  ",
            style = AiStyle.PHOTOGRAPHIC,
            lastSuccessfulRequest = last,
            blankPromptPreviewFallback = "Fallback preview",
        )

        assertEquals("misty canyon at dawn", confirmation?.promptPreview)
        assertEquals("Photo", confirmation?.styleLabel)
    }

    @Test
    fun `duplicate confirmation ignores different style`() {
        val last = generatedWallpaperRequestSignature(
            prompt = "Misty canyon at dawn",
            style = AiStyle.PHOTOGRAPHIC,
        )

        assertNull(
            duplicateGenerationConfirmation(
                prompt = "Misty canyon at dawn",
                style = AiStyle.CINEMATIC,
                lastSuccessfulRequest = last,
                blankPromptPreviewFallback = "Fallback preview",
            ),
        )
    }

    @Test
    fun `generated wallpaper report input omits local file details`() {
        val wallpaper = Wallpaper(
            id = "generated-1",
            source = ContentSource.AI_GENERATED,
            thumbnailUrl = "file:///cache/thumb.png",
            fullUrl = "file:///cache/sk-secret-output.png",
            width = 1024,
            height = 1792,
            sourcePageUrl = "file:///cache/prompt.json",
        )

        val input = generatedWallpaperReportInput(
            wallpaper = wallpaper,
            reason = CommunityReportReason.DECEPTIVE,
            note = "looks like a login page",
        )

        assertEquals("WALLPAPER::AI_GENERATED::generated-1", input.contentId)
        assertEquals(ContentSource.AI_GENERATED, input.contentSource)
        assertEquals(CommunityReportReason.DECEPTIVE, input.reason)
        assertEquals("", input.sourceUrl)
        assertEquals("Generated wallpaper", input.license)
        assertEquals("Aura generated wallpaper", input.uploaderName)
        assertFalse(input.toString().contains("sk-secret"))
    }

    @Test
    fun `generated wallpaper favorite entity does not retain prompt text`() {
        val wallpaper = Wallpaper(
            id = "generated-1",
            source = ContentSource.AI_GENERATED,
            thumbnailUrl = "file:///cache/thumb.png",
            fullUrl = "file:///cache/full.png",
            width = 1024,
            height = 1792,
            tags = listOf("ai-generated", "photographic", "private", "bedroom"),
        )

        val favorite = generatedWallpaperFavoriteEntity(wallpaper)

        assertEquals("Generated wallpaper", favorite.name)
        assertEquals("ai-generated,photographic", favorite.tags)
        assertFalse(favorite.toString().contains("private"))
        assertFalse(favorite.toString().contains("bedroom"))
    }

    @Test
    fun `generated wallpaper community uploads are always AI flagged`() {
        val generated = Wallpaper(
            id = "generated-1",
            source = ContentSource.AI_GENERATED,
            thumbnailUrl = "file:///cache/thumb.png",
            fullUrl = "file:///cache/full.png",
            width = 1024,
            height = 1792,
        )
        val legacyCommunity = generated.copy(
            id = "community-1",
            source = ContentSource.COMMUNITY,
            isAiGenerated = null,
        )

        assertTrue(generatedWallpaperCommunityAiFlag(generated))
        assertFalse(generatedWallpaperCommunityAiFlag(legacyCommunity))
        assertTrue(generatedWallpaperCommunityAiFlag(legacyCommunity.copy(isAiGenerated = true)))
    }
}
