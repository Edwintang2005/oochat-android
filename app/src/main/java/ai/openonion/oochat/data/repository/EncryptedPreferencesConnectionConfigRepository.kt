package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.local.SafePreferencesWrapper
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedPreferencesConnectionConfigRepository(
    context: Context
) : ConnectionConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val safePrefs = SafePreferencesWrapper(context, PREFS_NAME)
    private val prefs: SharedPreferences = safePrefs.getPrefs()
    private val configFlow = MutableStateFlow<ConnectionConfig?>(null)

    init {
        configFlow.value = loadConfigSync()
    }

    private fun loadConfigSync(): ConnectionConfig? {
        val configJson = prefs.getString(KEY_CONFIG, null) ?: return null
        return runCatching {
            json.decodeFromString<ConnectionConfig>(configJson)
        }.getOrNull()
    }

    override suspend fun getConfig(): ConnectionConfig? {
        return withContext(Dispatchers.IO) {
            loadConfigSync()
        }
    }

    override fun observeConfig(): Flow<ConnectionConfig?> = configFlow.asStateFlow()

    override suspend fun saveConfig(config: ConnectionConfig) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_CONFIG, json.encodeToString(config))
                .apply()
            configFlow.value = config
        }
    }

    override suspend fun deleteConfig() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_CONFIG)
                .apply()
            configFlow.value = null
        }
    }

    override suspend fun hasConfig(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.contains(KEY_CONFIG)
        }
    }

    override suspend fun updateLastConnected(timestamp: Long) {
        val config = getConfig() ?: return
        saveConfig(config.copy(lastConnected = timestamp))
    }

    override suspend fun updateAgentAddress(address: String?) {
        val config = getConfig() ?: return
        saveConfig(config.copy(agentAddress = address))
    }

    companion object {
        private const val PREFS_NAME = "connectonion_config_secure"
        private const val KEY_CONFIG = "connection_config"
    }
}
