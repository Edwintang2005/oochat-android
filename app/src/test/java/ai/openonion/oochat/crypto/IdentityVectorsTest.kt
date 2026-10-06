package ai.openonion.oochat.crypto

import android.app.Application
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known answers for the identity: recovery phrase → seed → address, and key →
 * signature. Every expected value here came from outside this codebase —
 * RFC 8032's own test vectors, and keys and signatures produced by the
 * connectonion Python SDK's libraries (python-mnemonic + PyNaCl, i.e.
 * libsodium) — and the payload signatures were checked with the server's own
 * `connectonion.network.host.auth.verify_signature`.
 *
 * So a failure here means the app would compute a different identity or a
 * different signature from the rest of the ecosystem: a phrase that restores
 * someone else's address, or a CONNECT the relay rejects. Nothing else in the
 * suite would notice either — the connection tests sign with a fake.
 *
 * Derivation pinned is the one [KeyManager.generateWithMnemonic] documents as
 * the web client's: BIP39 seed (empty passphrase), first 32 bytes, RFC 8032
 * expansion. connectonion 1.8.8's own `address.recover()` has since moved its
 * agent keys to SLIP-0010, so the same phrase gives a different address there —
 * that is a cross-client decision, not something this test should paper over.
 */
class IdentityVectorsTest {

    private val keyManager = KeyManager(Application(), BouncyCastleEd25519())

    private data class PhraseVector(val phrase: String, val seedHex: String, val address: String)

    private val phrases = listOf(
        PhraseVector(
            phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            seedHex = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            address = "0xc5785e1865b708938aff8161d573006496663b1aa10834e396dc566869a2c66a"
        ),
        PhraseVector(
            phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow",
            seedHex = "878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe" +
                "5f377ba02bf3f8544ab800b955e51fbff09828f682052a20faa6addbbddfb096",
            address = "0xc6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2"
        ),
        PhraseVector(
            phrase = (List(23) { "abandon" } + "art").joinToString(" "),
            seedHex = "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            address = "0x1de352e44cd333672593f2334a730e180aaf290de89aa16d480de594e34e2961"
        )
    )

    // ── BIP39: phrase → seed ──────────────────────────────────────────
    //
    // kotlin-bip39 is pure Kotlin, so this half runs exactly as it does on a
    // phone — no stand-in involved.

    @Test
    fun `a recovery phrase stretches to the standard BIP39 seed`() {
        for (v in phrases) {
            val seed = Mnemonics.MnemonicCode(v.phrase).toSeed()
            assertEquals("seed for \"${v.phrase.take(24)}…\"", v.seedHex, seed.toHex())
        }
    }

    // ── the stand-in is libsodium ─────────────────────────────────────
    //
    // Everything below the seed runs on BouncyCastleEd25519. These pin it to
    // RFC 8032 section 7.1, so the rest of this file tests the app's logic and
    // not a disagreement between two Ed25519 implementations.

    @Test
    fun `RFC 8032 test 1 - empty message`() {
        val ed = BouncyCastleEd25519()
        val (publicKey, secretKey) =
            ed.keypairFromSeed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes())

        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", publicKey.toHex())
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
                "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ed.sign(ByteArray(0), secretKey).toHex()
        )
    }

    @Test
    fun `RFC 8032 test 2 - one-byte message`() {
        val ed = BouncyCastleEd25519()
        val (publicKey, secretKey) =
            ed.keypairFromSeed("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes())

        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", publicKey.toHex())
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ed.sign(byteArrayOf(0x72), secretKey).toHex()
        )
    }

    // ── phrase → address, through KeyManager ──────────────────────────

    @Test
    fun `a recovery phrase restores the same address libsodium derives from it`() {
        for (v in phrases) {
            assertEquals("address for \"${v.phrase.take(24)}…\"", v.address, keyManager.keysFromMnemonic(v.phrase).address)
        }
    }

    @Test
    fun `the secret key is packed the way libsodium packs it - seed then public key`() {
        // The hex import path and every stored identity rely on this layout:
        // keysFromHex reads the public key back out of the last 32 bytes.
        val v = phrases.first()
        val keys = keyManager.keysFromMnemonic(v.phrase)

        assertEquals(v.seedHex.take(64) + v.address.removePrefix("0x"), keys.privateKey.toHex())
        assertEquals(v.address, keyManager.keysFromHex(keys.privateKey.toHex()).address)
    }

    @Test
    fun `an address is 0x plus 64 lower-case hex characters, shortened to 6 and 4`() {
        // The same form the connectonion SDK gives an address ("0x" + hex) and
        // its short form ("0x3d40...660c" in address.py's own docstring).
        val keys = keyManager.keysFromMnemonic(phrases.first().phrase)

        assertEquals(66, keys.address.length)
        assertEquals(keys.address.lowercase(), keys.address)
        assertEquals("0xc578...c66a", keys.shortAddress)
    }

    // ── canonical payloads and their signatures ───────────────────────
    //
    // What the relay verifies is the signature over *its own* canonical
    // re-encoding of the payload it received: Python's
    // json.dumps(payload, sort_keys=True, separators=(",", ":")). The app has
    // to produce those exact bytes, then sign them with the right key.

    private val agent = "0x" + "ab".repeat(32)
    private val timestamp = 1_760_000_000L

    private data class SignedPayload(val fields: Map<String, Any>, val canonical: String, val signature: String)

    private val signedPayloads = mapOf(
        "relay CONNECT" to SignedPayload(
            linkedMapOf("to" to agent, "timestamp" to timestamp),
            """{"timestamp":1760000000,"to":"$agent"}""",
            "8c547c5d3abeb8b79fc0325271379ea5b745c92e1dc5b27a3fea568a44bb47fb" +
                "68ef2dbf6cfbf1282eae7dbed17132907b11184c1ada817ca27fe351d781fc0f"
        ),
        "direct CONNECT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp),
            """{"timestamp":1760000000}""",
            "d67c123f31435b1d55bb090e51769783f9107ce8942fbd42e34f5a2131460052" +
                "23f62d54c8dcfb906f2ec2eec67480af25310f5d61fdd1deff8aff6923f61303"
        ),
        "invite-code ONBOARD_SUBMIT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "invite_code" to "BETA2024"),
            """{"invite_code":"BETA2024","timestamp":1760000000}""",
            "39c0f5d6617720234569c30bb9162adb81c3c57d1bc9bc30ef04bee6ac6565fb" +
                "4d598be171c8f19ed9b8015b767e1013e8a2bcbc0b2f17fd668822454451af04"
        ),
        // The payment travels as a Double, so the relay re-encodes 10.0, not 10.
        "payment ONBOARD_SUBMIT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "payment" to 10.0),
            """{"payment":10.0,"timestamp":1760000000}""",
            "7c4ba5453328b4597a8f7c2529de00051373c146723754963a51e25205019070" +
                "2b0155240fe11818cef88ac2faa62d9c8fd269429a45936c1057f38eb6a79f0b"
        ),
        // Unescaped UTF-8 — the server accepts it alongside the \u-escaped form.
        "non-ASCII invite code" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "invite_code" to "邀请码"),
            """{"invite_code":"邀请码","timestamp":1760000000}""",
            "3dd98c3014d94e898e719c6c87dff8143c362ce774774af67f8532aefa9b7e39" +
                "9239db92f71b1486e703be854374d4df66a8a6aac4149a6ea6698fad30770201"
        )
    )

    @Test
    fun `signed payloads canonicalise to the bytes the relay re-encodes`() {
        for ((name, p) in signedPayloads) {
            assertEquals(name, p.canonical, keyManager.canonicalJson(p.fields))
        }
    }

    @Test
    fun `signed payloads carry the signature the relay verifies`() {
        val keys = keyManager.keysFromMnemonic(phrases.first().phrase)
        for ((name, p) in signedPayloads) {
            assertEquals(name, p.signature, keyManager.sign(keys, keyManager.canonicalJson(p.fields)))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
