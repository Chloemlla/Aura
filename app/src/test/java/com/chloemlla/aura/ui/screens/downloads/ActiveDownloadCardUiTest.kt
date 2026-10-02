package com.chloemlla.aura.ui.screens.downloads

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chloemlla.aura.service.DownloadProgress
import com.chloemlla.aura.ui.theme.FreeVibeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActiveDownloadCardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failed card shows why and Retry names the file and retries it`() {
        var retries = 0
        var dismissals = 0
        val reason = "Couldn't reach the server. Check your connection."
        composeRule.setContent {
            FreeVibeTheme(darkTheme = true) {
                ActiveDownloadCard(
                    dl = DownloadProgress("d1", "night.png", 0f, 0, 0, error = reason),
                    onDismiss = { dismissals++ },
                    onRetry = { retries++ },
                )
            }
        }

        composeRule.onNodeWithText(reason, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Retry") and hasClickAction())
            .assert(
                SemanticsMatcher("its click action is labeled Retry night.png") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == "Retry night.png"
                },
            )
            .performClick()

        assertEquals(1, retries)
        assertEquals(0, dismissals)
    }

    @Test
    fun `a running card offers no Retry`() {
        composeRule.setContent {
            FreeVibeTheme(darkTheme = true) {
                ActiveDownloadCard(
                    dl = DownloadProgress("d1", "night.png", 0.4f, 1000, 400),
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Retry", useUnmergedTree = true).assertCountEquals(0)
    }
}
