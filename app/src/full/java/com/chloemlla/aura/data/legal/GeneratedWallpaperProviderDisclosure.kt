package com.chloemlla.aura.data.legal

import com.chloemlla.aura.data.model.ContentSource

internal fun generatedWallpaperProviderDisclosure() = ProviderDisclosure(
    source = ContentSource.AI_GENERATED,
    displayName = "AI-generated",
    content = "User-generated AI wallpapers",
    status = ProviderStatus.GENERATED,
    termsUrl = "https://platform.stability.ai/legal",
    licenseSummary = "Generator/provider terms plus user prompt context",
    attribution = "Preserve generator/provider, creation time, and prompt/style metadata where available.",
    cachePolicy = "Store only user-generated outputs and metadata needed for restore/export/apply flows.",
    userActions = "Generate, favorite, export, and apply under user control.",
    storeDisclosure = "Optional AI generation through user-configured provider access.",
)
