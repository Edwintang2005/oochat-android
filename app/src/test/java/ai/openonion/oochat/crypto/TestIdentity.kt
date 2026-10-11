package ai.openonion.oochat.crypto

import android.app.Application

/**
 * Real Ed25519 identities for tests, derived by [BouncyCastleEd25519].
 *
 * [KeyManager.keysFromHex] refuses a key whose second half is not the public
 * key its first half derives, so a made-up key like `"aa" * 32 + "bb" * 32` no
 * longer imports. These are what libsodium would derive from the same seed.
 */
internal object TestIdentity {

    /** A 128-character hex secret key: a seed of [fill] bytes, then its public key. */
    fun hexKey(fill: Int = 0x01): String {
        val (_, secretKey) = BouncyCastleEd25519().keypairFromSeed(ByteArray(32) { fill.toByte() })
        return secretKey.joinToString("") { "%02x".format(it) }
    }

    /** The identity [hexKey] imports as. */
    fun keys(fill: Int = 0x01): KeyManager.AddressData =
        KeyManager(Application(), BouncyCastleEd25519()).keysFromHex(hexKey(fill))
}
