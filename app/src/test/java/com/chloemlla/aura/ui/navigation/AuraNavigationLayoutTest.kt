package com.chloemlla.aura.ui.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuraNavigationLayoutTest {
    @Test
    fun `compact widths keep bottom navigation`() {
        val layout = auraNavigationLayoutForWidth(411.dp)

        assertEquals(AuraNavigationLayout.CompactBottomBar, layout)
        assertFalse(layout.isExpanded)
    }

    @Test
    fun `expanded widths use navigation rail`() {
        val layout = auraNavigationLayoutForWidth(840.dp)

        assertEquals(AuraNavigationLayout.ExpandedRail, layout)
        assertTrue(layout.isExpanded)
    }
}
