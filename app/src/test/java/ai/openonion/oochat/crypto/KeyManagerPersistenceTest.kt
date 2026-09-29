package ai.openonion.oochat.crypto

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [KeyManager]'s storage layer — [KeyManager.load],
 * [KeyManager.save], [KeyManager.loadOrGenerate] (existing-key path only),
 * [KeyManager.importFromPhraseOrHex] (hex branch only), and
 * [KeyManager.exportBackup] (legacy-hex branch only).
 *
 * Requires Robolectric (real Context/SharedPreferences), not the plain-JVM
 * setup [KeyManagerTest] uses: [KeyManager.prefs] is routed through
 * [ai.openonion.oochat.data.local.SafePreferencesWrapper], which
 * falls back to plain SharedPreferences when Robolectric's missing
 * AndroidKeyStore shadow makes the encrypted path throw.
 *
 * Deliberately does not cover [KeyManager.generate], [KeyManager.sign],
 * [KeyManager.keysFromSeed], [KeyManager.generateWithMnemonic], or
 * [KeyManager.keysFromMnemonic] (and therefore not the mnemonic-storage
 * branches of [KeyManager.importFromPhraseOrHex]/[KeyManager.resetIdentity]/
 * [KeyManager.exportBackup] either) — all of those call the native
 * `com.goterl.lazysodium` binary, which cannot load on a host JVM regardless
 * of Robolectric (see [KeyManagerTest]'s doc comment for the confirmed
 * `UnsatisfiedLinkError`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyManagerPersistenceTest {

    private val keyManager = KeyManager(ApplicationProvider.getApplicationContext<Application>())

    // AddressData is a data class with ByteArray properties, so Kotlin's
    // generated equals() compares those by reference — assert field-by-field
    // with assertArrayEquals for the byte arrays instead of assertEquals(AddressData, AddressData).
    private fun assertSameKeys(expected: KeyManager.AddressData, actual: KeyManager.AddressData?) {
        assertEquals(expected.address, actual?.address)
        assertEquals(expected.shortAddress, actual?.shortAddress)
        assertArrayEquals(expected.publicKey, actual?.publicKey)
        assertArrayEquals(expected.privateKey, actual?.privateKey)
    }

    @Test
    fun `load returns null before anything has been saved`() {
        assertNull(keyManager.load())
    }

    @Test
    fun `save then load round-trips an AddressData`() {
        val keys = keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32))

        keyManager.save(keys)

        assertSameKeys(keys, keyManager.load())
    }

    @Test
    fun `save overwrites a previously saved identity`() {
        val first = keyManager.keysFromHex("11".repeat(32) + "22".repeat(32))
        val second = keyManager.keysFromHex("33".repeat(32) + "44".repeat(32))

        keyManager.save(first)
        keyManager.save(second)

        assertSameKeys(second, keyManager.load())
    }

    @Test
    fun `loadOrGenerate returns the existing identity without needing to generate one`() {
        val keys = keyManager.keysFromHex("cc".repeat(32) + "dd".repeat(32))
        keyManager.save(keys)

        // If this fell through to generateWithMnemonic() (the "no existing
        // key" path), it would hit the native-libsodium UnsatisfiedLinkError
        // documented on this class — reaching here at all is itself proof
        // load() (not generate()) satisfied the call.
        val result = keyManager.loadOrGenerate()

        assertSameKeys(keys, result)
    }

    @Test
    fun `importFromPhraseOrHex with a hex key saves it and is retrievable via load`() {
        val hex = "ee".repeat(32) + "ff".repeat(32)

        val imported = keyManager.importFromPhraseOrHex(hex)

        assertSameKeys(imported, keyManager.load())
    }

    @Test
    fun `importFromPhraseOrHex accepts a 0x-prefixed hex key identically`() {
        val hex = "12".repeat(32) + "34".repeat(32)

        val withPrefix = keyManager.importFromPhraseOrHex("0x$hex")

        assertEquals(withPrefix.address, keyManager.load()?.address)
    }

    @Test
    fun `a non-hex import leaves the existing identity untouched`() {
        // The failure that made this worth guarding: 128 arbitrary characters
        // parsed into a garbage address and were saved over the user's real
        // identity, on the one flow whose job is to restore it.
        val real = keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32))
        keyManager.save(real)

        assertThrows(IllegalArgumentException::class.java) {
            keyManager.importFromPhraseOrHex("not-a-key-".repeat(12) + "12345678")
        }

        assertSameKeys(real, keyManager.load())
    }

    @Test
    fun `a non-hex import is rejected before anything is written at all`() {
        assertThrows(IllegalArgumentException::class.java) {
            keyManager.importFromPhraseOrHex("z".repeat(128))
        }

        assertNull("a refused import must not create an identity either", keyManager.load())
    }

    @Test
    fun `a stored identity that is not readable hex reads as absent, not as a garbage one`() {
        // Only reachable by corrupting the store from outside — save() writes
        // toHex() output — but it is the branch the strict parse creates, and
        // "no identity" is the safe reading of it.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("connectonion_keys_secure_standard", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("address", "0x" + "bb".repeat(32))
            .putString("public_key", "bb".repeat(32))
            .putString("private_key", "z".repeat(128))
            .commit()

        assertNull(keyManager.load())
    }

    @Test
    fun `exportBackup falls back to LegacyHexKey when no mnemonic was ever saved`() {
        val hex = "56".repeat(32) + "78".repeat(32)
        keyManager.importFromPhraseOrHex(hex)

        val backup = keyManager.exportBackup()

        assertTrue(backup is KeyManager.BackupExport.LegacyHexKey)
        assertEquals(
            (backup as KeyManager.BackupExport.LegacyHexKey).privateKeyHex,
            keyManager.load()?.privateKey?.let { bytes -> bytes.joinToString("") { "%02x".format(it) } }
        )
    }
}
