package com.chloemlla.aura.data.model

import java.util.Locale

enum class WallpaperAction {
    APPLY,
    DOWNLOAD,
    SHARE,
    EDIT,
}

enum class WallpaperActionDecision {
    ALLOWED,
    CONFIRMATION_REQUIRED,
    DISABLED,
}

/** Why an action is gated. The UI maps these to localized copy; the policy layer has no Context. */
enum class WallpaperActionReason {
    SOURCE_UNAVAILABLE,
    UNVERIFIED_LICENSE,
    SHARE_MISSING_SOURCE_LINK,
    SHARE_MISSING_UPLOADER,
    SHARE_MISSING_SOURCE_LINK_AND_UPLOADER,
    BING_TERMS,
    BING_SHARE_FORBIDDEN,
    REDDIT_TERMS,
    REDDIT_EDIT_FORBIDDEN,
    COMMUNITY_UPLOAD_RIGHTS,
    AI_GENERATOR_TERMS,
    NO_DERIVATIVES,
    NON_COMMERCIAL,
}

data class WallpaperActionCapability(
    val decision: WallpaperActionDecision,
    val reason: WallpaperActionReason? = null,
)

data class WallpaperLicenseCapabilities(
    val normalizedLicense: String,
    val attributionRequired: Boolean,
    val sourceLinkRequired: Boolean,
    val uploaderRequired: Boolean,
    val actions: Map<WallpaperAction, WallpaperActionCapability>,
) {
    fun capability(action: WallpaperAction): WallpaperActionCapability =
        actions.getValue(action)

    fun canUse(action: WallpaperAction): Boolean =
        capability(action).decision != WallpaperActionDecision.DISABLED

    fun requiresConfirmation(action: WallpaperAction): Boolean =
        capability(action).decision == WallpaperActionDecision.CONFIRMATION_REQUIRED
}

fun Wallpaper.wallpaperLicenseCapabilities(): WallpaperLicenseCapabilities {
    if (isSourceUnavailable()) {
        return WallpaperLicenseCapabilities(
            normalizedLicense = normalizeWallpaperLicense(source, license),
            attributionRequired = false,
            sourceLinkRequired = false,
            uploaderRequired = false,
            actions = disabledWallpaperActions(WallpaperActionReason.SOURCE_UNAVAILABLE),
        )
    }

    val normalizedLicense = normalizeWallpaperLicense(source, license)
    val licenseKey = normalizedLicense.uppercase(Locale.ROOT)
    val isCreativeCommons = licenseKey.startsWith("CC ") || licenseKey.startsWith("CC-")
    val isCc0Compatible = licenseKey == "CC0" || licenseKey == "CC0 1.0" || licenseKey == "PUBLIC DOMAIN"
    val isNoDerivatives = licenseKey.contains("-ND")
    val isNonCommercial = licenseKey.contains("-NC")
    val attributionRequired = isCreativeCommons && !isCc0Compatible
    val sourceLinkRequired = source in REMOTE_WALLPAPER_SOURCES || attributionRequired
    val uploaderRequired = source in REMOTE_WALLPAPER_SOURCES || attributionRequired
    val missingLicense = normalizedLicense == WALLPAPER_UNKNOWN_LICENSE
    val hasSourceLink = sourcePageUrl.isNotBlank() || (source == ContentSource.COMMUNITY && fullUrl.isNotBlank())
    val missingSourceLink = sourceLinkRequired && !hasSourceLink
    val missingUploader = uploaderRequired && uploaderName.isBlank()

    val actions = mutableAllowedWallpaperActions()

    if (missingLicense && source in REMOTE_WALLPAPER_SOURCES) {
        WallpaperAction.entries.forEach { action ->
            requireWallpaperConfirmation(actions, action, WallpaperActionReason.UNVERIFIED_LICENSE)
        }
    }

    if (missingSourceLink || missingUploader) {
        val reason = when {
            missingSourceLink && missingUploader -> WallpaperActionReason.SHARE_MISSING_SOURCE_LINK_AND_UPLOADER
            missingSourceLink -> WallpaperActionReason.SHARE_MISSING_SOURCE_LINK
            else -> WallpaperActionReason.SHARE_MISSING_UPLOADER
        }
        disableWallpaperAction(actions, WallpaperAction.SHARE, reason)
    }

    when (source) {
        ContentSource.BING -> {
            requireWallpaperConfirmation(actions, WallpaperAction.DOWNLOAD, WallpaperActionReason.BING_TERMS)
            requireWallpaperConfirmation(actions, WallpaperAction.EDIT, WallpaperActionReason.BING_TERMS)
            disableWallpaperAction(actions, WallpaperAction.SHARE, WallpaperActionReason.BING_SHARE_FORBIDDEN)
        }
        ContentSource.REDDIT -> {
            requireWallpaperConfirmation(actions, WallpaperAction.APPLY, WallpaperActionReason.REDDIT_TERMS)
            requireWallpaperConfirmation(actions, WallpaperAction.DOWNLOAD, WallpaperActionReason.REDDIT_TERMS)
            disableWallpaperAction(actions, WallpaperAction.EDIT, WallpaperActionReason.REDDIT_EDIT_FORBIDDEN)
        }
        ContentSource.COMMUNITY -> {
            if (normalizedLicense == "User Upload") {
                requireWallpaperConfirmation(actions, WallpaperAction.APPLY, WallpaperActionReason.COMMUNITY_UPLOAD_RIGHTS)
                requireWallpaperConfirmation(actions, WallpaperAction.DOWNLOAD, WallpaperActionReason.COMMUNITY_UPLOAD_RIGHTS)
                requireWallpaperConfirmation(actions, WallpaperAction.EDIT, WallpaperActionReason.COMMUNITY_UPLOAD_RIGHTS)
            }
        }
        ContentSource.AI_GENERATED -> {
            requireWallpaperConfirmation(actions, WallpaperAction.SHARE, WallpaperActionReason.AI_GENERATOR_TERMS)
        }
        else -> Unit
    }

    if (isNoDerivatives) {
        disableWallpaperAction(actions, WallpaperAction.EDIT, WallpaperActionReason.NO_DERIVATIVES)
    }
    if (isNonCommercial) {
        requireWallpaperConfirmation(actions, WallpaperAction.APPLY, WallpaperActionReason.NON_COMMERCIAL)
        requireWallpaperConfirmation(actions, WallpaperAction.DOWNLOAD, WallpaperActionReason.NON_COMMERCIAL)
        requireWallpaperConfirmation(actions, WallpaperAction.EDIT, WallpaperActionReason.NON_COMMERCIAL)
    }

    return WallpaperLicenseCapabilities(
        normalizedLicense = normalizedLicense,
        attributionRequired = attributionRequired,
        sourceLinkRequired = sourceLinkRequired,
        uploaderRequired = uploaderRequired,
        actions = actions,
    )
}

fun normalizeWallpaperLicense(source: ContentSource, license: String): String {
    val raw = license.trim()
    if (source == ContentSource.BING && raw.isBlank()) return "Bing Daily"
    if (source == ContentSource.REDDIT && raw.isBlank()) return "Reddit"
    if (source == ContentSource.COMMUNITY && raw.isBlank()) return "User Upload"
    if (source == ContentSource.AI_GENERATED && raw.isBlank()) return "AI Generated"
    if (source == ContentSource.LOCAL && raw.isBlank()) return "Local User Content"
    if (raw.isBlank()) return WALLPAPER_UNKNOWN_LICENSE

    val key = raw.uppercase(Locale.ROOT)
    return when {
        key.contains("CC0") || key.contains("CREATIVE COMMONS 0") -> "CC0"
        key.contains("PUBLIC DOMAIN") || key == "PDM" -> "Public Domain"
        key.contains("BY-NC-ND") || key.contains("ATTRIBUTION-NONCOMMERCIAL-NODERIVS") -> "CC BY-NC-ND"
        key.contains("BY-NC-SA") || key.contains("ATTRIBUTION-NONCOMMERCIAL-SHAREALIKE") -> "CC BY-NC-SA"
        key.contains("BY-NC") || key.contains("ATTRIBUTION-NONCOMMERCIAL") -> "CC BY-NC"
        key.contains("BY-ND") || key.contains("ATTRIBUTION-NODERIVS") -> "CC BY-ND"
        key.contains("BY-SA") || key.contains("ATTRIBUTION-SHAREALIKE") -> "CC BY-SA"
        key == "BY" || key.contains("CC BY") || key.contains("ATTRIBUTION") -> "CC BY"
        key.contains("PEXELS") -> "Pexels License"
        key.contains("PIXABAY") -> "Pixabay License"
        key.contains("USER UPLOAD") -> "User Upload"
        else -> raw.take(80)
    }
}

private const val WALLPAPER_UNKNOWN_LICENSE = "Unknown"

private val REMOTE_WALLPAPER_SOURCES = setOf(
    ContentSource.WALLHAVEN,
    ContentSource.PEXELS,
    ContentSource.PIXABAY,
    ContentSource.BING,
    ContentSource.REDDIT,
    ContentSource.NASA,
    ContentSource.WIKIMEDIA,
    ContentSource.PICSUM,
    ContentSource.COMMUNITY,
)

private fun mutableAllowedWallpaperActions(): MutableMap<WallpaperAction, WallpaperActionCapability> =
    WallpaperAction.entries.associateWith {
        WallpaperActionCapability(WallpaperActionDecision.ALLOWED)
    }.toMutableMap()

private fun disabledWallpaperActions(reason: WallpaperActionReason): Map<WallpaperAction, WallpaperActionCapability> =
    WallpaperAction.entries.associateWith {
        WallpaperActionCapability(WallpaperActionDecision.DISABLED, reason)
    }

private fun requireWallpaperConfirmation(
    actions: MutableMap<WallpaperAction, WallpaperActionCapability>,
    action: WallpaperAction,
    reason: WallpaperActionReason,
) {
    if (actions[action]?.decision == WallpaperActionDecision.DISABLED) return
    actions[action] = WallpaperActionCapability(WallpaperActionDecision.CONFIRMATION_REQUIRED, reason)
}

private fun disableWallpaperAction(
    actions: MutableMap<WallpaperAction, WallpaperActionCapability>,
    action: WallpaperAction,
    reason: WallpaperActionReason,
) {
    actions[action] = WallpaperActionCapability(WallpaperActionDecision.DISABLED, reason)
}
