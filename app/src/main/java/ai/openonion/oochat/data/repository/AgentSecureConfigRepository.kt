package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.SafePreferencesWrapper
import android.content.Context

/**
 * Secure storage for agent API keys.
 *
 * Interface (like every other repo in the codebase) so AgentRepositoryImpl
 * can be tested with a fake instead of needing a real Context/Keystore.
 */
interface AgentSecureConfigRepository {
    fun getApiKey(agentId: String): String?

    /** Pass null to remove the stored key. */
    fun saveApiKey(agentId: String, apiKey: String?)

    fun deleteApiKey(agentId: String)
}

/** Backed by EncryptedSharedPreferences — keys never touch Room/plaintext SQLite. */
class AgentSecureConfigRepositoryImpl(context: Context) : AgentSecureConfigRepository {

    private val safePrefs = SafePreferencesWrapper(context, PREFS_NAME)
    private val prefs = safePrefs.getPrefs()

    override fun getApiKey(agentId: String): String? {
        return prefs.getString(buildKey(agentId), null)
    }

    override fun saveApiKey(agentId: String, apiKey: String?) {
        val key = buildKey(agentId)
        if (apiKey != null) {
            prefs.edit().putString(key, apiKey).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    override fun deleteApiKey(agentId: String) {
        prefs.edit().remove(buildKey(agentId)).apply()
    }

    private fun buildKey(agentId: String): String = "${KEY_PREFIX}$agentId"

    companion object {
        private const val PREFS_NAME = "connectonion_agent_keys"
        private const val KEY_PREFIX = "agent_api_key_"
    }
}
