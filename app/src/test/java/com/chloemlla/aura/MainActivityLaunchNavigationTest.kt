package com.chloemlla.aura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.service.ExternalAutomationGate
import com.chloemlla.aura.service.TaskerActionReceiver
import com.chloemlla.aura.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLaunchNavigationTest {
    @Test
    fun `buildLaunchNavigation supports route-only launches`() {
        val navigation = buildLaunchNavigation(route = "favorites")

        assertEquals("favorites", navigation?.route)
        assertNull(navigation?.wallpaper)
    }

    @Test
    fun `buildLaunchWallpaper preserves wallpaper metadata from notification extras`() {
        val wallpaper = buildLaunchWallpaper(
            wallpaperId = "reddit_123",
            fullUrl = "https://example.com/full.jpg",
            thumbnailUrl = "https://example.com/thumb.jpg",
            sourceName = ContentSource.REDDIT.name,
            width = 1440,
            height = 3200,
        )

        assertNotNull(wallpaper)
        assertEquals(ContentSource.REDDIT, wallpaper?.source)
        assertEquals(1440, wallpaper?.width)
        assertEquals(3200, wallpaper?.height)
        assertEquals("https://example.com/thumb.jpg", wallpaper?.thumbnailUrl)
    }

    @Test
    fun `saved state gates initial stale launch replay`() {
        assertFalse(shouldHandleInitialLaunchNavigation(Bundle()))
        assertTrue(shouldHandleInitialLaunchNavigation(null))
    }

    @Test
    fun `shortcut actions map to launcher routes`() {
        assertEquals(Screen.Wallpapers.route, routeForShortcutAction(TaskerActionReceiver.ACTION_SHUFFLE_NOW))
        assertEquals(Screen.Wallpapers.route, routeForShortcutAction(TaskerActionReceiver.ACTION_ROTATE_NOW))
        assertEquals(Screen.Wallpapers.route, routeForShortcutAction(ACTION_SHORTCUT_SEARCH))
        assertEquals(Screen.Downloads.route, routeForShortcutAction(ACTION_SHORTCUT_DOWNLOADS))
        assertNull(routeForShortcutAction("com.chloemlla.aura.action.UNKNOWN"))
    }

    @Test
    fun `rotation shortcut detection only accepts rotation actions`() {
        // The activity no longer keeps its own copy of this predicate; it shares the
        // gate's definition so the exported activity and receiver cannot drift.
        assertTrue(ExternalAutomationGate.isSupportedAction(TaskerActionReceiver.ACTION_SHUFFLE_NOW))
        assertTrue(ExternalAutomationGate.isSupportedAction(TaskerActionReceiver.ACTION_ROTATE_NOW))
        assertFalse(ExternalAutomationGate.isSupportedAction(ACTION_SHORTCUT_SEARCH))
        assertFalse(ExternalAutomationGate.isSupportedAction(ACTION_SHORTCUT_DOWNLOADS))
        assertFalse(ExternalAutomationGate.isSupportedAction(null))
    }

    @Test
    fun `buildLaunchWallpaper drops non-https urls from notification extras`() {
        // v6.5.0 HTTPS-only policy for deep-linked wallpaper URLs — cleartext or local
        // file URIs smuggled through a notification intent must not be rehydrated.
        listOf(
            "http://example.com/full.jpg",
            "file:///sdcard/payload.jpg",
            "content://media/external/images/1",
            "javascript:alert(1)",
        ).forEach { unsafe ->
            val wallpaper = buildLaunchWallpaper(
                wallpaperId = "reddit_123",
                fullUrl = unsafe,
                thumbnailUrl = "https://example.com/thumb.jpg",
                sourceName = ContentSource.REDDIT.name,
            )
            assertNull("Expected null for unsafe URL $unsafe", wallpaper)
        }
    }

    @Test
    fun `attach-data only accepts a granted content provider uri`() {
        val allowed = Uri.parse("content://media/external/images/1")
        assertTrue(isAllowedAttachDataUri(allowed, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse(isAllowedAttachDataUri(allowed, 0))
        assertFalse(
            isAllowedAttachDataUri(
                Uri.parse("file:///sdcard/image.jpg"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        assertFalse(
            isAllowedAttachDataUri(
                Uri.parse("content:///image.jpg"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }
}
