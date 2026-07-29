package com.freevibe.data.model

import com.freevibe.data.legal.ProviderCapability
import com.freevibe.data.legal.providerCapability
import java.util.Locale
import java.util.concurrent.TimeUnit

const val PROVIDER_CACHE_TTL_DEFAULT_MS: Long = 6 * 60 * 60 * 1000L
const val PROVIDER_CACHE_TTL_REDDIT_MS: Long = 2 * 60 * 60 * 1000L
const val PROVIDER_CACHE_TTL_24H_MS: Long = 24 * 60 * 60 * 1000L
const val PROVIDER_CACHE_TTL_PIXABAY_MS: Long = PROVIDER_CACHE_TTL_24H_MS
const val PROVIDER_CACHE_TTL_BING_MS: Long = 4 * 60 * 60 * 1000L

enum class RetryAfterHandling {
    NONE,
    DELTA_SECONDS,
}

data class ProviderNetworkPolicy(
    val source: ContentSource,
    val sourceKey: String = source.name.lowercase(Locale.ROOT),
    val sourceAliases: Set<String> = emptySet(),
    val requestCacheTtlMs: Long? = null,
    val mediaUrlTtlMs: Long? = null,
    val retryAfterHandling: RetryAfterHandling = RetryAfterHandling.NONE,
    val hostSuffixes: Set<String> = emptySet(),
    val maxAutomaticPrefetch: Int = 0,
    val maxBatchDownloadPerUserAction: Int = Int.MAX_VALUE,
    val timeoutPolicy: String,
    val backoffPolicy: String,
    val cacheFallbackPolicy: String,
    val disabledBehavior: String,
    val quotaSummary: String,
) {
    init {
        require(sourceKey.isNotBlank()) { "${source.name} sourceKey must not be blank" }
        require(maxAutomaticPrefetch >= 0) { "${source.name} maxAutomaticPrefetch must be non-negative" }
        require(maxBatchDownloadPerUserAction >= 0) {
            "${source.name} maxBatchDownloadPerUserAction must be non-negative"
        }
        require(timeoutPolicy.isNotBlank()) { "${source.name} timeoutPolicy must not be blank" }
        require(backoffPolicy.isNotBlank()) { "${source.name} backoffPolicy must not be blank" }
        require(cacheFallbackPolicy.isNotBlank()) { "${source.name} cacheFallbackPolicy must not be blank" }
        require(disabledBehavior.isNotBlank()) { "${source.name} disabledBehavior must not be blank" }
    }

    fun allowsAutomaticPrefetch(count: Int): Boolean =
        count.coerceAtLeast(0) <= maxAutomaticPrefetch

    fun allowsBatchDownload(count: Int): Boolean =
        count.coerceAtLeast(0) <= maxBatchDownloadPerUserAction

    /** Registry entry backing this source. See `ProviderCapability`. */
    val capability: ProviderCapability
        get() = providerCapability(source)

    /**
     * The registry's view of this source, rendered for diagnostics so support
     * bundles report the same lifecycle/build/channel facts the release gates
     * enforce rather than a second, hand-maintained description.
     */
    val capabilitySummary: String
        get() = capability.let { entry ->
            listOf(
                "lifecycle ${entry.lifecycle.name.lowercase(Locale.ROOT)}",
                "builds ${entry.builds.map { it.name.lowercase(Locale.ROOT) }.sorted().joinToString("+")}",
                "channels ${entry.channels.map { it.name.lowercase(Locale.ROOT) }.sorted().joinToString("+")}",
                "config ${entry.configuration.name.lowercase(Locale.ROOT)}",
                "permission ${entry.permission.name.lowercase(Locale.ROOT)}",
                "default ${if (entry.enabledByDefault) "on" else "off"}",
                "kill switch ${entry.killSwitchKey ?: "none"}",
            ).joinToString(" / ")
        }

    val diagnosticSummary: String
        get() = listOf(
            capabilitySummary,
            "timeout $timeoutPolicy",
            "backoff $backoffPolicy",
            "fallback $cacheFallbackPolicy",
            "disabled $disabledBehavior",
            "request cache ${formatPolicyDuration(requestCacheTtlMs)}",
            "media URL ${formatPolicyDuration(mediaUrlTtlMs)}",
            "retry ${retryAfterHandling.label()}",
            "prefetch ${limitLabel(maxAutomaticPrefetch)}",
            "batch ${limitLabel(maxBatchDownloadPerUserAction)}",
        ).joinToString(" / ")
}

val providerNetworkPolicies = listOf(
    ProviderNetworkPolicy(
        source = ContentSource.WALLHAVEN,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_DEFAULT_MS,
        hostSuffixes = setOf("wallhaven.cc"),
        maxAutomaticPrefetch = 30,
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "stale wallpaper cache where available",
        disabledBehavior = "provider toggle blocks new requests",
        quotaSummary = "Aura default metadata cache; no Retry-After contract is encoded.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.PICSUM,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_24H_MS,
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "inactive source",
        backoffPolicy = "none",
        cacheFallbackPolicy = "saved legacy records only",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Legacy placeholder source; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.BING,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_BING_MS,
        hostSuffixes = setOf("bing.com"),
        maxAutomaticPrefetch = 1,
        maxBatchDownloadPerUserAction = 1,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "reviewed fallback host retry on transport failures",
        cacheFallbackPolicy = "cached daily metadata",
        disabledBehavior = "provider toggle blocks new requests",
        quotaSummary = "Daily-image metadata is cached for rotation continuity.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.WIKIMEDIA,
        sourceAliases = setOf("wiki_potd"),
        requestCacheTtlMs = PROVIDER_CACHE_TTL_DEFAULT_MS,
        hostSuffixes = setOf("wikimedia.org", "wikipedia.org"),
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "daily enhancement skipped; saved items remain",
        disabledBehavior = "Discover omits the daily featured image; saved items remain",
        quotaSummary = "One featured-image request per Discover refresh under the shared secondary-source budget.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.INTERNET_ARCHIVE,
        sourceAliases = setOf("archive"),
        hostSuffixes = setOf("archive.org"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "inactive source",
        backoffPolicy = "none",
        cacheFallbackPolicy = "saved legacy records only",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Removed legacy audio source; retained records are user-saved only.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.REDDIT,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_REDDIT_MS,
        hostSuffixes = setOf("reddit.com"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "one cursor-paged combined RSS request at a time with a 60-second process cooldown",
        cacheFallbackPolicy = "two-hour Room cache with stale fallback",
        disabledBehavior = "provider toggle blocks tab access",
        quotaSummary = "Public Atom/RSS only; anonymous after-cursor pagination with no OAuth client ID or credential impersonation.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.NASA,
        sourceAliases = setOf("nasa_apod"),
        requestCacheTtlMs = PROVIDER_CACHE_TTL_DEFAULT_MS,
        hostSuffixes = setOf("nasa.gov"),
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "daily enhancement skipped; saved items remain",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Legacy restored records only; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.FREESOUND,
        sourceAliases = setOf("openverse"),
        retryAfterHandling = RetryAfterHandling.DELTA_SECONDS,
        hostSuffixes = setOf("freesound.org", "openverse.org"),
        maxAutomaticPrefetch = 10,
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "Retry-After delta seconds with bounded retries",
        cacheFallbackPolicy = "bundled and saved sounds remain available",
        disabledBehavior = "source can be omitted without hiding local sounds",
        quotaSummary = "Freesound/Openverse sound requests honor delta-second Retry-After.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.JAMENDO,
        hostSuffixes = setOf("jamendo.com"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "inactive source",
        backoffPolicy = "none",
        cacheFallbackPolicy = "saved legacy records only",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Legacy sound source; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.AUDIUS,
        hostSuffixes = setOf("audius.co", "audius.org"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "saved sounds remain available",
        disabledBehavior = "source can be omitted without hiding local sounds",
        quotaSummary = "Legacy sound source; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.CCMIXTER,
        hostSuffixes = setOf("ccmixter.org"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "saved sounds remain available",
        disabledBehavior = "source can be omitted without hiding local sounds",
        quotaSummary = "Legacy sound source; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.LOCAL,
        maxBatchDownloadPerUserAction = Int.MAX_VALUE,
        timeoutPolicy = "not applicable",
        backoffPolicy = "none",
        cacheFallbackPolicy = "local files are the source of truth",
        disabledBehavior = "never disabled by provider health",
        quotaSummary = "Local user-selected files; no provider quota applies.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.YOUTUBE,
        hostSuffixes = setOf("youtube.com", "youtu.be", "googlevideo.com"),
        maxAutomaticPrefetch = 0,
        maxBatchDownloadPerUserAction = 1,
        timeoutPolicy = "extractor call timeout",
        backoffPolicy = "explicit user retry after extraction failure",
        cacheFallbackPolicy = "short in-memory stream cache; saved sounds remain",
        disabledBehavior = "legal-mode or provider toggle blocks active use",
        quotaSummary = "Explicit user actions only; no automatic prefetch or batch downloading.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.PEXELS,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_DEFAULT_MS,
        hostSuffixes = setOf("pexels.com"),
        maxAutomaticPrefetch = 30,
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "discover enhancement skipped; cached wallpapers remain",
        disabledBehavior = "provider toggle blocks new requests",
        quotaSummary = "Enhancement-source metadata uses Aura default cache and bounded batches.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.PIXABAY,
        requestCacheTtlMs = PROVIDER_CACHE_TTL_PIXABAY_MS,
        mediaUrlTtlMs = PROVIDER_CACHE_TTL_PIXABAY_MS,
        retryAfterHandling = RetryAfterHandling.DELTA_SECONDS,
        hostSuffixes = setOf("pixabay.com"),
        maxAutomaticPrefetch = 30,
        maxBatchDownloadPerUserAction = 30,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "Retry-After delta seconds with bounded retries",
        cacheFallbackPolicy = "24-hour metadata/media cache where available",
        disabledBehavior = "provider toggle blocks new requests",
        quotaSummary = "Pixabay metadata and media URLs are held behind a 24-hour cache and Retry-After backoff.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.KLIPY,
        hostSuffixes = setOf("klipy.com"),
        maxBatchDownloadPerUserAction = 10,
        timeoutPolicy = "inactive source",
        backoffPolicy = "none",
        cacheFallbackPolicy = "saved legacy records only",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Legacy animated-media source; no active automatic fetching.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.SOUNDCLOUD,
        hostSuffixes = setOf("soundcloud.com"),
        maxBatchDownloadPerUserAction = 1,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "saved sounds remain available",
        disabledBehavior = "blank provider credentials return empty results",
        quotaSummary = "Dormant source; batch downloading remains disabled.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.COMMUNITY,
        maxAutomaticPrefetch = 50,
        maxBatchDownloadPerUserAction = Int.MAX_VALUE,
        timeoutPolicy = "Firebase SDK and callable timeouts",
        backoffPolicy = "callable quota and source degradation gates",
        cacheFallbackPolicy = "local community caches and saved items remain",
        disabledBehavior = "community toggle blocks requests and uploads",
        quotaSummary = "Firebase-backed community content uses app-side moderation and callable quotas.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.BUNDLED,
        maxBatchDownloadPerUserAction = Int.MAX_VALUE,
        timeoutPolicy = "not applicable",
        backoffPolicy = "none",
        cacheFallbackPolicy = "bundled assets ship with the app",
        disabledBehavior = "never disabled by provider health",
        quotaSummary = "Bundled assets ship with the APK; no network quota applies.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.AI_GENERATED,
        sourceKey = "ai_generated",
        maxBatchDownloadPerUserAction = Int.MAX_VALUE,
        timeoutPolicy = "generation request timeout",
        backoffPolicy = "no automatic retry to avoid duplicate charges",
        cacheFallbackPolicy = "local generated files remain available",
        disabledBehavior = "generated-content toggle blocks new requests",
        quotaSummary = "User-generated local outputs; provider calls are gated by generation flow limits.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.OPEN_METEO,
        sourceKey = "open_meteo",
        sourceAliases = setOf("open-meteo"),
        requestCacheTtlMs = TimeUnit.MINUTES.toMillis(30),
        hostSuffixes = setOf("open-meteo.com"),
        maxAutomaticPrefetch = 1,
        maxBatchDownloadPerUserAction = 0,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "WorkManager retry cadence and degraded-source cooldown",
        cacheFallbackPolicy = "weather overlay degrades to no current conditions",
        disabledBehavior = "weather toggle blocks scheduled lookups",
        quotaSummary = "Weather refresh is scheduled, single-request, and not downloadable media.",
    ),
    ProviderNetworkPolicy(
        source = ContentSource.LEMMY,
        sourceKey = "lemmy",
        sourceAliases = setOf("lemmy.world"),
        requestCacheTtlMs = TimeUnit.MINUTES.toMillis(15),
        hostSuffixes = setOf("lemmy.world"),
        maxAutomaticPrefetch = 20,
        maxBatchDownloadPerUserAction = 5,
        timeoutPolicy = "OkHttp connect/read/write timeouts",
        backoffPolicy = "degraded-source cooldown after repeated failures",
        cacheFallbackPolicy = "discover enhancement skipped; saved items remain",
        disabledBehavior = "hidden from active source lists",
        quotaSummary = "Public API with rate limiting at ~1 req/s. Fetches community wallpaper posts with vote counts.",
    ),
)

val providerNetworkPoliciesBySource: Map<ContentSource, ProviderNetworkPolicy> =
    providerNetworkPolicies.associateBy { it.source }

val providerNetworkPoliciesBySourceKey: Map<String, ProviderNetworkPolicy> =
    buildMap {
        providerNetworkPolicies.forEach { policy ->
            put(policy.sourceKey, policy)
            policy.sourceAliases.forEach { alias -> put(alias.lowercase(Locale.ROOT), policy) }
        }
    }

fun providerNetworkPolicyForSourceKey(source: String): ProviderNetworkPolicy? =
    providerNetworkPoliciesBySourceKey[source.lowercase(Locale.ROOT)]

fun providerRetryAfterHostSuffixes(): Set<String> =
    providerNetworkPolicies
        .filter { it.retryAfterHandling != RetryAfterHandling.NONE }
        .flatMap { it.hostSuffixes }
        .toSet()

fun longestProviderRequestCacheTtlMs(): Long =
    providerNetworkPolicies.mapNotNull { it.requestCacheTtlMs }.maxOrNull()
        ?: PROVIDER_CACHE_TTL_DEFAULT_MS

private fun RetryAfterHandling.label(): String = when (this) {
    RetryAfterHandling.NONE -> "none"
    RetryAfterHandling.DELTA_SECONDS -> "Retry-After seconds"
}

private fun limitLabel(limit: Int): String = when (limit) {
    Int.MAX_VALUE -> "unlimited"
    0 -> "blocked"
    else -> "$limit items"
}

private fun formatPolicyDuration(ms: Long?): String {
    if (ms == null) return "none"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    return when {
        minutes < 60 -> "${minutes}m"
        ms % TimeUnit.DAYS.toMillis(1) == 0L -> "${TimeUnit.MILLISECONDS.toDays(ms)}d"
        ms % TimeUnit.HOURS.toMillis(1) == 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}
