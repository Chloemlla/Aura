package com.chloemlla.aura.ui.screens.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.chloemlla.aura.ui.theme.FreeVibeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CropAspectControlsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `only the chosen ratio reads as selected and the frame label announces politely`() {
        var aspect by mutableStateOf(CropAspect.FREE)
        composeRule.setContent {
            FreeVibeTheme(darkTheme = true) {
                Column {
                    CropAspectIndicator(aspect)
                    Row { CropAspectChips(selected = aspect, enabled = true, onSelect = { aspect = it }) }
                }
            }
        }
        composeRule.onNode(chip("Free")).assertIsSelected()

        composeRule.onNode(chip("1:1")).performClick()

        assertEquals(CropAspect.SQUARE, aspect)
        composeRule.onNode(chip("1:1")).assertIsSelected()
        listOf("Free", "9:16", "16:9").forEach { composeRule.onNode(chip(it)).assertIsNotSelected() }
        composeRule.onNodeWithContentDescription("Crop frame 1:1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun chip(label: String) = hasText(label) and isSelectable()
}
