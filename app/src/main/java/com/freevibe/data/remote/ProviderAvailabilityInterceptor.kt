package com.freevibe.data.remote

import com.freevibe.data.legal.isProviderAvailableInCurrentArtifact
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.providerNetworkPolicies
import java.io.IOException
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Response

/** Final network-layer guard for providers excluded by lifecycle, build, or channel. */
class ProviderAvailabilityInterceptor(
    private val isAvailable: (ContentSource) -> Boolean = ::isProviderAvailableInCurrentArtifact,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = providerSourceForHost(request.url.host)
        if (source != null && !isAvailable(source)) {
            throw IOException("Provider ${source.name} is unavailable in this Aura artifact")
        }
        return chain.proceed(request)
    }
}

internal fun providerSourceForHost(rawHost: String): ContentSource? {
    val host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
    return providerNetworkPolicies
        .asSequence()
        .flatMap { policy -> policy.hostSuffixes.asSequence().map { suffix -> suffix to policy.source } }
        .filter { (suffix, _) -> host == suffix || host.endsWith(".$suffix") }
        .maxByOrNull { (suffix, _) -> suffix.length }
        ?.second
}
