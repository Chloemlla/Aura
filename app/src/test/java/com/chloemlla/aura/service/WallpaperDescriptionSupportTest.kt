package com.chloemlla.aura.service

import android.os.PersistableBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperDescriptionSupportTest {

    @Test
    fun `instance content keeps media and weather settings`() {
        val content = auraWallpaperDescriptionContent(
            source = "/storage/emulated/0/Pictures/aura.jpg",
            shaderPresetId = AgslShaderGallery.AURORA_RIBBONS,
            weatherEffect = "RAIN",
            windSpeed = 4.5,
        )

        assertEquals(
            "/storage/emulated/0/Pictures/aura.jpg",
            content.getString(WALLPAPER_DESCRIPTION_SOURCE_KEY),
        )
        assertEquals(
            AgslShaderGallery.AURORA_RIBBONS,
            content.getString(WALLPAPER_DESCRIPTION_SHADER_PRESET_KEY),
        )
        assertEquals("RAIN", content.getString(WALLPAPER_DESCRIPTION_WEATHER_EFFECT_KEY))
        assertEquals(4.5, content.getDouble(WALLPAPER_DESCRIPTION_WIND_SPEED_KEY), 0.0)
    }

    @Test
    fun `empty instance content does not invent values`() {
        val content: PersistableBundle = auraWallpaperDescriptionContent()

        assertNull(content.getString(WALLPAPER_DESCRIPTION_SOURCE_KEY))
        assertNull(content.getString(WALLPAPER_DESCRIPTION_SHADER_PRESET_KEY))
        assertNull(content.getString(WALLPAPER_DESCRIPTION_WEATHER_EFFECT_KEY))
        check(!content.containsKey(WALLPAPER_DESCRIPTION_WIND_SPEED_KEY))
    }

    @Test
    fun `description ids are stable for the same content`() {
        val content = AuraWallpaperDescriptionContent(source = "clip.mp4")

        assertEquals(
            auraWallpaperDescriptionId("video", content),
            auraWallpaperDescriptionId("video", content),
        )
    }
}
