package ai.openonion.oochat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The known answers from the JVM's IdentityVectorsTest, against the real
 * libsodium binary.
 *
 * The JVM suite runs identity code on an RFC 8032 stand-in because the `.so`
 * only loads on a device; this is the one place the production [KeyManager]
 * — `KeyManager(context)`, libsodium and all — is held to the same values. If
 * the stand-in and the phone ever disagree, this is where it shows.
 */
@RunWith(AndroidJUnit4::class)
class KeyManagerSodiumVectorsTest {

    private val keyManager = KeyManager(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun a_recovery_phrase_restores_the_address_libsodium_derives_from_it() {
        assertEquals(
            "0xc5785e1865b708938aff8161d573006496663b1aa10834e396dc566869a2c66a",
            keyManager.keysFromMnemonic(ABANDON_ABOUT).address
        )
        assertEquals(
            "0xc6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2",
            keyManager.keysFromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow").address
        )
    }

    @Test
    fun a_relay_connect_payload_carries_the_signature_the_relay_verifies() {
        val keys = keyManager.keysFromMnemonic(ABANDON_ABOUT)
        val canonical = keyManager.canonicalJson(
            linkedMapOf("to" to "0x" + "ab".repeat(32), "timestamp" to 1_760_000_000L)
        )

        assertEquals(
            "8c547c5d3abeb8b79fc0325271379ea5b745c92e1dc5b27a3fea568a44bb47fb" +
                "68ef2dbf6cfbf1282eae7dbed17132907b11184c1ada817ca27fe351d781fc0f",
            keyManager.sign(keys, canonical)
        )
    }

    private companion object {
        const val ABANDON_ABOUT =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
