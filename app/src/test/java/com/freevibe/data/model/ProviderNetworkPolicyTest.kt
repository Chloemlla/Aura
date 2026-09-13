package com.freevibe.data.model

import com.freevibe.data.legal.ProviderLifecycle
import com.freevibe.data.legal.providerCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderNetworkPolicyTest {

    @Test
    fun `network policies cover every source exactly once`() {
        val covered = providerNetworkPolicies.map { it.source }

        assertEquals(ContentSource.entries.toSet(), covered.toSet())
        assertEquals(covered.size, covered.toSet().size)
    }

    @Test
    fun `network policies declare timeout backoff fallback and disabled behavior`() {
        providerNetworkPolicies.forEach { policy ->
            assertTrue("${policy.source} timeout", policy.timeoutPolicy.isNotBlank())
            assertTrue("${policy.source} backoff", policy.backoffPolicy.isNotBlank())
            assertTrue("${policy.source} fallback", policy.cacheFallbackPolicy.isNotBlank())
            assertTrue("${policy.source} disabled", policy.disabledBehavior.isNotBlank())
            assertTrue("${policy.source} diagnostic timeout", policy.diagnosticSummary.contains("timeout "))
            assertTrue("${policy.source} diagnostic backoff", policy.diagnosticSummary.contains("backoff "))
            assertTrue("${policy.source} diagnostic fallback", policy.diagnosticSummary.contains("fallback "))
            assertFalse("${policy.source} no stale wording", policy.diagnosticSummary.contains("no host-specific"))
            assertFalse("${policy.source} no auth header leak", policy.diagnosticSummary.contains("Authorization"))
            assertFalse("${policy.source} no token leak", policy.diagnosticSummary.contains("apikey"))
        }
    }

    @Test
    fun `pixabay declares twenty four hour cache and retry policy`() {
        val policy = providerNetworkPoliciesBySource.getValue(ContentSource.PIXABAY)

        assertEquals(PROVIDER_CACHE_TTL_PIXABAY_MS, policy.requestCacheTtlMs)
        assertEquals(PROVIDER_CACHE_TTL_PIXABAY_MS, policy.mediaUrlTtlMs)
        assertEquals(RetryAfterHandling.DELTA_SECONDS, policy.retryAfterHandling)
        assertTrue(policy.hostSuffixes.contains("pixabay.com"))
        assertTrue(policy.allowsAutomaticPrefetch(30))
        assertFalse(policy.allowsAutomaticPrefetch(31))
        assertTrue(policy.allowsBatchDownload(30))
        assertFalse(policy.allowsBatchDownload(31))
    }

    @Test
    fun `legacy providers are inactive attribution only policies`() {
        providerNetworkPolicies
            .filter { providerCapability(it.source).lifecycle == ProviderLifecycle.LEGACY }
            .forEach { policy ->
                assertEquals(null, policy.requestCacheTtlMs)
                assertEquals(null, policy.mediaUrlTtlMs)
                assertEquals(RetryAfterHandling.NONE, policy.retryAfterHandling)
                assertFalse(policy.allowsAutomaticPrefetch(1))
                assertFalse(policy.allowsBatchDownload(1))
                assertTrue(policy.quotaSummary.startsWith("Legacy attribution only."))
                assertTrue(policy.diagnosticSummary.contains("prefetch blocked"))
                assertTrue(policy.diagnosticSummary.contains("batch blocked"))
            }

        val freesound = providerNetworkPoliciesBySource.getValue(ContentSource.FREESOUND)
        assertSame(freesound, providerNetworkPolicyForSourceKey("freesound"))
        assertSame(freesound, providerNetworkPolicyForSourceKey("openverse"))
    }

    @Test
    fun `retry host suffixes are policy derived`() {
        val hosts = providerRetryAfterHostSuffixes()

        assertTrue(hosts.contains("pixabay.com"))
        assertFalse(hosts.contains("freesound.org"))
        assertFalse(hosts.contains("openverse.org"))
        assertFalse(hosts.contains("wallhaven.cc"))
    }

    @Test
    fun `active NASA diagnostics describe live APOD fetching`() {
        val policy = providerNetworkPoliciesBySource.getValue(ContentSource.NASA)

        assertEquals(ProviderLifecycle.ACTIVE, providerCapability(ContentSource.NASA).lifecycle)
        assertEquals(PROVIDER_CACHE_TTL_DEFAULT_MS, policy.requestCacheTtlMs)
        assertTrue(policy.cacheFallbackPolicy.contains("APOD cache"))
        assertTrue(policy.quotaSummary.contains("One APOD request"))
        assertFalse(policy.diagnosticSummary.contains("Legacy"))
    }
}
