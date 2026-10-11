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
 * The native `com.goterl.lazysodium` binary cannot load on a host JVM
 * regardless of Robolectric (see [KeyManagerTest]'s doc comment for the
 * confirmed `UnsatisfiedLinkError`), and the hex import checks a key against
 * the public key its seed derives, so [BouncyCastleEd25519] stands in for it.
 * The mnemonic branches are covered in [KeyManagerMnemonicTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyManagerPersistenceTest {

    private val keyManager = KeyManager(ApplicationProvider.getApplicationContext<Application>(), BouncyCastleEd25519())

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
        val keys = keyManager.keysFromHex(TestIdentity.hexKey(0xaa))

        keyManager.save(keys)

        assertSameKeys(keys, keyManager.load())
    }

    @Test
    fun `save overwrites a previously saved identity`() {
        val first = keyManager.keysFromHex(TestIdentity.hexKey(0x11))
        val second = keyManager.keysFromHex(TestIdentity.hexKey(0x33))

        keyManager.save(first)
        keyManager.save(second)

        assertSameKeys(second, keyManager.load())
    }

    @Test
    fun `loadOrGenerate returns the existing identity without needing to generate one`() {
        val keys = keyManager.keysFromHex(TestIdentity.hexKey(0xcc))
        keyManager.save(keys)

        // Falling through to generateWithMnemonic() (the "no existing key"
        // path) would return a fresh identity, not the saved one.
        val result = keyManager.loadOrGenerate()

        assertSameKeys(keys, result)
    }

    @Test
    fun `importFromPhraseOrHex with a hex key saves it and is retrievable via load`() {
        val hex = TestIdentity.hexKey(0xee)

        val imported = keyManager.importFromPhraseOrHex(hex)

        assertSameKeys(imported, keyManager.load())
    }

    @Test
    fun `importFromPhraseOrHex accepts a 0x-prefixed hex key identically`() {
        val hex = TestIdentity.hexKey(0x12)

        val withPrefix = keyManager.importFromPhraseOrHex("0x$hex")

        assertEquals(withPrefix.address, keyManager.load()?.address)
    }

    @Test
    fun `a non-hex import leaves the existing identity untouched`() {
        // The failure that made this worth guarding: 128 arbitrary characters
        // parsed into a garbage address and were saved over the user's real
        // identity, on the one flow whose job is to restore it.
        val real = keyManager.keysFromHex(TestIdentity.hexKey(0xaa))
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
        val hex = TestIdentity.hexKey(0x56)
        keyManager.importFromPhraseOrHex(hex)

        val backup = keyManager.exportBackup()

        assertTrue(backup is KeyManager.BackupExport.LegacyHexKey)
        assertEquals(
            (backup as KeyManager.BackupExport.LegacyHexKey).privateKeyHex,
            keyManager.load()?.privateKey?.let { bytes -> bytes.joinToString("") { "%02x".format(it) } }
        )
    }
}
