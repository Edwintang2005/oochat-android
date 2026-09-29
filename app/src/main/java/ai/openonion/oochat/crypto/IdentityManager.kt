package ai.openonion.oochat.crypto

/**
 * The subset of [KeyManager] the Settings surface needs — split into an
 * interface for the same reason [ai.openonion.oochat.data.local.AppSettings]
 * is one: so [ai.openonion.oochat.ui.settings.SettingsViewModel] can be
 * unit-tested with an in-memory fake. The real implementations of these
 * operations need the native libsodium binary and Android-backed
 * SharedPreferences, neither of which exists on a plain JVM (see
 * KeyManagerTest / KeyManagerPersistenceTest for the same constraint).
 *
 * These are the security-sensitive Ed25519/BIP39 identity operations; every
 * caller that *changes* the identity (reset/import) must also invalidate
 * [ai.openonion.oochat.data.repository.AccountRepository]'s cached
 * auth state — SettingsViewModel owns that ordering.
 */
interface IdentityManager {
    /** See [KeyManager.loadOrGenerate]. */
    fun loadOrGenerate(): KeyManager.AddressData

    /** See [KeyManager.resetIdentity]. */
    fun resetIdentity(): KeyManager.MnemonicKeys

    /** See [KeyManager.importFromPhraseOrHex]. */
    fun importFromPhraseOrHex(input: String): KeyManager.AddressData

    /** See [KeyManager.exportBackup]. */
    fun exportBackup(): KeyManager.BackupExport
}
