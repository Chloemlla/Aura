package com.freevibe.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AiWallpaperRepositoryGeneratedTagsTest {
    @Test
    fun `generated wallpaper tags omit prompt words`() {
        assertEquals(
            listOf("ai-generated", "photographic"),
            AiWallpaperRepository.generatedWallpaperTags(AiStyle.PHOTOGRAPHIC),
        )
        assertEquals(
            listOf("ai-generated"),
            AiWallpaperRepository.generatedWallpaperTags(AiStyle.NONE),
        )
    }
}
