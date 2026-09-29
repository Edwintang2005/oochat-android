package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.ConnectionConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing server connection configuration.
 *
 * Stores the app's current single-server configuration.
 * Interface allows future extension for multiple server profiles
 * without major refactoring.
 *
 * Implementation should use secure storage (EncryptedSharedPreferences)
 * for sensitive data like API keys.
 */
interface ConnectionConfigRepository {

    /**
     * Get the current connection configuration.
     * Returns null if no configuration exists.
     */
    suspend fun getConfig(): ConnectionConfig?

    /**
     * Observe the current connection configuration.
     * Emits null if no configuration exists.
     */
    fun observeConfig(): Flow<ConnectionConfig?>

    /**
     * Save a connection configuration.
     * Overwrites any existing configuration.
     */
    suspend fun saveConfig(config: ConnectionConfig)

    /**
     * Delete the current connection configuration.
     */
    suspend fun deleteConfig()

    /**
     * Check if a connection configuration exists.
     */
    suspend fun hasConfig(): Boolean

    /**
     * Update the last connected timestamp.
     */
    suspend fun updateLastConnected(timestamp: Long = System.currentTimeMillis())

    /**
     * Update the agent address for the current configuration.
     */
    suspend fun updateAgentAddress(address: String?)
}
