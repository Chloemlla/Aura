package com.chloemlla.aura.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderCredentialKey(val storageKey: String) {
    WALLHAVEN("wallhaven_api_key"),
    PEXELS("pexels_api_key"),
    PIXABAY("pixabay_api_key"),
    FREESOUND("freesound_api_key"),
    STABILITY_AI("stability_ai_key"),
}

class ProviderCredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ProviderCredentialStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(key: ProviderCredentialKey): String? {
        val encoded = prefs.getString(key.storageKey, null) ?: return null
        return decrypt(encoded)
    }

    fun set(key: ProviderCredentialKey, value: String) {
        if (value.isBlank()) {
            clear(key)
            return
        }
        prefs.edit().putString(key.storageKey, encrypt(value)).apply()
    }

    fun clear(key: ProviderCredentialKey) {
        prefs.edit().remove(key.storageKey).apply()
    }

    private fun encrypt(value: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        listOf(
            ENVELOPE_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }.getOrElse { error ->
        throw ProviderCredentialStoreException("Provider credential encryption failed", error)
    }

    private fun decrypt(encoded: String): String = runCatching {
        val parts = encoded.split(":")
        require(parts.size == 3 && parts[0] == ENVELOPE_VERSION) { "Unsupported credential envelope" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrElse { error ->
        throw ProviderCredentialStoreException("Provider credential decryption failed", error)
    }

    // Process-wide lock: concurrent first-time credential ops (e.g. the startup legacy
    // migration reading all five key flows) must not each generate a key — the second
    // generateKey() would overwrite the alias and orphan ciphertext written under the first.
    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return@synchronized generator.generateKey()
    }

    companion object {
        private val KEY_LOCK = Any()
        const val PREFS_NAME = "aura_provider_credentials"
        const val PREFS_FILE = "aura_provider_credentials.xml"
        const val KEY_ALIAS = "aura_provider_credentials_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = "v1"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
