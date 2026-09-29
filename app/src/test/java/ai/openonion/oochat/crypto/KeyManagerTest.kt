package ai.openonion.oochat.crypto

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KeyManager.canonicalJson] — the only part of
 * [KeyManager]'s crypto logic this project's plain-JVM test setup can
 * actually exercise.
 *
 * [KeyManager.generate]/[KeyManager.sign] depend on `com.goterl.lazysodium`,
 * whose native `libsodium` binary is compiled per-Android-ABI and cannot be
 * loaded by a host JVM (`java.lang.UnsatisfiedLinkError`, confirmed by
 * actually running such a test against this dependency in this project —
 * not a theoretical limitation). Covering Ed25519 keypair generation and
 * signing for real would need either Robolectric with a host-native
 * libsodium build, or an instrumented test running on a device/emulator —
 * neither exists in this project yet. `sodium` was changed from an eager
 * field to `by lazy` specifically so *constructing* `KeyManager` (needed to
 * reach `canonicalJson`, which touches neither `sodium` nor `prefs`)
 * doesn't itself trigger the native load.
 *
 * `canonicalJson` is still worth testing on its own: it's the function that
 * makes Ed25519 signatures verifiable against the server's independently
 * canonicalized version of the same payload — a subtly wrong sort order or
 * number formatting here would break signature verification for every
 * request, without an obvious symptom.
 */
class KeyManagerTest {

    private val keyManager = KeyManager(Application())

    @Test
    fun `canonicalJson sorts keys alphabetically regardless of input map order`() {
        val map1 = linkedMapOf("zeta" to "1", "alpha" to "2", "mid" to "3")
        val map2 = linkedMapOf("alpha" to "2", "mid" to "3", "zeta" to "1")

        val json1 = keyManager.canonicalJson(map1)
        val json2 = keyManager.canonicalJson(map2)

        assertEquals(json1, json2)
        assertEquals("""{"alpha":"2","mid":"3","zeta":"1"}""", json1)
    }

    @Test
    fun `canonicalJson encodes nested maps with sorted keys too`() {
        val map = linkedMapOf<String, Any>(
            "outer" to linkedMapOf("z" to 1, "a" to 2)
        )

        val json = keyManager.canonicalJson(map)

        assertEquals("""{"outer":{"a":2,"z":1}}""", json)
    }

    @Test
    fun `canonicalJson encodes numeric and boolean types without quotes`() {
        val map = linkedMapOf<String, Any>(
            "timestamp" to 1234567890L,
            "enabled" to true,
            "ratio" to 0.5
        )

        val json = keyManager.canonicalJson(map)

        assertEquals("""{"enabled":true,"ratio":0.5,"timestamp":1234567890}""", json)
    }

    @Test
    fun `canonicalJson encodes lists in original order`() {
        val map = linkedMapOf<String, Any>("tags" to listOf("b", "a", "c"))

        val json = keyManager.canonicalJson(map)

        // List ORDER is preserved (only map KEYS are sorted) — canonicalization
        // is about deterministic key ordering, not reordering array contents.
        assertEquals("""{"tags":["b","a","c"]}""", json)
    }

    @Test
    fun `canonicalJson encodes null values explicitly`() {
        val map = linkedMapOf<String, Any?>("value" to null)

        @Suppress("UNCHECKED_CAST")
        val json = keyManager.canonicalJson(map as Map<String, Any>)

        assertEquals("""{"value":null}""", json)
    }

    @Test
    fun `canonicalJson produces identical output for semantically equal maps built in different insertion order`() {
        // The actual reason canonicalJson exists: two logically-identical
        // payloads built with keys inserted in a different order must
        // canonicalize identically, or a signature over one would fail to
        // verify against the other (the server canonicalizes independently
        // on its own side before checking the signature).
        val payloadA = linkedMapOf<String, Any>("to" to "0xabc", "timestamp" to 100L)
        val payloadB = linkedMapOf<String, Any>("timestamp" to 100L, "to" to "0xabc")

        assertEquals(keyManager.canonicalJson(payloadA), keyManager.canonicalJson(payloadB))
    }

    // ── keysFromHex ── no native libsodium call (pure byte slicing), so
    // this is exercisable in the same plain-JVM setup as canonicalJson.

    @Test
    fun `keysFromHex derives the address from the last 32 bytes of a 128-char hex key`() {
        val privateKeyHex = "aa".repeat(32) + "bb".repeat(32)

        val keys = keyManager.keysFromHex(privateKeyHex)

        assertEquals("0x" + "bb".repeat(32), keys.address)
        assertEquals(64, keys.privateKey.size)
        assertEquals(32, keys.publicKey.size)
    }

    @Test
    fun `keysFromHex accepts a 0x-prefixed key identically to an unprefixed one`() {
        val privateKeyHex = "cc".repeat(32) + "dd".repeat(32)

        val withPrefix = keyManager.keysFromHex("0x$privateKeyHex")
        val withoutPrefix = keyManager.keysFromHex(privateKeyHex)

        assertEquals(withoutPrefix.address, withPrefix.address)
    }

    @Test
    fun `keysFromHex rejects a key that is not exactly 128 hex characters`() {
        assertThrows(IllegalArgumentException::class.java) { keyManager.keysFromHex("ab".repeat(31)) }
        assertThrows(IllegalArgumentException::class.java) { keyManager.keysFromHex("ab".repeat(33)) }
    }

    @Test
    fun `keysFromHex rejects 128 characters that are not hex`() {
        // Character.digit returns -1 for a non-hex character, and folding that
        // into a byte produced a valid-looking identity at a garbage address —
        // silently, on a recovery flow whose whole job is to restore the right one.
        assertThrows(IllegalArgumentException::class.java) { keyManager.keysFromHex("z".repeat(128)) }
        assertThrows(IllegalArgumentException::class.java) { keyManager.keysFromHex("ab".repeat(63) + "g!") }
    }

    @Test
    fun `keysFromHex accepts upper-case hex, deriving the same address as lower case`() {
        // The charset check must not be stricter than the parse: A-F is hex,
        // and someone pasting an upper-case key deserves their own identity
        // back rather than a rejection.
        val lower = keyManager.keysFromHex("ab".repeat(64)).address

        assertEquals("0x" + "ab".repeat(32), lower)
        assertEquals(lower, keyManager.keysFromHex("AB".repeat(64)).address)
    }

    @Test
    fun `the rejection message names hexadecimal, since it is what the import sheet shows the user`() {
        // SettingsScreen rethrows this via getOrThrow() and ImportKeySheet
        // renders e.message inline, so the text is the entire explanation the
        // user gets for why their key was refused.
        val message = assertThrows(IllegalArgumentException::class.java) {
            keyManager.keysFromHex("z".repeat(128))
        }.message.orEmpty()

        assertTrue("unhelpful rejection message: $message", message.contains("hexadecimal", ignoreCase = true))
    }
}
