package com.chloemlla.aura.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.chloemlla.aura.config.defaultGeneratedWallpaperProviderKey
import kotlinx.coroutines.flow.Flow

private val stabilityCredentialKey = ProviderCredentialKey("stability_ai_key")
private val stabilityLegacyPreferenceKey = stringPreferencesKey("stability_ai_key")

internal fun PreferencesManager.generatedWallpaperProviderKeyForFlavor(): Flow<String> =
    providerCredential(
        stabilityCredentialKey,
        stabilityLegacyPreferenceKey,
        defaultGeneratedWallpaperProviderKey,
    )

internal suspend fun PreferencesManager.setGeneratedWallpaperProviderKeyForFlavor(key: String) {
    setProviderCredential(stabilityCredentialKey, stabilityLegacyPreferenceKey, key)
}
