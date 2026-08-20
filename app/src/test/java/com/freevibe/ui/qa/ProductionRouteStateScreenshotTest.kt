package com.freevibe.ui.qa

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.freevibe.ui.theme.FreeVibeTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ProductionRouteStateScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wallpapersGridAmoled() {
        captureScenario(ProductionRouteScenario.WallpapersGridSuccess, darkTheme = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun wallpapersGridExpandedAmoled() {
        captureScenario(
            scenario = ProductionRouteScenario.WallpapersGridSuccess,
            darkTheme = true,
            width = 1280.dp,
            height = 800.dp,
        )
    }

    @Test
    fun wallpapersOfflineLight() {
        captureScenario(ProductionRouteScenario.WallpapersOfflineEmpty, darkTheme = false)
    }

    @Test
    fun soundDetailAmoled() {
        captureScenario(ProductionRouteScenario.SoundDetailReady, darkTheme = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun soundDetailExpandedAmoled() {
        captureScenario(
            scenario = ProductionRouteScenario.SoundDetailReady,
            darkTheme = true,
            width = 1280.dp,
            height = 800.dp,
        )
    }

    @Test
    fun settingsProviderDisabledLightLargeFont() {
        captureScenario(
            scenario = ProductionRouteScenario.SettingsProviderDisabled,
            darkTheme = false,
            fontScale = 2.0f,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rXA-w411dp-h891dp-xhdpi")
    fun wallpapersGridCompactEnglishXa() {
        captureScenario(
            scenario = ProductionRouteScenario.WallpapersGridSuccess,
            darkTheme = true,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rXB-w411dp-h891dp-xhdpi")
    fun settingsProviderDisabledCompactArabicXbRtl() {
        captureScenario(
            scenario = ProductionRouteScenario.SettingsProviderDisabled,
            darkTheme = false,
            fontScale = 1.3f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    @Test
    fun videoWallpapersErrorAmoledRtl() {
        captureScenario(
            scenario = ProductionRouteScenario.VideoWallpapersError,
            darkTheme = true,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    @Test
    fun wallpaperEditorLoadingLight() {
        captureScenario(ProductionRouteScenario.WallpaperEditorLoading, darkTheme = false)
    }

    private fun captureScenario(
        scenario: ProductionRouteScenario,
        darkTheme: Boolean,
        fontScale: Float = 1.0f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        width: Dp = 411.dp,
        height: Dp = 891.dp,
    ) {
        val variant = buildString {
            append(scenario.screenshotName)
            append(if (darkTheme) "_amoled" else "_light")
            if (width != 411.dp || height != 891.dp) append("_${width.value.toInt()}x${height.value.toInt()}")
            if (fontScale > 1.0f) append("_font${fontScale.toString().replace(".", "_")}")
            if (layoutDirection == LayoutDirection.Rtl) append("_rtl")
        }
        val expectedText = ApplicationProvider
            .getApplicationContext<Context>()
            .getString(scenario.assertionResource)

        composeRule.setContent {
            RouteStateRoot(
                darkTheme = darkTheme,
                fontScale = fontScale,
                layoutDirection = layoutDirection,
                width = width,
                height = height,
            ) {
                ProductionRouteState(scenario)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(expectedText).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$variant.png")
    }
}

@Composable
private fun RouteStateRoot(
    darkTheme: Boolean,
    fontScale: Float,
    layoutDirection: LayoutDirection,
    width: Dp,
    height: Dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale),
        LocalLayoutDirection provides layoutDirection,
    ) {
        FreeVibeTheme(darkTheme = darkTheme, dynamicColor = false) {
            Surface(
                modifier = Modifier.size(width, height),
                content = content,
            )
        }
    }
}
