package ai.openonion.oochat.data.local

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.di.DefaultAppContainer
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the bookkeeping that tells Settings *which* store lost its encryption.
 *
 * The bug these guard against: `isSecureStorage` read one
 * [SafePreferencesWrapper] instance — the one on `connectonion_prefs` — on the
 * stated assumption that "a Keystore failure is device-wide, so this single
 * instance's state accurately reflects every other instance the app creates".
 * `EncryptedSharedPreferences` derives a keyset **per file**, so that
 * assumption is false, and the file it did not consult
 * (`connectonion_keys_secure`) is the one holding the Ed25519 private key and
 * the BIP39 recovery phrase. A keyset lost for that file alone put the phrase
 * on disk in readable words with the warning banner never appearing.
 *
 * Robolectric has no AndroidKeyStore shadow, so every wrapper built here takes
 * the plaintext fallback. That is what makes per-store attribution testable at
 * all: the interesting assertion is not "something degraded" but "the store I
 * never opened is not being reported as degraded".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafePreferencesDegradationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SafePreferencesWrapper.resetDegradedStoresForTest()
    }

    @After
    fun tearDown() {
        // The record is process-wide, so leaving it dirty leaks into whatever
        // test runs next in this JVM.
        SafePreferencesWrapper.resetDegradedStoresForTest()
    }

    @Test
    fun `a fallback is attributed to the store that fell back, not to every store`() {
        SafePreferencesWrapper(context, "store_alpha")

        assertTrue(SafePreferencesWrapper.isStoreDegraded("store_alpha"))
        assertFalse(
            "A store that was never opened must not be reported as degraded",
            SafePreferencesWrapper.isStoreDegraded("store_beta")
        )
        assertEquals(setOf("store_alpha"), SafePreferencesWrapper.degradedStores())
    }

    @Test
    fun `each degraded store is recorded separately`() {
        SafePreferencesWrapper(context, "store_alpha")
        SafePreferencesWrapper(context, "store_beta")

        assertEquals(setOf("store_alpha", "store_beta"), SafePreferencesWrapper.degradedStores())
    }

    @Test
    fun `reopening the same store does not double-count it`() {
        SafePreferencesWrapper(context, "store_alpha")
        SafePreferencesWrapper(context, "store_alpha")

        assertEquals(1, SafePreferencesWrapper.degradedStores().size)
    }

    @Test
    fun `isIdentityStorageSecure reports the identity store, not connectonion_prefs`() {
        val container = DefaultAppContainer(context)

        // Reading it is what opens the identity store — the point of the
        // probe, since `KeyManager.prefs` is `by lazy` and an unopened store
        // reports nothing at all.
        assertFalse(container.isIdentityStorageSecure)
        assertTrue(
            "The identity store must be the one attributed, by name",
            SafePreferencesWrapper.isStoreDegraded(KeyManager.PREFS_NAME)
        )
    }

    @Test
    fun `isSecureStorage stays false while any single store is degraded`() {
        val container = DefaultAppContainer(context)

        assertFalse(container.isSecureStorage)
    }

    @Test
    fun `probing opens the identity store even when no key has been read`() {
        DefaultAppContainer(context).isSecureStorage

        assertTrue(
            "Without the probe, a degraded identity store stays silent until the first key read",
            KeyManager.PREFS_NAME in SafePreferencesWrapper.degradedStores()
        )
    }
}
