package com.freevibe.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitCanvasControlsTest {
    @Test
    fun `hex colors accept rgb and ignore supplied alpha`() {
        assertEquals(0xFF123ABC.toInt(), parseFitCanvasColor("#123abc"))
        assertEquals(0xFF223344.toInt(), parseFitCanvasColor("80223344"))
    }

    @Test
    fun `invalid color text is rejected`() {
        assertNull(parseFitCanvasColor("#12345"))
        assertNull(parseFitCanvasColor("#not-a-color"))
    }
}
