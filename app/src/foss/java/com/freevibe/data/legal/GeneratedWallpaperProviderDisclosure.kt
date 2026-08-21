package com.freevibe.data.legal

import com.freevibe.data.model.ContentSource

internal fun generatedWallpaperProviderDisclosure() = ProviderDisclosure(
    source = ContentSource.AI_GENERATED,
    displayName = "AI-generated",
    content = "AI-labeled community wallpapers",
    status = ProviderStatus.GENERATED,
    termsUrl = "https://github.com/SysAdminDoc/Aura/blob/main/docs/privacy/privacy-policy.md",
    licenseSummary = "Uploader-provided source and license terms",
    attribution = "Preserve uploader, source, license, and AI labels where available.",
    cachePolicy = "Cache only community records needed for browsing and user saves.",
    userActions = "Browse, favorite, export, and apply under user control.",
    storeDisclosure = "AI-labeled community content; external generation is not included in this build.",
)
