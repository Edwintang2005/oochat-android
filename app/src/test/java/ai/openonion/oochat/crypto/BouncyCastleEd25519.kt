package ai.openonion.oochat.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * RFC 8032 Ed25519 on the host JVM, standing in for libsodium's `.so` — which
 * a JVM cannot load — so [KeyManager]'s mnemonic paths can run in unit tests.
 *
 * Only a stand-in if it computes exactly what libsodium does. IdentityVectorsTest
 * holds it to keys and signatures libsodium itself produced, so a divergence
 * fails there before it can quietly make every other identity test agree with
 * the wrong answer.
 */
internal class BouncyCastleEd25519 : Ed25519 {

    override fun keypair(): Pair<ByteArray, ByteArray> =
        keypairFromSeed(ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) })

    override fun keypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == SEED_BYTES) { "seed must be $SEED_BYTES bytes, got ${seed.size}" }
        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        // libsodium's packing: the seed, then the public key it derives.
        return publicKey to (seed + publicKey)
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(secretKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private companion object {
        const val SEED_BYTES = 32
    }
}
