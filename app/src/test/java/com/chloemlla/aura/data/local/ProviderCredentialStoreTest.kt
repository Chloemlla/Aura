package com.chloemlla.aura.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderCredentialStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var cipher: FakeProviderCredentialCipher
    private lateinit var store: ProviderCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("provider-credential-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cipher = FakeProviderCredentialCipher()
        store = ProviderCredentialStore(prefs, cipher)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `same device write and read keeps plaintext out of shared preferences`() {
        val secret = "WALLHAVEN_SENTINEL_123"

        store.set(ProviderCredentialKey.WALLHAVEN, secret)

        assertEquals(
            ProviderCredentialReadResult.Available(secret),
            store.read(ProviderCredentialKey.WALLHAVEN),
        )
        assertFalse(prefs.getString(ProviderCredentialKey.WALLHAVEN.storageKey, "").orEmpty().contains(secret))
        assertFalse(store.hasReentryRequiredCredentials())
    }

    @Test
    fun `missing key after backup style restore clears ciphertext and requests reentry`() {
        store.set(ProviderCredentialKey.PEXELS, "PEXELS_SENTINEL")
        cipher.failure = ProviderCredentialKeyMissingException()

        val result = store.read(ProviderCredentialKey.PEXELS)

        assertEquals(
            ProviderCredentialReadResult.ReentryRequired(ProviderCredentialRecoveryReason.KEY_MISSING),
            result,
        )
        assertNull(prefs.getString(ProviderCredentialKey.PEXELS.storageKey, null))
        assertTrue(store.hasReentryRequiredCredentials())
        assertEquals(1, cipher.deleteKeyCalls)
    }

    @Test
    fun `invalidated shared key clears every affected credential`() {
        store.set(ProviderCredentialKey.WALLHAVEN, "WALLHAVEN_SENTINEL")
        store.set(ProviderCredentialKey.PIXABAY, "PIXABAY_SENTINEL")
        cipher.failure = ProviderCredentialKeyInvalidatedException()

        val result = store.read(ProviderCredentialKey.WALLHAVEN)

        assertEquals(
            ProviderCredentialReadResult.ReentryRequired(ProviderCredentialRecoveryReason.KEY_INVALIDATED),
            result,
        )
        assertNull(prefs.getString(ProviderCredentialKey.WALLHAVEN.storageKey, null))
        assertNull(prefs.getString(ProviderCredentialKey.PIXABAY.storageKey, null))
        assertEquals(
            setOf(ProviderCredentialKey.WALLHAVEN.storageKey, ProviderCredentialKey.PIXABAY.storageKey),
            prefs.getStringSet(ProviderCredentialStore.REENTRY_REQUIRED_KEYS, emptySet()),
        )
        assertEquals(1, cipher.deleteKeyCalls)
    }

    @Test
    fun `saving and clearing affected keys dismisses reentry only after all are handled`() {
        store.set(ProviderCredentialKey.WALLHAVEN, "WALLHAVEN_SENTINEL")
        store.set(ProviderCredentialKey.PIXABAY, "PIXABAY_SENTINEL")
        cipher.failure = ProviderCredentialKeyMissingException()
        store.read(ProviderCredentialKey.WALLHAVEN)
        cipher.failure = null

        store.set(ProviderCredentialKey.WALLHAVEN, "NEW_WALLHAVEN_SENTINEL")
        assertTrue(store.hasReentryRequiredCredentials())

        store.clear(ProviderCredentialKey.PIXABAY)
        assertFalse(store.hasReentryRequiredCredentials())
    }

    @Test
    fun `write replaces an invalidated shared key and preserves reentry for other values`() {
        store.set(ProviderCredentialKey.PIXABAY, "OLD_PIXABAY_SENTINEL")
        cipher.encryptFailure = ProviderCredentialKeyInvalidatedException()

        store.set(ProviderCredentialKey.WALLHAVEN, "NEW_WALLHAVEN_SENTINEL")

        assertEquals(
            ProviderCredentialReadResult.Available("NEW_WALLHAVEN_SENTINEL"),
            store.read(ProviderCredentialKey.WALLHAVEN),
        )
        assertNull(prefs.getString(ProviderCredentialKey.PIXABAY.storageKey, null))
        assertTrue(store.hasReentryRequiredCredentials())
        assertEquals(1, cipher.deleteKeyCalls)
    }

    @Test
    fun `corrupt envelope clears only that credential`() {
        store.set(ProviderCredentialKey.WALLHAVEN, "WALLHAVEN_SENTINEL")
        store.set(ProviderCredentialKey.PIXABAY, "PIXABAY_SENTINEL")
        prefs.edit().putString(ProviderCredentialKey.WALLHAVEN.storageKey, "not-an-envelope").commit()

        val result = store.read(ProviderCredentialKey.WALLHAVEN)

        assertEquals(
            ProviderCredentialReadResult.ReentryRequired(ProviderCredentialRecoveryReason.CIPHERTEXT_UNREADABLE),
            result,
        )
        assertNull(prefs.getString(ProviderCredentialKey.WALLHAVEN.storageKey, null))
        assertTrue(prefs.contains(ProviderCredentialKey.PIXABAY.storageKey))
        assertEquals(0, cipher.deleteKeyCalls)
    }

    @Test
    fun `temporary keystore failure retains ciphertext without reentry marker`() {
        store.set(ProviderCredentialKey.WALLHAVEN, "WALLHAVEN_SENTINEL")
        cipher.failure = IllegalStateException("keystore temporarily unavailable")

        assertEquals(
            ProviderCredentialReadResult.RetryableFailure,
            store.read(ProviderCredentialKey.WALLHAVEN),
        )
        assertTrue(prefs.contains(ProviderCredentialKey.WALLHAVEN.storageKey))
        assertFalse(store.hasReentryRequiredCredentials())
        assertEquals(0, cipher.deleteKeyCalls)
    }

    @Test
    fun `clear all removes ciphertext recovery state and encryption key`() {
        store.set(ProviderCredentialKey.WALLHAVEN, "WALLHAVEN_SENTINEL")
        store.set(ProviderCredentialKey.PEXELS, "PEXELS_SENTINEL")
        cipher.failure = ProviderCredentialKeyMissingException()
        store.read(ProviderCredentialKey.WALLHAVEN)
        cipher.failure = null

        store.clearAll()

        assertTrue(prefs.all.isEmpty())
        assertFalse(store.hasReentryRequiredCredentials())
        assertEquals(2, cipher.deleteKeyCalls)
    }
}

private class FakeProviderCredentialCipher : ProviderCredentialCipher {
    var failure: Throwable? = null
    var encryptFailure: Throwable? = null
    var deleteKeyCalls: Int = 0

    override fun encrypt(value: String): String {
        encryptFailure?.let {
            encryptFailure = null
            throw it
        }
        return "sealed:${value.reversed()}"
    }

    override fun decrypt(encoded: String): String {
        failure?.let { throw it }
        require(encoded.startsWith("sealed:")) { "Unsupported credential envelope" }
        return encoded.removePrefix("sealed:").reversed()
    }

    override fun deleteKey() {
        deleteKeyCalls += 1
    }
}
