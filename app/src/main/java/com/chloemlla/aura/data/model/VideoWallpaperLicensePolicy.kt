package com.chloemlla.aura.data.model

import com.chloemlla.aura.data.legal.ProviderAction
import com.chloemlla.aura.data.legal.ProviderLifecycle
import com.chloemlla.aura.data.legal.isProviderAvailableInCurrentArtifact
import com.chloemlla.aura.data.legal.isProviderActionPermitted
import com.chloemlla.aura.data.legal.providerCapability
import java.util.Locale

enum class VideoWallpaperAction {
    APPLY,
    DOWNLOAD,
    SHARE,
}

enum class VideoWallpaperActionDecision {
    ALLOWED,
    CONFIRMATION_REQUIRED,
    DISABLED,
}

data class VideoWallpaperActionCapability(
    val decision: VideoWallpaperActionDecision,
    val reason: String = "",
)

data class VideoProviderPolicyLinks(
    val termsUrl: String = "",
    val reportUrl: String = "",
    val takedownUrl: String = "",
)

data class VideoWallpaperLicenseCapabilities(
    val normalizedLicense: String,
    val attributionRequired: Boolean,
    val sourceLinkRequired: Boolean,
    val uploaderRequired: Boolean,
    val providerPolicyLinks: VideoProviderPolicyLinks,
    val actions: Map<VideoWallpaperAction, VideoWallpaperActionCapability>,
) {
    fun capability(action: VideoWallpaperAction): VideoWallpaperActionCapability =
        actions.getValue(action)

    fun canUse(action: VideoWallpaperAction): Boolean =
        capability(action).decision != VideoWallpaperActionDecision.DISABLED

    fun requiresConfirmation(action: VideoWallpaperAction): Boolean =
        capability(action).decision == VideoWallpaperActionDecision.CONFIRMATION_REQUIRED
}

fun VideoWallpaperItem.videoWallpaperLicenseCapabilities(): VideoWallpaperLicenseCapabilities {
    val normalizedLicense = normalizeVideoWallpaperLicense(contentSource, license)
    val attributionRequired = contentSource in REMOTE_VIDEO_SOURCES
    val sourceLinkRequired = contentSource in REMOTE_VIDEO_SOURCES
    val uploaderRequired = contentSource in REMOTE_VIDEO_SOURCES
    val missingSourceLink = sourceLinkRequired && sourcePageUrl.isBlank()
    val missingUploader = uploaderRequired && uploaderName.isBlank()

    val actions = mutableAllowedVideoActions()
    enforceVideoProviderActionCeiling(contentSource, actions)

    if (missingSourceLink || missingUploader) {
        val missing = buildList {
            if (missingSourceLink) add("source link")
            if (missingUploader) add("uploader")
        }.joinToString(" and ")
        disableVideoAction(actions, VideoWallpaperAction.SHARE, "Share is disabled until the video has $missing metadata.")
    }

    when (contentSource) {
        ContentSource.PEXELS -> {
            requireVideoConfirmation(actions, VideoWallpaperAction.DOWNLOAD, "Confirm Pexels license terms before downloading this video.")
        }
        ContentSource.PIXABAY -> {
            requireVideoConfirmation(actions, VideoWallpaperAction.DOWNLOAD, "Confirm Pixabay license terms before downloading this video.")
        }
        ContentSource.YOUTUBE -> {
            requireVideoConfirmation(actions, VideoWallpaperAction.APPLY, "Confirm YouTube source terms before applying this video wallpaper.")
            requireVideoConfirmation(actions, VideoWallpaperAction.DOWNLOAD, "Confirm YouTube source terms before downloading this video.")
            disableVideoAction(actions, VideoWallpaperAction.SHARE, "YouTube videos cannot be shared outside Aura.")
        }
        ContentSource.REDDIT -> {
            requireVideoConfirmation(actions, VideoWallpaperAction.APPLY, "Confirm Reddit source availability before applying this video wallpaper.")
            requireVideoConfirmation(actions, VideoWallpaperAction.DOWNLOAD, "Confirm Reddit source terms before downloading this video.")
        }
        ContentSource.LOCAL -> Unit
        else -> Unit
    }

    return VideoWallpaperLicenseCapabilities(
        normalizedLicense = normalizedLicense,
        attributionRequired = attributionRequired,
        sourceLinkRequired = sourceLinkRequired,
        uploaderRequired = uploaderRequired,
        providerPolicyLinks = videoProviderPolicyLinks(contentSource),
        actions = actions,
    )
}

fun VideoWallpaperItem.canUseVideoAction(action: VideoWallpaperAction): Boolean =
    videoWallpaperLicenseCapabilities().canUse(action)

fun VideoWallpaperItem.requiresVideoActionConfirmation(action: VideoWallpaperAction): Boolean =
    videoWallpaperLicenseCapabilities().requiresConfirmation(action)

fun VideoWallpaperItem.videoActionMessage(action: VideoWallpaperAction): String =
    videoWallpaperLicenseCapabilities().capability(action).reason

fun normalizeVideoWallpaperLicense(source: ContentSource, license: String): String {
    val raw = license.trim()
    if (source == ContentSource.YOUTUBE) return "YouTube"
    if (source == ContentSource.REDDIT && raw.isBlank()) return "Reddit"
    if (source == ContentSource.LOCAL && raw.isBlank()) return "Local User Content"
    if (raw.isBlank()) return VIDEO_UNKNOWN_LICENSE

    val key = raw.uppercase(Locale.ROOT)
    return when {
        key.contains("PEXELS") -> "Pexels License"
        key.contains("PIXABAY") -> "Pixabay License"
        else -> raw.take(80)
    }
}

fun videoProviderPolicyLinks(source: ContentSource): VideoProviderPolicyLinks = when (source) {
    ContentSource.PEXELS -> VideoProviderPolicyLinks(
        termsUrl = "https://www.pexels.com/terms-of-service/",
        reportUrl = "https://www.pexels.com/report/",
        takedownUrl = "https://www.pexels.com/report/",
    )
    ContentSource.PIXABAY -> VideoProviderPolicyLinks(
        termsUrl = "https://pixabay.com/service/terms/",
        reportUrl = "https://pixabay.com/content-reports/",
        takedownUrl = "https://pixabay.com/content-reports/",
    )
    ContentSource.YOUTUBE -> VideoProviderPolicyLinks(
        termsUrl = "https://www.youtube.com/t/terms",
        reportUrl = "https://support.google.com/youtube/answer/2802027",
        takedownUrl = "https://support.google.com/youtube/answer/2807622",
    )
    ContentSource.REDDIT -> VideoProviderPolicyLinks(
        termsUrl = "https://redditinc.com/policies/data-api-terms",
        reportUrl = "https://www.reddit.com/report",
        takedownUrl = "https://support.reddithelp.com/hc/en-us/articles/360043076292-Copyright-overview",
    )
    else -> VideoProviderPolicyLinks()
}

private const val VIDEO_UNKNOWN_LICENSE = "Unknown"

private val REMOTE_VIDEO_SOURCES = setOf(
    ContentSource.PEXELS,
    ContentSource.PIXABAY,
    ContentSource.YOUTUBE,
    ContentSource.REDDIT,
)

private fun mutableAllowedVideoActions(): MutableMap<VideoWallpaperAction, VideoWallpaperActionCapability> =
    VideoWallpaperAction.entries.associateWith {
        VideoWallpaperActionCapability(VideoWallpaperActionDecision.ALLOWED)
    }.toMutableMap()

private fun enforceVideoProviderActionCeiling(
    source: ContentSource,
    actions: MutableMap<VideoWallpaperAction, VideoWallpaperActionCapability>,
) {
    val provider = providerCapability(source)
    VIDEO_PROVIDER_ACTIONS.forEach { (videoAction, providerAction) ->
        if (!isProviderActionPermitted(source, providerAction)) {
            val reason = if (provider.lifecycle == ProviderLifecycle.LEGACY) {
                "Legacy source items are kept for attribution only; ${videoAction.name.lowercase()} is disabled."
            } else if (!isProviderAvailableInCurrentArtifact(source)) {
                "This provider is unavailable in this Aura build."
            } else {
                "${provider.source.name.lowercase().replaceFirstChar { it.titlecase() }} does not permit ${videoAction.name.lowercase()}."
            }
            disableVideoAction(actions, videoAction, reason)
        }
    }
}

private val VIDEO_PROVIDER_ACTIONS = mapOf(
    VideoWallpaperAction.APPLY to ProviderAction.APPLY,
    VideoWallpaperAction.DOWNLOAD to ProviderAction.DOWNLOAD,
    VideoWallpaperAction.SHARE to ProviderAction.SHARE,
)

private fun requireVideoConfirmation(
    actions: MutableMap<VideoWallpaperAction, VideoWallpaperActionCapability>,
    action: VideoWallpaperAction,
    reason: String,
) {
    if (actions[action]?.decision == VideoWallpaperActionDecision.DISABLED) return
    actions[action] = VideoWallpaperActionCapability(VideoWallpaperActionDecision.CONFIRMATION_REQUIRED, reason)
}

private fun disableVideoAction(
    actions: MutableMap<VideoWallpaperAction, VideoWallpaperActionCapability>,
    action: VideoWallpaperAction,
    reason: String,
) {
    actions[action] = VideoWallpaperActionCapability(VideoWallpaperActionDecision.DISABLED, reason)
}
