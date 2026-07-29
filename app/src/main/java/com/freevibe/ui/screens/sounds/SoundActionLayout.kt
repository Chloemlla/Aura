package com.freevibe.ui.screens.sounds

/** Horizontal padding the sound detail content column applies on each side. */
internal const val SOUND_DETAIL_HORIZONTAL_PADDING_DP = 16

/**
 * Width a labelled secondary action needs before its label starts ellipsizing.
 *
 * A `TextButton` spends 12dp of content padding per side, an 18dp icon, and a 6dp
 * spacer before the label gets any room; the longest label ("Contact") needs
 * roughly 56dp at the 12sp label style. Rounded up so the check errs toward
 * reflowing rather than truncating.
 */
internal const val SOUND_SECONDARY_ACTION_MIN_WIDTH_DP = 104

/** Gap between actions in a row. */
internal const val SOUND_ACTION_SPACING_DP = 8

/** Font scale at which any multi-action row is stacked regardless of width. */
internal const val SOUND_ACTION_STACK_FONT_SCALE = 1.3f

/**
 * True when [itemCount] actions cannot share one row without truncating.
 *
 * Pure so the reflow rule is unit-testable at the exact widths the device audit
 * used (411dp at default scale, and the same layout at 200% font scale) instead
 * of relying on a screenshot to notice a clipped label.
 */
internal fun shouldStackSoundActions(
    availableWidthDp: Int,
    itemCount: Int,
    minItemWidthDp: Int,
    fontScale: Float,
    spacingDp: Int = SOUND_ACTION_SPACING_DP,
): Boolean {
    if (itemCount <= 1) return false
    if (fontScale >= SOUND_ACTION_STACK_FONT_SCALE) return true
    val required = itemCount * minItemWidthDp + (itemCount - 1) * spacingDp
    return availableWidthDp < required
}
