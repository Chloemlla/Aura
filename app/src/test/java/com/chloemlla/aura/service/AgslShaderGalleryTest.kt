package com.chloemlla.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgslShaderGalleryTest {

    @Test
    fun `gallery exposes curated preset count without none entry`() {
        val presets = AgslShaderGallery.presets

        assertTrue("AGSL gallery should expose five to ten presets", presets.size in 5..10)
        assertFalse("NONE is a UI option, not a shader preset", presets.any { it.id == AgslShaderGallery.NONE_ID })
    }

    @Test
    fun `gallery preset ids are stable and unique`() {
        val ids = AgslShaderGallery.presets.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains(AgslShaderGallery.AURORA_RIBBONS))
        assertTrue(ids.contains(AgslShaderGallery.CHROMA_MIST))
        assertTrue(ids.contains(AgslShaderGallery.NEON_DUSK))
        assertTrue(ids.contains(AgslShaderGallery.SOLAR_DRIFT))
        assertTrue(ids.contains(AgslShaderGallery.DEEP_OCEAN))
        assertTrue(ids.contains(AgslShaderGallery.MONOCHROME_RAIN))
    }

    @Test
    fun `gallery shaders declare runtime uniforms and main`() {
        AgslShaderGallery.presets.forEach { preset ->
            val agsl = preset.agsl

            assertTrue("${preset.id} must declare resolution uniform", agsl.contains("uniform float2 resolution"))
            assertTrue("${preset.id} must declare time uniform", agsl.contains("uniform float time"))
            assertTrue("${preset.id} must export main", agsl.contains("half4 main"))
        }
    }

    @Test
    fun `gallery rejects unknown or blank preset ids`() {
        assertEquals(AgslShaderGallery.NONE_ID, AgslShaderGallery.sanitizeId(""))
        assertEquals(AgslShaderGallery.NONE_ID, AgslShaderGallery.sanitizeId("custom_shader_input"))
        assertEquals(AgslShaderGallery.AURORA_RIBBONS, AgslShaderGallery.sanitizeId(AgslShaderGallery.AURORA_RIBBONS))
    }
}
