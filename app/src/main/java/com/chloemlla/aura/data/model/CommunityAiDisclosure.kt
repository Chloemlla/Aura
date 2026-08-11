package com.chloemlla.aura.data.model

fun shouldShowCommunityContent(
    isAiGenerated: Boolean?,
    hideAiGenerated: Boolean,
): Boolean = !hideAiGenerated || isAiGenerated != true
