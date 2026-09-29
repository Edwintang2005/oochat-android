package ai.openonion.oochat.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SafePreferencesWrapper(
    private val context: Context,
    private val prefsName: String = DEFAULT_PREFS_NAME
) {
    private val prefs: SharedPreferences
    private val encrypted: Boolean

    init {
        val (resolvedPrefs, isEncrypted) = resolvePrefs(
            createEncrypted = ::createEncryptedPrefs,
            createStandard = ::createStandardPrefs
        )
        prefs = resolvedPrefs
        encrypted = isEncrypted
        if (!isEncrypted) {
            Log.w(TAG, "Encrypted SharedPreferences failed for '$prefsName', falling back to standard storage")
            recordFallback(prefsName)
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun createStandardPrefs(): SharedPreferences {
        return context.getSharedPreferences("${prefsName}_standard", Context.MODE_PRIVATE)
    }

    fun getPrefs(): SharedPreferences = prefs

    fun isSecure(): Boolean = encrypted

    fun getStatus(): String = if (encrypted) "Encrypted (secure)" else "Standard (insecure)"

    companion object {
        private const val TAG = "SafePreferences"
        private const val DEFAULT_PREFS_NAME = "connectonion_prefs"

        /**
         * Every store that fell back to plaintext this process.
         *
         * Process-wide, not per-instance, and that is the whole point.
         * `EncryptedSharedPreferences` keeps a **separate keyset per file**, so
         * one store can degrade while the others stay encrypted — the app has
         * four, on four different files. Asking a single instance "are you
         * secure?" therefore answers a question nobody is asking. The warning
         * surface needs "is anything on this device now unencrypted", which is
         * this set. See [ai.openonion.oochat.di.AppContainer.isSecureStorage].
         */
        private val degradedStores = LinkedHashSet<String>()

        private fun recordFallback(prefsName: String) {
            synchronized(degradedStores) { degradedStores += prefsName }
        }

        /** Names of the stores currently holding their contents in plaintext. */
        fun degradedStores(): Set<String> = synchronized(degradedStores) { LinkedHashSet(degradedStores) }

        /** True when [prefsName]'s own file fell back, regardless of the others. */
        fun isStoreDegraded(prefsName: String): Boolean =
            synchronized(degradedStores) { prefsName in degradedStores }

        /** Test seam — the record outlives individual instances, so it outlives individual tests too. */
        internal fun resetDegradedStoresForTest() {
            synchronized(degradedStores) { degradedStores.clear() }
        }

        /**
         * Pure fallback logic, extracted so it's unit-testable without a real
         * Keystore (which createEncryptedPrefs needs). Tries encrypted first,
         * falls back to standard on any failure.
         */
        internal fun resolvePrefs(
            createEncrypted: () -> SharedPreferences,
            createStandard: () -> SharedPreferences
        ): Pair<SharedPreferences, Boolean> {
            return try {
                createEncrypted() to true
            } catch (e: Exception) {
                createStandard() to false
            }
        }
    }
}
