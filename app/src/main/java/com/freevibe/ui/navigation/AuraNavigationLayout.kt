package com.freevibe.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class AuraNavigationLayout {
    CompactBottomBar,
    ExpandedRail,
}

internal val LocalAuraNavigationLayout = staticCompositionLocalOf {
    AuraNavigationLayout.CompactBottomBar
}

internal val AuraNavigationLayout.isExpanded: Boolean
    get() = this == AuraNavigationLayout.ExpandedRail

internal fun auraNavigationLayoutForWidth(width: Dp): AuraNavigationLayout =
    if (width >= 840.dp) AuraNavigationLayout.ExpandedRail else AuraNavigationLayout.CompactBottomBar
