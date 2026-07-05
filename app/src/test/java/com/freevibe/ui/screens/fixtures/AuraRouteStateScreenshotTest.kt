package com.freevibe.ui.screens.fixtures

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
class AuraRouteStateScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wallpapersGridAmoled() {
        captureFixture(AuraRouteFixture.WallpapersGridSuccess, darkTheme = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun wallpapersGridExpandedAmoled() {
        captureFixture(
            fixture = AuraRouteFixture.WallpapersGridSuccess,
            darkTheme = true,
            width = 1280.dp,
            height = 800.dp,
        )
    }

    @Test
    fun wallpapersOfflineLight() {
        captureFixture(AuraRouteFixture.WallpapersOfflineEmpty, darkTheme = false)
    }

    @Test
    fun soundDetailAmoled() {
        captureFixture(AuraRouteFixture.SoundDetailReady, darkTheme = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun soundDetailExpandedAmoled() {
        captureFixture(
            fixture = AuraRouteFixture.SoundDetailReady,
            darkTheme = true,
            width = 1280.dp,
            height = 800.dp,
        )
    }

    @Test
    fun settingsProviderDisabledLightLargeFont() {
        captureFixture(
            fixture = AuraRouteFixture.SettingsProviderDisabled,
            darkTheme = false,
            fontScale = 2.0f,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rXA-w411dp-h891dp-xhdpi")
    fun wallpapersGridCompactEnglishXa() {
        captureFixture(
            fixture = AuraRouteFixture.WallpapersGridSuccess,
            darkTheme = true,
            pseudoLocaleTag = "en_XA",
            textTransform = ::englishXaPseudo,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rXB-w411dp-h891dp-xhdpi")
    fun settingsProviderDisabledCompactArabicXbRtl() {
        captureFixture(
            fixture = AuraRouteFixture.SettingsProviderDisabled,
            darkTheme = false,
            fontScale = 1.3f,
            layoutDirection = LayoutDirection.Rtl,
            pseudoLocaleTag = "ar_XB",
            textTransform = ::arabicXbPseudo,
        )
    }

    @Test
    fun videoWallpapersErrorAmoledRtl() {
        captureFixture(
            fixture = AuraRouteFixture.VideoWallpapersError,
            darkTheme = true,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    @Test
    fun wallpaperEditorLoadingLight() {
        captureFixture(AuraRouteFixture.WallpaperEditorLoading, darkTheme = false)
    }

    private fun captureFixture(
        fixture: AuraRouteFixture,
        darkTheme: Boolean,
        fontScale: Float = 1.0f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        width: Dp = 411.dp,
        height: Dp = 891.dp,
        pseudoLocaleTag: String? = null,
        textTransform: (String) -> String = { it },
    ) {
        val variant = buildString {
            append(fixture.screenshotName)
            append(if (darkTheme) "_amoled" else "_light")
            if (width != 411.dp || height != 891.dp) {
                append("_${width.value.toInt()}x${height.value.toInt()}")
            }
            if (fontScale > 1.0f) append("_font${fontScale.toString().replace(".", "_")}")
            if (layoutDirection == LayoutDirection.Rtl) append("_rtl")
            pseudoLocaleTag?.let { append("_$it") }
        }

        composeRule.setContent {
            FixtureRoot(
                darkTheme = darkTheme,
                fontScale = fontScale,
                layoutDirection = layoutDirection,
                width = width,
                height = height,
                textTransform = textTransform,
            ) {
                AuraRouteStateFixture(fixture)
            }
        }
        composeRule.waitForIdle()
        pseudoLocaleTag?.let {
            composeRule.onNodeWithText(textTransform(fixture.primaryAssertionText())).assertExists()
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$variant.png")
    }
}

private fun AuraRouteFixture.primaryAssertionText(): String = when (this) {
    AuraRouteFixture.WallpapersGridSuccess -> "Fresh AMOLED picks"
    AuraRouteFixture.WallpapersOfflineEmpty -> "Offline wallpaper search"
    AuraRouteFixture.SoundDetailReady -> "Midnight Pulse"
    AuraRouteFixture.SettingsProviderDisabled -> "Local-first controls"
    AuraRouteFixture.VideoWallpapersError -> "Video wallpaper metadata"
    AuraRouteFixture.WallpaperEditorLoading -> "Wallpaper editor recovery"
}

@Composable
private fun FixtureRoot(
    darkTheme: Boolean,
    fontScale: Float,
    layoutDirection: LayoutDirection,
    width: Dp,
    height: Dp,
    textTransform: (String) -> String,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale),
        LocalLayoutDirection provides layoutDirection,
        LocalFixtureTextTransform provides textTransform,
    ) {
        FreeVibeTheme(darkTheme = darkTheme, dynamicColor = false) {
            Surface(
                modifier = Modifier.size(width = width, height = height),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}

private fun englishXaPseudo(text: String): String =
    "[!! " + text.map(::accentLatinChar).joinToString("") + " !!]"

private fun arabicXbPseudo(text: String): String =
    "\u202e" + englishXaPseudo(text) + "\u202c"

private fun accentLatinChar(char: Char): Char = when (char) {
    'A' -> '\u00c5'
    'a' -> '\u00e5'
    'E' -> '\u018e'
    'e' -> '\u0119'
    'I' -> '\u00cf'
    'i' -> '\u00ef'
    'O' -> '\u00d8'
    'o' -> '\u00f8'
    'U' -> '\u00db'
    'u' -> '\u00fb'
    else -> char
}
