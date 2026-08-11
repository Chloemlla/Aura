package com.chloemlla.aura.data.legal

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.providerNetworkPolicies
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate that keeps provider truth single-source.
 *
 * Every fact about a content source — is it fetched, in which builds, on which
 * channels, behind which switch, needing which key or permission — lives once in
 * [providerCapabilities]. The disclosure list, the runtime-control list, and the
 * network-endpoint manifest are all checked against it here, so a source can no
 * longer read "dormant" in one file while another still fetches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderCapabilityContractTest {

    private val endpointManifest: JSONObject by lazy {
        JSONObject(File("../docs/security/network-endpoints.json").readText())
    }

    private fun endpointsById(): Map<String, JSONObject> {
        val array = endpointManifest.getJSONArray("endpoints")
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .associateBy { it.getString("id") }
    }

    @Test
    fun `every content source appears exactly once`() {
        val sources = providerCapabilities.map { it.source }

        assertEquals(
            "duplicate registry entries: " +
                sources.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            sources.size,
            sources.toSet().size,
        )
        assertEquals(ContentSource.entries.toSet(), sources.toSet())
    }

    @Test
    fun `disclosure status is derived from lifecycle`() {
        providerCapabilities.forEach { capability ->
            val disclosure = providerDisclosuresBySource.getValue(capability.source)
            assertEquals(
                "${capability.source} disclosure status must match its lifecycle",
                capability.lifecycle.disclosureStatus(),
                disclosure.status,
            )
        }
    }

    @Test
    fun `every source has a runtime control entry`() {
        assertEquals(
            ContentSource.entries.toSet(),
            providerRuntimeControls.map { it.source }.toSet(),
        )
        assertEquals(
            providerRuntimeControls.size,
            providerRuntimeControls.map { it.source }.toSet().size,
        )
    }

    @Test
    fun `impossible active and legacy combinations fail the gate`() {
        providerCapabilities.forEach { capability ->
            when (capability.lifecycle) {
                ProviderLifecycle.LEGACY -> assertFalse(
                    "${capability.source} is legacy, so it must not be able to fetch",
                    capability.canFetch,
                )
                ProviderLifecycle.ACTIVE -> {
                    if (capability.health == ProviderHealth.NETWORKED) {
                        assertTrue(
                            "${capability.source} is an active networked source and must declare endpoints",
                            capability.endpointIds.isNotEmpty(),
                        )
                    }
                    // An active source is on out of the box unless it first needs
                    // something only the user can give: a credential or a permission.
                    val gatedOnUser =
                        capability.configuration == ProviderConfiguration.REQUIRED_KEY ||
                            capability.permission != ProviderPermission.NONE
                    assertEquals(
                        "${capability.source} default-enabled state must follow from its gating",
                        !gatedOnUser,
                        capability.enabledByDefault,
                    )
                }
                ProviderLifecycle.LOCAL -> {
                    assertTrue(
                        "${capability.source} is local and must not declare network endpoints",
                        capability.endpointIds.isEmpty(),
                    )
                    assertEquals(ProviderHealth.OFFLINE, capability.health)
                }
                ProviderLifecycle.COMMUNITY, ProviderLifecycle.GENERATED -> assertTrue(
                    "${capability.source} must declare the endpoints it calls",
                    capability.endpointIds.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `offline sources are never described as networked`() {
        providerCapabilities
            .filter { it.health == ProviderHealth.OFFLINE }
            .forEach { assertFalse("${it.source} cannot fetch while offline", it.canFetch) }
    }

    @Test
    fun `a required key is never paired with an on-by-default source`() {
        providerCapabilities
            .filter { it.configuration == ProviderConfiguration.REQUIRED_KEY }
            .forEach {
                assertFalse(
                    "${it.source} needs a user credential, so it cannot ship enabled",
                    it.enabledByDefault,
                )
            }
    }

    @Test
    fun `a permission-gated source is never on by default`() {
        providerCapabilities
            .filter { it.permission == ProviderPermission.APPROXIMATE_LOCATION }
            .forEach {
                assertFalse(
                    "${it.source} needs a runtime permission, so it cannot ship enabled",
                    it.enabledByDefault,
                )
            }
    }

    @Test
    fun `declared endpoints exist in the network manifest`() {
        val endpoints = endpointsById()
        providerCapabilities.forEach { capability ->
            capability.endpointIds.forEach { id ->
                assertTrue(
                    "${capability.source} declares unknown endpoint id $id",
                    endpoints.containsKey(id),
                )
            }
        }
    }

    @Test
    fun `every manifest endpoint is owned by exactly one source`() {
        val declared = providerCapabilities.flatMap { it.endpointIds }
        assertEquals(
            "an endpoint may not be claimed twice",
            declared.size,
            declared.toSet().size,
        )
        assertEquals(
            "every documented endpoint must belong to a registry entry",
            endpointsById().keys,
            declared.toSet(),
        )
    }

    @Test
    fun `kill switch keys match the manifest`() {
        val endpoints = endpointsById()
        providerCapabilities
            .filter { it.killSwitchKey != null }
            .forEach { capability ->
                val key = capability.killSwitchKey!!
                val owns = capability.endpointIds.any { id ->
                    endpoints.getValue(id).getString("killSwitch").contains(key)
                }
                assertTrue(
                    "${capability.source} declares kill switch $key that no endpoint documents",
                    owns,
                )
            }
    }

    @Test
    fun `FOSS builds drop exactly the Firebase-backed sources`() {
        val fossExcluded = providerCapabilities
            .filterNot { it.availableIn(ProviderBuild.FOSS) }
            .map { it.source }
            .toSet()

        assertEquals(setOf(ContentSource.COMMUNITY), fossExcluded)
    }

    @Test
    fun `the Play channel drops exactly the sources Aura's own risk profile forbids`() {
        val playExcluded = providerCapabilities
            .filterNot { it.availableOn(ProviderChannel.PLAY) }
            .map { it.source }
            .toSet()

        assertEquals(setOf(ContentSource.YOUTUBE), playExcluded)
    }

    @Test
    fun `attribution is required for every source that is not the user's own files`() {
        providerCapabilities.forEach { capability ->
            if (capability.source == ContentSource.LOCAL) {
                assertFalse(capability.requiresAttribution)
            } else {
                assertTrue(
                    "${capability.source} must carry provider attribution",
                    capability.requiresAttribution,
                )
            }
        }
    }

    @Test
    fun `every source has a network policy whose diagnostics carry registry truth`() {
        assertEquals(
            ContentSource.entries.toSet(),
            providerNetworkPolicies.map { it.source }.toSet(),
        )
        providerNetworkPolicies.forEach { policy ->
            val capability = providerCapability(policy.source)
            assertTrue(
                "${policy.source} diagnostics must report its lifecycle",
                policy.capabilitySummary.contains(capability.lifecycle.name.lowercase()),
            )
            assertTrue(
                "${policy.source} diagnostics must be part of the support summary",
                policy.diagnosticSummary.startsWith(policy.capabilitySummary),
            )
        }
    }

    @Test
    fun `lookup covers every enum value`() {
        ContentSource.entries.forEach { source ->
            assertEquals(source, providerCapability(source).source)
        }
    }
}
