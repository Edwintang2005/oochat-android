package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.AgentProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for agent profiles.
 *
 * Provides abstraction over agent data access.
 * UI must NOT access database directly - only through this interface.
 */
interface AgentRepository {

    /**
     * Get all agents ordered by their drag-to-reorder position — the same
     * order AgentListScreen and NavDrawer both surface.
     */
    fun getAllAgents(): Flow<List<AgentProfile>>

    /**
     * Get active agents only.
     */
    fun getActiveAgents(): Flow<List<AgentProfile>>

    /**
     * Get an agent by ID.
     */
    suspend fun getAgentById(id: String): AgentProfile?

    /**
     * Get an agent by address.
     */
    suspend fun getAgentByAddress(address: String): AgentProfile?

    /**
     * Create a new agent.
     */
    suspend fun createAgent(agent: AgentProfile): AgentProfile

    /**
     * Update an existing agent.
     */
    suspend fun updateAgent(agent: AgentProfile)

    /**
     * Delete an agent by ID.
     */
    suspend fun deleteAgent(agentId: String)

    /**
     * Update last connected timestamp.
     */
    suspend fun updateLastConnected(agentId: String)

    /**
     * First active agent, or null. Fallback [DefaultAgentRepository] uses
     * when nothing's been explicitly set as default.
     */
    suspend fun getDefaultAgent(): AgentProfile?

    /**
     * Persist a new drag-to-reorder position for every agent in [orderedIds]
     * (index 0 becomes position 0, and so on) — both AgentListScreen and
     * NavDrawer read [getAllAgents] ordered by this same column, so this is
     * the single place that keeps the two screens' ordering in sync.
     */
    suspend fun reorderAgents(orderedIds: List<String>)
}
