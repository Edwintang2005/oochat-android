package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.AgentProfile

/**
 * Repository interface for agent discovery.
 *
 * Provides abstraction over agent discovery operations.
 * UI must NOT access discovery logic directly - only through this interface.
 */
interface AgentDiscoveryRepository {

    /**
     * Discover agents from a relay server.
     */
    suspend fun discoverFromRelay(relayUrl: String): List<AgentProfile>
}
