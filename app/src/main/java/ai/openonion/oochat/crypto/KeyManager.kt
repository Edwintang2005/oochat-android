package ai.openonion.oochat.crypto

import ai.openonion.oochat.data.local.SafePreferencesWrapper
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.Context
import android.content.SharedPreferences
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Ed25519 key management for agent authentication.
 * Handles key generation, storage, and message signing.
 *
 * Uses EncryptedSharedPreferences for secure key storage.
 *
 * Implements [IdentityManager] so [ai.openonion.oochat.ui.settings.SettingsViewModel]
 * can depend on the narrower interface and be unit-tested with an in-memory fake.
 */
class KeyManager(private val context: Context) : IdentityManager {
    // by lazy (not eager) so constructing KeyManager doesn't unconditionally
    // load the native libsodium .so — only generate()/sign() (which
    // actually need it) trigger the load. This lets canonicalJson(), which
    // touches neither sodium nor prefs, be exercised in a plain JVM unit
    // test (see KeyManagerTest) without an Android device/emulator.
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    // Routed through SafePreferencesWrapper (already used by every other
    // encrypted-storage class in the app) instead of calling
    // EncryptedSharedPreferences.create() directly: besides the fallback
    // behavior matching the rest of the app, this is what makes load()/
    // save()/loadOrGenerate() (existing-key path) testable under Robolectric
    // — Robolectric has no AndroidKeyStore shadow, so the encrypted path
    // throws, and SafePreferencesWrapper's own try/catch falls back to a
    // plain SharedPreferences that Robolectric *does* shadow correctly.
    private val prefs: SharedPreferences by lazy {
        SafePreferencesWrapper(context, PREFS_NAME).getPrefs()
    }

    /**
     * Opens the identity store if it isn't open yet, so its encryption status
     * is known without waiting for the first key read.
     *
     * [prefs] is `by lazy`, so a store that fell back to plaintext reports
     * nothing at all until something touches it. The Settings warning must not
     * depend on that having happened — an unread store is not a secure one.
     */
    fun probeStorage() {
        prefs
    }

    data class AddressData(
        val address: String,
        val shortAddress: String,
        val publicKey: ByteArray,
        val privateKey: ByteArray
    )

    /** A freshly generated identity paired with the recovery phrase that derives it. */
    data class MnemonicKeys(val addressData: AddressData, val mnemonic: String)

    /**
     * What "Backup Seed Phrase" shows: identities generated (or imported) via
     * a mnemonic show the 12 words; identities that predate this feature, or
     * were imported via a raw hex key, have no mnemonic to show and fall
     * back to the legacy hex private key instead.
     */
    sealed class BackupExport {
        data class Phrase(val mnemonic: String) : BackupExport()
        data class LegacyHexKey(val privateKeyHex: String) : BackupExport()
    }

    /**
     * Generate a new Ed25519 keypair.
     */
    fun generate(): AddressData {
        val keyPair = sodium.cryptoSignKeypair()
        val publicKey = keyPair.publicKey.asBytes
        val privateKey = keyPair.secretKey.asBytes

        val address = "0x" + publicKey.toHex()
        val shortAddress = "${address.take(6)}...${address.takeLast(4)}"

        return AddressData(
            address = address,
            shortAddress = shortAddress,
            publicKey = publicKey,
            privateKey = privateKey
        )
    }

    /**
     * Load existing keys from encrypted SharedPreferences.
     */
    /**
     * The stored identity, or null when there isn't a readable one. Unparseable
     * stored bytes count as "none": [save] only ever writes [toHex] output, so
     * this can only be a corrupted store — and treating it as absent lands on
     * the same fresh-identity path a first launch takes, rather than
     * authenticating as whatever garbage the old lenient parse produced.
     */
    fun load(): AddressData? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val publicKeyHex = prefs.getString(KEY_PUBLIC, null) ?: return null
        val privateKeyHex = prefs.getString(KEY_PRIVATE, null) ?: return null

        val publicKey = runCatching { publicKeyHex.hexToBytes() }.getOrNull()
        val privateKey = runCatching { privateKeyHex.hexToBytes() }.getOrNull()
        if (publicKey == null || privateKey == null) {
            FileLogger.e(LogTags.ACCOUNT, "Stored identity is not readable hex — treating it as absent")
            return null
        }
        val shortAddress = "${address.take(6)}...${address.takeLast(4)}"

        return AddressData(
            address = address,
            shortAddress = shortAddress,
            publicKey = publicKey,
            privateKey = privateKey
        )
    }

    /**
     * Save keys to encrypted SharedPreferences.
     */
    fun save(keys: AddressData) {
        prefs.edit()
            .putString(KEY_ADDRESS, keys.address)
            .putString(KEY_PUBLIC, keys.publicKey.toHex())
            .putString(KEY_PRIVATE, keys.privateKey.toHex())
            .apply()
    }

    /**
     * Load existing keys, or provision a brand-new identity backed by a
     * fresh mnemonic so "Backup Seed Phrase" has something to show without
     * requiring the user to go through "Reset Identity" first.
     */
    override fun loadOrGenerate(): AddressData {
        return load() ?: generateWithMnemonic().also {
            save(it.addressData)
            saveMnemonic(it.mnemonic)
        }.addressData
    }

    /**
     * Derive an Ed25519 keypair from a 32-byte seed — the standard,
     * libsodium/tweetnacl-compatible Ed25519 seed expansion (RFC 8032), so a
     * keypair derived here from a given seed is byte-identical to one
     * derived from the same seed by the web client's tweetnacl.
     */
    private fun keysFromSeed(seed: ByteArray): AddressData {
        val publicKey = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)) {
            "Failed to derive Ed25519 keypair from seed"
        }
        val address = "0x" + publicKey.toHex()
        return AddressData(
            address = address,
            shortAddress = "${address.take(6)}...${address.takeLast(4)}",
            publicKey = publicKey,
            privateKey = secretKey
        )
    }

    /**
     * Generate a brand-new identity backed by a fresh 12-word BIP39
     * mnemonic. Matches the web client's `generateWithMnemonic()`
     * (`bip39.generateMnemonic()` → `mnemonicToSeedSync(mnemonic).slice(0,
     * 32)` → `nacl.sign.keyPair.fromSeed(seed)`) exactly: same wordlist,
     * same PBKDF2-HMAC-SHA512 seed derivation, same Ed25519 seed expansion —
     * so the resulting address/keys are identical to what the web client
     * would derive from the same phrase.
     */
    fun generateWithMnemonic(): MnemonicKeys {
        val mnemonicCode = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_12)
        val mnemonic = String(mnemonicCode.chars)
        val seed = mnemonicCode.toSeed().copyOfRange(0, 32)
        return MnemonicKeys(keysFromSeed(seed), mnemonic)
    }

    /**
     * Derive the identity for an existing, already-known mnemonic phrase
     * (import / restore). Throws [Mnemonics.ChecksumException],
     * [Mnemonics.WordCountException], or [Mnemonics.InvalidWordException]
     * (all `cash.z.ecc.android.bip39.Mnemonics.*`) if the phrase is invalid.
     */
    fun keysFromMnemonic(mnemonic: String): AddressData {
        val mnemonicCode = Mnemonics.MnemonicCode(mnemonic.trim().lowercase())
        mnemonicCode.validate()
        val seed = mnemonicCode.toSeed().copyOfRange(0, 32)
        return keysFromSeed(seed)
    }

    /**
     * Derive the identity for a raw 64-byte Ed25519 secret key given as 128
     * hex characters (optionally "0x"-prefixed) — the legacy/no-mnemonic
     * import path, matching the web client's `importKey()` hex fallback.
     * The public key is the secret key's last 32 bytes, per libsodium's
     * (and tweetnacl's identical) packed secret-key convention.
     */
    fun keysFromHex(hex: String): AddressData {
        val cleaned = hex.trim().removePrefix("0x")
        require(cleaned.length == 128) { "Invalid key length — expected 128 hex characters" }
        require(cleaned.all { it.isHexDigit() }) {
            "Invalid private key — expected 128 hexadecimal characters (0-9, a-f)"
        }
        val privateKey = cleaned.hexToBytes()
        val publicKey = privateKey.copyOfRange(32, 64)
        val address = "0x" + publicKey.toHex()
        return AddressData(
            address = address,
            shortAddress = "${address.take(6)}...${address.takeLast(4)}",
            publicKey = publicKey,
            privateKey = privateKey
        )
    }

    /**
     * Overwrites the current identity with a brand-new mnemonic-backed one
     * — used by "Reset Identity". Returns the new keys/phrase directly so
     * the caller can surface the phrase for immediate backup before it's
     * "lost" the way generating it silently would risk.
     */
    override fun resetIdentity(): MnemonicKeys {
        val fresh = generateWithMnemonic()
        save(fresh.addressData)
        saveMnemonic(fresh.mnemonic)
        return fresh
    }

    /**
     * Overwrites the current identity from user-supplied input — either a
     * 12/24-word recovery phrase or a raw hex private key, matching the web
     * client's `importKey()` branching exactly (word count 12 or 24 →
     * mnemonic path; otherwise treated as hex).
     */
    override fun importFromPhraseOrHex(input: String): AddressData {
        val trimmed = input.trim().lowercase()
        val wordCount = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return if (wordCount == 12 || wordCount == 24) {
            val keys = keysFromMnemonic(trimmed)
            save(keys)
            saveMnemonic(trimmed)
            keys
        } else {
            val keys = keysFromHex(trimmed)
            save(keys)
            clearMnemonic()
            keys
        }
    }

    /** What to show in "Backup Seed Phrase" — see [BackupExport]. */
    override fun exportBackup(): BackupExport {
        val mnemonic = loadMnemonic()
        return if (mnemonic != null) {
            BackupExport.Phrase(mnemonic)
        } else {
            val keys = load() ?: loadOrGenerate()
            BackupExport.LegacyHexKey(keys.privateKey.toHex())
        }
    }

    private fun saveMnemonic(mnemonic: String) {
        prefs.edit().putString(KEY_MNEMONIC, mnemonic).apply()
    }

    private fun loadMnemonic(): String? = prefs.getString(KEY_MNEMONIC, null)

    private fun clearMnemonic() {
        prefs.edit().remove(KEY_MNEMONIC).apply()
    }

    /**
     * Sign a message with Ed25519.
     * Returns hex-encoded signature.
     */
    fun sign(keys: AddressData, message: String): String {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val signatureBytes = ByteArray(Sign.ED25519_BYTES)

        sodium.cryptoSignDetached(
            signatureBytes,
            messageBytes,
            messageBytes.size.toLong(),
            keys.privateKey
        )

        return signatureBytes.toHex()
    }

    /**
     * Create canonical JSON with sorted keys for consistent signatures.
     * Uses kotlinx.serialization for proper JSON encoding (matches Web/Server behavior).
     */
    fun canonicalJson(map: Map<String, Any>): String {
        val sortedKeys = map.keys.sorted()
        val jsonObject = buildJsonObject {
            for (key in sortedKeys) {
                put(key, toJsonValue(map[key]))
            }
        }
        return Json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /**
     * Convert a Kotlin value to a kotlinx.serialization JsonElement.
     * Ensures proper JSON encoding for all types.
     */
    private fun toJsonValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value.toDouble())
            is Double -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toDouble())
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                buildJsonObject {
                    for ((k, v) in (value as Map<String, Any>).entries.sortedBy { it.key }) {
                        put(k, toJsonValue(v))
                    }
                }
            }
            is List<*> -> buildJsonArray {
                for (item in value) {
                    add(toJsonValue(item))
                }
            }
            is Array<*> -> buildJsonArray {
                for (item in value) {
                    add(toJsonValue(item))
                }
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun Char.isHexDigit(): Boolean = Character.digit(this, 16) >= 0

    /**
     * Throws rather than folding a non-hex character in as -1 — the silent
     * path that let 128 arbitrary characters parse into a plausible-looking
     * identity. Callers taking user input validate ahead of this
     * ([keysFromHex]) so the failure carries a message worth showing.
     */
    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val hi = Character.digit(this[i], 16)
            val lo = Character.digit(this[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Not a hexadecimal string" }
            data[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return data
    }

    companion object {
        /**
         * The identity store's own file. Internal rather than private because
         * the encryption status of *this* file is what the Settings warning
         * has to report on — it is the one holding the Ed25519 private key and
         * the recovery phrase, and it degrades independently of the other three
         * stores (see [SafePreferencesWrapper.degradedStores]).
         */
        internal const val PREFS_NAME = "connectonion_keys_secure"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_MNEMONIC = "mnemonic"
    }
}
