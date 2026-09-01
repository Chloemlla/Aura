package com.chloemlla.aura.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationShellContractTest {
    @Test
    fun `root switches primary navigation surfaces by width`() {
        val source = File("src/main/java/com/chloemlla/aura/ui/FreeVibeRoot.kt").readText()

        assertTrue(source.contains("BoxWithConstraints("))
        assertTrue(source.contains("auraNavigationLayoutForWidth(maxWidth)"))
        assertTrue(source.contains("val useNavigationRail = showBottomBar && navigationLayout.isExpanded"))
        assertTrue(source.contains("if (showBottomBar && !useNavigationRail)"))
        assertTrue(source.contains("PrimaryNavigationRail("))
    }

    @Test
    fun `primary media screens receive expanded shell state`() {
        val source = File("src/main/java/com/chloemlla/aura/ui/FreeVibeRoot.kt").readText()
        val wallpapers =
            File("src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpapersScreen.kt").readText()
        val sounds = File("src/main/java/com/chloemlla/aura/ui/screens/sounds/SoundsScreen.kt").readText()

        assertTrue(source.contains("WallpapersScreen("))
        assertTrue(source.contains("SoundsScreen("))
        // The shell publishes the width class through a composition local rather than
        // capturing it in the NavHost builders (AURA-G9-04), so each screen defaults
        // from the local instead of taking it as a call-site argument.
        assertTrue(source.contains("CompositionLocalProvider(LocalAuraNavigationLayout provides navigationLayout)"))
        assertTrue(
            wallpapers.contains("isExpandedLayout: Boolean = LocalAuraNavigationLayout.current.isExpanded"),
        )
        assertTrue(sounds.contains("isExpandedLayout: Boolean = LocalAuraNavigationLayout.current.isExpanded"))
    }
}
