package com.chloemlla.aura.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationShellContractTest {
    @Test
    fun `root switches primary navigation surfaces by width`() {
        val source = File("src/main/java/com/freevibe/ui/FreeVibeRoot.kt").readText()

        assertTrue(source.contains("BoxWithConstraints("))
        assertTrue(source.contains("auraNavigationLayoutForWidth(maxWidth)"))
        assertTrue(source.contains("val useNavigationRail = showBottomBar && navigationLayout.isExpanded"))
        assertTrue(source.contains("if (showBottomBar && !useNavigationRail)"))
        assertTrue(source.contains("PrimaryNavigationRail("))
    }

    @Test
    fun `primary media screens receive expanded shell state`() {
        val source = File("src/main/java/com/freevibe/ui/FreeVibeRoot.kt").readText()

        assertTrue(source.contains("WallpapersScreen("))
        assertTrue(source.contains("SoundsScreen("))
        assertTrue(source.contains("isExpandedLayout = navigationLayout.isExpanded"))
    }
}
