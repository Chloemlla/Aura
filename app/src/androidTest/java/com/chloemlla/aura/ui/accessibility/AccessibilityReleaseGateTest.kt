package com.chloemlla.aura.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.chloemlla.aura.ui.screens.fixtures.AuraRouteFixture
import com.chloemlla.aura.ui.screens.fixtures.AuraRouteStateFixture
import com.chloemlla.aura.ui.theme.FreeVibeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release accessibility gate for real Aura route-state fixtures.
 *
 * These checks render the same debug route fixtures used by screenshot QA rather
 * than standalone component primitives, so failures map back to user-facing
 * Wallpapers, Sounds, Settings, Videos, and editor/detail surfaces.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityReleaseGateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableComposeAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun wallpapersGridRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.WallpapersGridSuccess,
            expectedText = "Fresh AMOLED picks",
        )
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun wallpapersOfflineRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.WallpapersOfflineEmpty,
            expectedText = "Offline wallpaper search",
            darkTheme = false,
        )
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun soundsDetailRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.SoundDetailReady,
            expectedText = "Midnight Pulse",
        )
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun settingsDiagnosticsRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.SettingsProviderDisabled,
            expectedText = "Local-first controls",
            darkTheme = false,
            fontScale = 2.0f,
        )
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun videoWallpapersRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.VideoWallpapersError,
            expectedText = "Video wallpaper metadata",
        )
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun wallpaperEditorRouteFixturePassesAccessibilityChecks() {
        renderFixture(
            fixture = AuraRouteFixture.WallpaperEditorLoading,
            expectedText = "Wallpaper editor recovery",
            darkTheme = false,
        )
    }

    private fun renderFixture(
        fixture: AuraRouteFixture,
        expectedText: String,
        darkTheme: Boolean = true,
        fontScale: Float = 1.0f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FreeVibeTheme(darkTheme = darkTheme, dynamicColor = false) {
                    AuraRouteStateFixture(fixture = fixture)
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(expectedText).assertExists()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }
}
