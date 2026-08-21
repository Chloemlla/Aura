package com.freevibe.data.legal

import com.freevibe.data.model.ContentSource

/**
 * Where a source sits in its life: this is what decides whether Aura is allowed
 * to make a network call for it at all, not just how the UI labels it.
 */
enum class ProviderLifecycle {
    /** Fetched today. Must declare at least one network endpoint. */
    ACTIVE,

    /**
     * No active feed. Saved records keep their attribution and stay visible, but
     * no new fetch may originate from this source.
     */
    LEGACY,

    /** User-supplied device media. No remote catalog at all. */
    LOCAL,

    /** Aura's own Firebase-backed community content. */
    COMMUNITY,

    /** Produced on request from a user-configured generator. */
    GENERATED,
}

/** Build flavors a source can operate in. */
enum class ProviderBuild { FULL, FOSS }

/** Distribution channels a source is allowed to ship enabled on. */
enum class ProviderChannel { GITHUB, PLAY }

/** What the user must supply before the source can be used. */
enum class ProviderConfiguration {
    /** Works out of the box. */
    NONE,

    /** Ships with a usable default; a user key raises limits or unlocks extras. */
    OPTIONAL_KEY,

    /** Unusable until the user supplies their own credential. */
    REQUIRED_KEY,
}

/** Runtime permission the source needs before it can do anything. */
enum class ProviderPermission { NONE, APPROXIMATE_LOCATION, MEDIA_ACCESS }

/** Whether per-source health/backoff tracking is meaningful. */
enum class ProviderHealth {
    /** Makes network calls; `SourceMetrics` and backoff apply. */
    NETWORKED,

    /** No network calls; nothing to measure. */
    OFFLINE,
}

/**
 * The single source of truth for what a [ContentSource] is and is allowed to do.
 *
 * Before this existed, the same facts were spelled out independently in the
 * disclosure list, the runtime-control list, the network-endpoint manifest, the
 * Settings toggles, and the repositories — so a source could read "dormant" in
 * one place while a repository still fetched it, and a build/channel could ship
 * a provider its own policy forbade. `ProviderCapabilityContractTest` fails the
 * build when any of those disagree with this registry.
 */
data class ProviderCapability(
    val source: ContentSource,
    val lifecycle: ProviderLifecycle,
    /** Builds this source can operate in. FOSS drops the Firebase-backed ones. */
    val builds: Set<ProviderBuild>,
    /** Channels this source may ship enabled on. */
    val channels: Set<ProviderChannel>,
    val configuration: ProviderConfiguration,
    val permission: ProviderPermission,
    val health: ProviderHealth,
    /** True when items must carry provider attribution wherever they are shown. */
    val requiresAttribution: Boolean,
    /** Whether the source is on for a fresh install. */
    val enabledByDefault: Boolean,
    /**
     * Preference key that switches the source off, matching the `killSwitch`
     * field of its endpoints. Null when there is nothing to switch off.
     */
    val killSwitchKey: String?,
    /** Ids in `docs/security/network-endpoints.json` this source owns. */
    val endpointIds: Set<String>,
) {
    /** True when the source may originate a new network request. */
    val canFetch: Boolean
        get() = lifecycle != ProviderLifecycle.LEGACY && health == ProviderHealth.NETWORKED

    fun availableIn(build: ProviderBuild): Boolean = build in builds

    fun availableOn(channel: ProviderChannel): Boolean = channel in channels
}

private val ALL_BUILDS = setOf(ProviderBuild.FULL, ProviderBuild.FOSS)
private val FULL_ONLY = setOf(ProviderBuild.FULL)
private val ALL_CHANNELS = setOf(ProviderChannel.GITHUB, ProviderChannel.PLAY)
private val GITHUB_ONLY = setOf(ProviderChannel.GITHUB)

/**
 * Shorthand for the many sources that are dormant: no feed, no endpoints, no
 * switch, present only so saved records keep their provenance.
 */
private fun legacy(source: ContentSource) = ProviderCapability(
    source = source,
    lifecycle = ProviderLifecycle.LEGACY,
    builds = ALL_BUILDS,
    channels = ALL_CHANNELS,
    configuration = ProviderConfiguration.NONE,
    permission = ProviderPermission.NONE,
    health = ProviderHealth.OFFLINE,
    requiresAttribution = true,
    enabledByDefault = false,
    killSwitchKey = null,
    endpointIds = emptySet(),
)

val providerCapabilities: List<ProviderCapability> = listOf(
    ProviderCapability(
        source = ContentSource.WALLHAVEN,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.OPTIONAL_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "wallhaven_provider_enabled",
        endpointIds = setOf("wallhaven-api"),
    ),
    legacy(ContentSource.PICSUM),
    ProviderCapability(
        source = ContentSource.BING,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "bing_provider_enabled",
        endpointIds = setOf("bing-daily"),
    ),
    ProviderCapability(
        source = ContentSource.WIKIMEDIA,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = null,
        endpointIds = setOf("wikimedia-potd"),
    ),
    legacy(ContentSource.INTERNET_ARCHIVE),
    ProviderCapability(
        source = ContentSource.REDDIT,
        lifecycle = ProviderLifecycle.LEGACY,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = "reddit_provider_enabled",
        endpointIds = setOf("reddit-rss"),
    ),
    ProviderCapability(
        source = ContentSource.NASA,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = null,
        endpointIds = setOf("nasa-apod"),
    ),
    ProviderCapability(
        source = ContentSource.FREESOUND,
        lifecycle = ProviderLifecycle.LEGACY,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.OPTIONAL_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = null,
        endpointIds = setOf("freesound-v2", "openverse-audio"),
    ),
    legacy(ContentSource.JAMENDO),
    ProviderCapability(
        source = ContentSource.AUDIUS,
        lifecycle = ProviderLifecycle.LEGACY,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = null,
        endpointIds = setOf("audius-api"),
    ),
    ProviderCapability(
        source = ContentSource.CCMIXTER,
        lifecycle = ProviderLifecycle.LEGACY,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = null,
        endpointIds = setOf("ccmixter-api"),
    ),
    ProviderCapability(
        source = ContentSource.LOCAL,
        lifecycle = ProviderLifecycle.LOCAL,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.MEDIA_ACCESS,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = false,
        enabledByDefault = true,
        killSwitchKey = null,
        endpointIds = emptySet(),
    ),
    ProviderCapability(
        source = ContentSource.YOUTUBE,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        // Aura's own YouTube risk profile keeps extraction off Play until the
        // owner records approval evidence; GitHub/Obtainium keeps the capability.
        channels = GITHUB_ONLY,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "youtube_provider_enabled",
        endpointIds = setOf("youtube-newpipe", "youtube-pot-provider"),
    ),
    ProviderCapability(
        source = ContentSource.PEXELS,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.OPTIONAL_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "pexels_provider_enabled",
        endpointIds = setOf("pexels-api"),
    ),
    ProviderCapability(
        source = ContentSource.PIXABAY,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.OPTIONAL_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "pixabay_provider_enabled",
        endpointIds = setOf("pixabay-api"),
    ),
    legacy(ContentSource.KLIPY),
    ProviderCapability(
        source = ContentSource.SOUNDCLOUD,
        lifecycle = ProviderLifecycle.LEGACY,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.REQUIRED_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = null,
        endpointIds = setOf("soundcloud-api"),
    ),
    ProviderCapability(
        source = ContentSource.COMMUNITY,
        lifecycle = ProviderLifecycle.COMMUNITY,
        // Firebase is compiled out of the FOSS flavor.
        builds = FULL_ONLY,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = "community_provider_enabled",
        endpointIds = setOf("firebase-community", "aura-collection-links"),
    ),
    ProviderCapability(
        source = ContentSource.BUNDLED,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.OFFLINE,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = null,
        endpointIds = emptySet(),
    ),
    ProviderCapability(
        source = ContentSource.AI_GENERATED,
        lifecycle = ProviderLifecycle.GENERATED,
        builds = FULL_ONLY,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.REQUIRED_KEY,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = "generated_content_provider_enabled",
        endpointIds = setOf("generated-wallpaper-api"),
    ),
    ProviderCapability(
        source = ContentSource.OPEN_METEO,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.APPROXIMATE_LOCATION,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = false,
        killSwitchKey = "weather_effects_enabled",
        endpointIds = setOf("open-meteo-api"),
    ),
    ProviderCapability(
        source = ContentSource.LEMMY,
        lifecycle = ProviderLifecycle.ACTIVE,
        builds = ALL_BUILDS,
        channels = ALL_CHANNELS,
        configuration = ProviderConfiguration.NONE,
        permission = ProviderPermission.NONE,
        health = ProviderHealth.NETWORKED,
        requiresAttribution = true,
        enabledByDefault = true,
        killSwitchKey = null,
        endpointIds = setOf("lemmy-wallpapers"),
    ),
)

val providerCapabilitiesBySource: Map<ContentSource, ProviderCapability> =
    providerCapabilities.associateBy { it.source }

/** The registry entry for [source]; every enum value has exactly one. */
fun providerCapability(source: ContentSource): ProviderCapability =
    providerCapabilitiesBySource.getValue(source)

/**
 * Disclosure status implied by a lifecycle. Keeps `ProviderDisclosure.status`
 * from drifting away from what the runtime actually allows.
 */
fun ProviderLifecycle.disclosureStatus(): ProviderStatus = when (this) {
    ProviderLifecycle.ACTIVE -> ProviderStatus.ACTIVE
    ProviderLifecycle.LEGACY -> ProviderStatus.LEGACY
    ProviderLifecycle.LOCAL -> ProviderStatus.LOCAL
    ProviderLifecycle.COMMUNITY -> ProviderStatus.COMMUNITY
    ProviderLifecycle.GENERATED -> ProviderStatus.GENERATED
}
