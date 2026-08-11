package com.chloemlla.aura.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier

@Composable
internal fun SettingsSectionAnchorTarget(
    section: String,
    initialSection: String?,
    content: @Composable () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }

    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        content()
    }
    LaunchedEffect(section, initialSection) {
        if (initialSection == section) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
}
