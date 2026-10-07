package ai.openonion.oochat.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign

/**
 * The three Ed25519 operations [KeyManager] needs, behind a seam.
 *
 * Production binds them to libsodium ([SodiumEd25519]). That binary is an
 * Android `.so`, which a host JVM cannot load, so without this seam every
 * mnemonic path in [KeyManager] — generate, restore, import, reset — was
 * untestable off a device. JVM tests substitute an RFC 8032 implementation and
 * pin it to vectors libsodium itself produced (see IdentityVectorsTest).
 *
 * Keys use libsodium's packing: a 32-byte public key, and a 64-byte secret key
 * that is the seed followed by that public key.
 */
internal interface Ed25519 {
    /** A fresh random keypair, as (publicKey, secretKey). */
    fun keypair(): Pair<ByteArray, ByteArray>

    /** The keypair RFC 8032 derives from a 32-byte [seed], as (publicKey, secretKey). */
    fun keypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray>

    /** A detached 64-byte signature over [message]. */
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray
}

/**
 * libsodium, loaded on first use rather than on construction, so building a
 * [KeyManager] never loads the native library by itself.
 */
internal class SodiumEd25519 : Ed25519 {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    override fun keypair(): Pair<ByteArray, ByteArray> {
        val keyPair = sodium.cryptoSignKeypair()
        return keyPair.publicKey.asBytes to keyPair.secretKey.asBytes
    }

    override fun keypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)) {
            "Failed to derive Ed25519 keypair from seed"
        }
        return publicKey to secretKey
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.ED25519_BYTES)
        sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)
        return signature
    }
}
