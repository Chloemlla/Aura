package com.freevibe.service

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WallpaperApplierStreamingTest {

    @Test
    fun `encoded source at cap is readable`() {
        val input = ByteArrayInputStream(ByteArray(8) { it.toByte() })
        val limited = WallpaperByteLimitInputStream(input, maxBytes = 8)

        assertEquals(8, limited.readBytes().size)
    }

    @Test
    fun `encoded source beyond cap fails during stream consumption`() {
        val input = ByteArrayInputStream(ByteArray(9))
        val limited = WallpaperByteLimitInputStream(input, maxBytes = 8)

        try {
            limited.readBytes()
            fail("an oversized source must be rejected before WallpaperManager consumes it")
        } catch (_: IOException) {
            // expected
        }
    }
}
