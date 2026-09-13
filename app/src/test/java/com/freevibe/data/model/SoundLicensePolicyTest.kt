package com.freevibe.data.model

import com.freevibe.data.legal.isProviderAvailableInCurrentArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundLicensePolicyTest {

    @Test
    fun `cc0 bundled sounds allow reviewed Aura Originals actions`() {
        val capabilities = sound(
            source = ContentSource.BUNDLED,
            license = "CC0 1.0",
            sourcePageUrl = "https://freesound.org/s/1/",
        ).soundLicenseCapabilities()

        assertEquals("CC0", capabilities.normalizedLicense)
        assertTrue(capabilities.canUse(SoundAction.APPLY))
        assertTrue(capabilities.canUse(SoundAction.DOWNLOAD))
        assertTrue(capabilities.canUse(SoundAction.EDIT))
        assertTrue(capabilities.canUse(SoundAction.BUNDLE))
    }

    @Test
    fun `youtube sounds require confirmation for apply and download but disable edit and bundle`() {
        val capabilities = sound(
            source = ContentSource.YOUTUBE,
            license = "YouTube",
            sourcePageUrl = "https://www.youtube.com/watch?v=abc12345678",
        ).soundLicenseCapabilities()

        val expectedDecision = if (isProviderAvailableInCurrentArtifact(ContentSource.YOUTUBE)) {
            SoundActionDecision.CONFIRMATION_REQUIRED
        } else {
            SoundActionDecision.DISABLED
        }
        assertEquals(expectedDecision, capabilities.capability(SoundAction.APPLY).decision)
        assertEquals(expectedDecision, capabilities.capability(SoundAction.DOWNLOAD).decision)
        assertEquals(SoundActionDecision.DISABLED, capabilities.capability(SoundAction.EDIT).decision)
        assertEquals(SoundActionDecision.DISABLED, capabilities.capability(SoundAction.BUNDLE).decision)
    }

    @Test
    fun `legacy soundcloud records are attribution only`() {
        val capabilities = sound(
            source = ContentSource.SOUNDCLOUD,
            license = "SoundCloud",
            sourcePageUrl = "https://soundcloud.com/artist/track",
        ).soundLicenseCapabilities()

        assertEquals(SoundActionDecision.DISABLED, capabilities.capability(SoundAction.APPLY).decision)
        assertEquals(SoundActionDecision.DISABLED, capabilities.capability(SoundAction.DOWNLOAD).decision)
        assertEquals(SoundActionDecision.DISABLED, capabilities.capability(SoundAction.EDIT).decision)
        assertFalse(capabilities.canUse(SoundAction.SHARE))
    }

    @Test
    fun `noncommercial licenses require confirmation and cannot be bundled`() {
        val capabilities = sound(
            source = ContentSource.COMMUNITY,
            license = "Attribution-NonCommercial 4.0",
            sourcePageUrl = "https://example.com/community/sound/42",
        ).soundLicenseCapabilities()

        assertEquals("CC BY-NC", capabilities.normalizedLicense)
        if (isProviderAvailableInCurrentArtifact(ContentSource.COMMUNITY)) {
            assertTrue(capabilities.requiresConfirmation(SoundAction.APPLY))
            assertTrue(capabilities.requiresConfirmation(SoundAction.DOWNLOAD))
            assertTrue(capabilities.requiresConfirmation(SoundAction.EDIT))
        } else {
            assertFalse(capabilities.canUse(SoundAction.APPLY))
            assertFalse(capabilities.canUse(SoundAction.DOWNLOAD))
            assertFalse(capabilities.canUse(SoundAction.EDIT))
        }
        assertFalse(capabilities.canUse(SoundAction.BUNDLE))
    }

    @Test
    fun `legacy freesound record stays attribution only even with complete CC0 metadata`() {
        val capabilities = sound(
            source = ContentSource.FREESOUND,
            license = "CC0",
            sourcePageUrl = "https://freesound.org/s/42/",
        ).soundLicenseCapabilities()

        assertFalse(capabilities.canUse(SoundAction.APPLY))
        assertFalse(capabilities.canUse(SoundAction.PREVIEW))
        assertFalse(capabilities.canUse(SoundAction.DOWNLOAD))
        assertFalse(capabilities.canUse(SoundAction.EDIT))
        assertFalse(capabilities.canUse(SoundAction.SHARE))
        assertFalse(capabilities.canUse(SoundAction.BUNDLE))
    }

    @Test
    fun `community uploads require confirmation but can share stored provenance`() {
        val capabilities = sound(
            source = ContentSource.COMMUNITY,
            license = "User Upload",
        ).soundLicenseCapabilities()

        assertEquals("User Upload", capabilities.normalizedLicense)
        if (isProviderAvailableInCurrentArtifact(ContentSource.COMMUNITY)) {
            assertTrue(capabilities.requiresConfirmation(SoundAction.APPLY))
            assertTrue(capabilities.requiresConfirmation(SoundAction.DOWNLOAD))
            assertTrue(capabilities.requiresConfirmation(SoundAction.EDIT))
            assertTrue(capabilities.canUse(SoundAction.SHARE))
        } else {
            assertFalse(capabilities.canUse(SoundAction.APPLY))
            assertFalse(capabilities.canUse(SoundAction.DOWNLOAD))
            assertFalse(capabilities.canUse(SoundAction.EDIT))
            assertFalse(capabilities.canUse(SoundAction.SHARE))
        }
        assertFalse(capabilities.canUse(SoundAction.BUNDLE))
    }

    @Test
    fun `community uploads with selected CC0 license allow normal personal actions`() {
        val capabilities = sound(
            source = ContentSource.COMMUNITY,
            license = "CC0",
        ).soundLicenseCapabilities()

        assertEquals("CC0", capabilities.normalizedLicense)
        val available = isProviderAvailableInCurrentArtifact(ContentSource.COMMUNITY)
        assertEquals(available, capabilities.canUse(SoundAction.APPLY))
        assertEquals(available, capabilities.canUse(SoundAction.DOWNLOAD))
        assertEquals(available, capabilities.canUse(SoundAction.EDIT))
        assertEquals(available, capabilities.canUse(SoundAction.SHARE))
        assertFalse(capabilities.canUse(SoundAction.BUNDLE))
    }

    @Test
    fun `missing remote license disables sound actions`() {
        val capabilities = sound(
            source = ContentSource.FREESOUND,
            license = "",
            sourcePageUrl = "https://freesound.org/s/42/",
        ).soundLicenseCapabilities()

        assertEquals("Unknown", capabilities.normalizedLicense)
        assertFalse(capabilities.canUse(SoundAction.APPLY))
        assertFalse(capabilities.canUse(SoundAction.DOWNLOAD))
        assertFalse(capabilities.canUse(SoundAction.SHARE))
        assertFalse(capabilities.canUse(SoundAction.EDIT))
    }

    private fun sound(
        source: ContentSource,
        license: String,
        sourcePageUrl: String = "",
    ) = Sound(
        id = "sound_1",
        source = source,
        name = "Policy Tone",
        previewUrl = "https://example.com/preview.mp3",
        downloadUrl = "https://example.com/download.mp3",
        license = license,
        uploaderName = "Creator",
        sourcePageUrl = sourcePageUrl,
    )
}
