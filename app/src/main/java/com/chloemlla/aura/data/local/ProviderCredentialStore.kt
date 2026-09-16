package com.chloemlla.aura.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderCredentialKey(val storageKey: String) {
    companion object {
        val WALLHAVEN = ProviderCredentialKey("wallhaven_api_key")
        val PEXELS = ProviderCredentialKey("pexels_api_key")
        val PIXABAY = ProviderCredentialKey("pixabay_api_key")
        val FREESOUND = ProviderCredentialKey("freesound_api_key")
    }
}

internal enum class ProviderCredentialRecoveryReason {
    KEY_MISSING,
    KEY_INVALIDATED,
    CIPHERTEXT_UNREADABLE,
}

internal sealed interface ProviderCredentialReadResult {
    data object Missing : ProviderCredentialReadResult
    data class Available(val value: String) : ProviderCredentialReadResult
    data object RetryableFailure : ProviderCredentialReadResult
    data class ReentryRequired(
        val reason: ProviderCredentialRecoveryReason,
    ) : ProviderCredentialReadResult
}

class ProviderCredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal interface ProviderCredentialCipher {
    fun encrypt(value: String): String
    fun decrypt(encoded: String): String
    fun deleteKey()
}

internal class ProviderCredentialStore(
    private val prefs: SharedPreferences,
    private val cipher: ProviderCredentialCipher,
) {
    constructor(context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        cipher = AndroidProviderCredentialCipher(),
    )

    @Synchronized
    fun read(key: ProviderCredentialKey): ProviderCredentialReadResult {
        val encoded = prefs.getString(key.storageKey, null)
            ?: return ProviderCredentialReadResult.Missing
        return try {
            ProviderCredentialReadResult.Available(cipher.decrypt(encoded))
        } catch (error: Exception) {
            val recoveryReason = recoveryReason(error)
                ?: return ProviderCredentialReadResult.RetryableFailure
            recoverUnreadableCredentials(key, recoveryReason)
            ProviderCredentialReadResult.ReentryRequired(recoveryReason)
        }
    }

    @Synchronized
    fun set(key: ProviderCredentialKey, value: String) {
        if (value.isBlank()) {
            clear(key)
            return
        }
        val encoded = encryptForWrite(key, value)
        prefs.edit()
            .putString(key.storageKey, encoded)
            .putReentryKeys(reentryRequiredKeys() - key.storageKey)
            .apply()
    }

    @Synchronized
    fun clear(key: ProviderCredentialKey) {
        prefs.edit()
            .remove(key.storageKey)
            .putReentryKeys(reentryRequiredKeys() - key.storageKey)
            .apply()
    }

    @Synchronized
    fun hasReentryRequiredCredentials(): Boolean = reentryRequiredKeys().isNotEmpty()

    @Synchronized
    fun reentryRequiredKeys(): Set<String> =
        prefs.getStringSet(REENTRY_REQUIRED_KEYS, emptySet()).orEmpty().toSet()

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
        try {
            cipher.deleteKey()
        } catch (error: Exception) {
            throw ProviderCredentialStoreException("Provider credential key deletion failed", error)
        }
    }

    private fun encryptForWrite(key: ProviderCredentialKey, value: String): String = try {
        cipher.encrypt(value)
    } catch (error: Exception) {
        if (recoveryReason(error) == ProviderCredentialRecoveryReason.KEY_INVALIDATED) {
            recoverUnreadableCredentials(key, ProviderCredentialRecoveryReason.KEY_INVALIDATED)
            try {
                cipher.encrypt(value)
            } catch (retryError: Exception) {
                throw ProviderCredentialStoreException("Provider credential encryption failed", retryError)
            }
        } else {
            throw ProviderCredentialStoreException("Provider credential encryption failed", error)
        }
    }

    private fun recoverUnreadableCredentials(
        requestedKey: ProviderCredentialKey,
        reason: ProviderCredentialRecoveryReason,
    ) {
        val resetSharedKey = reason == ProviderCredentialRecoveryReason.KEY_MISSING ||
            reason == ProviderCredentialRecoveryReason.KEY_INVALIDATED
        val affectedKeys = if (resetSharedKey) {
            prefs.all.keys.filterNot { it == REENTRY_REQUIRED_KEYS }.toSet()
        } else {
            setOf(requestedKey.storageKey)
        }
        val pendingReentry = reentryRequiredKeys() + affectedKeys
        val editor = prefs.edit()
        affectedKeys.forEach { editor.remove(it) }
        editor.putReentryKeys(pendingReentry).apply()
        if (resetSharedKey) {
            try {
                cipher.deleteKey()
            } catch (_: Exception) {
                // The next save stays visibly unavailable if Android still cannot reset the alias.
            }
        }
    }

    private fun SharedPreferences.Editor.putReentryKeys(keys: Set<String>): SharedPreferences.Editor =
        if (keys.isEmpty()) remove(REENTRY_REQUIRED_KEYS) else putStringSet(REENTRY_REQUIRED_KEYS, keys)

    companion object {
        const val PREFS_NAME = "aura_provider_credentials"
        const val PREFS_FILE = "aura_provider_credentials.xml"
        const val KEY_ALIAS = "aura_provider_credentials_v1"
        internal const val REENTRY_REQUIRED_KEYS = "provider_credentials_reentry_required"
    }
}

internal class ProviderCredentialKeyMissingException : IllegalStateException("Credential key is missing")
internal class ProviderCredentialKeyInvalidatedException(
    cause: Throwable? = null,
) : IllegalStateException("Credential key is invalidated", cause)

private fun recoveryReason(error: Throwable): ProviderCredentialRecoveryReason? {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is ProviderCredentialKeyMissingException -> return ProviderCredentialRecoveryReason.KEY_MISSING
            is ProviderCredentialKeyInvalidatedException,
            is KeyPermanentlyInvalidatedException,
            is UnrecoverableEntryException,
            -> return ProviderCredentialRecoveryReason.KEY_INVALIDATED
            is AEADBadTagException,
            is BadPaddingException,
            is IllegalArgumentException,
            -> return ProviderCredentialRecoveryReason.CIPHERTEXT_UNREADABLE
        }
        current = current.cause
    }
    return null
}

internal class AndroidProviderCredentialCipher(
    private val keyAlias: String = ProviderCredentialStore.KEY_ALIAS,
) : ProviderCredentialCipher {
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            ENVELOPE_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(ENVELOPE_SEPARATOR)
    }

    override fun decrypt(encoded: String): String {
        val parts = encoded.split(ENVELOPE_SEPARATOR)
        require(parts.size == 3 && parts[0] == ENVELOPE_VERSION) {
            "Unsupported credential envelope"
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BITS / 8) {
            "Invalid credential envelope"
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw ProviderCredentialKeyInvalidatedException(error)
        }
    }

    override fun deleteKey() = synchronized(KEY_LOCK) {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun existingSecretKey(): SecretKey = synchronized(KEY_LOCK) {
        val entry = loadKeyStore().getEntry(keyAlias, null)
            as? KeyStore.SecretKeyEntry
        entry?.secretKey ?: throw ProviderCredentialKeyMissingException()
    }

    private fun getOrCreateSecretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = loadKeyStore()
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
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

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    companion object {
        private val KEY_LOCK = Any()
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = "v1"
        private const val ENVELOPE_SEPARATOR = ":"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}
