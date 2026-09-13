package com.freevibe

import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCachePolicyTest {

    @Test
    fun `foreground image cache leaves room for wallpaper decoding`() {
        assertTrue(IMAGE_MEMORY_CACHE_MAX_FRACTION in 0.05..0.125)
    }

    @Test
    fun `background image cache keeps only a small part of its foreground maximum`() {
        assertTrue(IMAGE_MEMORY_CACHE_BACKGROUND_FRACTION in 0.0..0.15)
    }
}
