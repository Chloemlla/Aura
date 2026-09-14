package com.freevibe.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCredentialStoreInstrumentedTest {
    private lateinit var store: ProviderCredentialStore
    private lateinit var cipher: AndroidProviderCredentialCipher
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cipher = AndroidProviderCredentialCipher(TEST_KEY_ALIAS)
        store = ProviderCredentialStore(
            prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE),
            cipher = cipher,
        )
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun sameDeviceKeystoreRoundTripAndClear() {
        val secret = "ON_DEVICE_PROVIDER_KEY_SENTINEL"

        store.set(ProviderCredentialKey.WALLHAVEN, secret)

        assertEquals(
            ProviderCredentialReadResult.Available(secret),
            store.read(ProviderCredentialKey.WALLHAVEN),
        )
        store.clear(ProviderCredentialKey.WALLHAVEN)
        assertEquals(ProviderCredentialReadResult.Missing, store.read(ProviderCredentialKey.WALLHAVEN))
        assertFalse(store.hasReentryRequiredCredentials())
    }

    @Test
    fun missingDeviceKeyClearsRestoredCiphertextAndRequestsReentry() {
        store.set(ProviderCredentialKey.PEXELS, "ON_DEVICE_RESTORE_SENTINEL")
        cipher.deleteKey()

        assertEquals(
            ProviderCredentialReadResult.ReentryRequired(ProviderCredentialRecoveryReason.KEY_MISSING),
            store.read(ProviderCredentialKey.PEXELS),
        )
        val prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
        assertNull(prefs.getString(ProviderCredentialKey.PEXELS.storageKey, null))
        assertTrue(store.hasReentryRequiredCredentials())
    }

    companion object {
        private const val TEST_PREFS = "provider_credential_store_instrumented_test"
        private const val TEST_KEY_ALIAS = "provider_credential_store_instrumented_test_v1"
    }
}
