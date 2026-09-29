package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.dao.AgentDao
import ai.openonion.oochat.data.local.mapper.AgentMapper
import ai.openonion.oochat.domain.model.AgentProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Implementation of AgentRepository.
 *
 * Uses Room database for persistence.
 * API keys are stored securely via AgentSecureConfigRepository
 * (EncryptedSharedPreferences), never in Room plaintext.
 *
 * Follows single source of truth principle.
 */
class AgentRepositoryImpl(
    private val agentDao: AgentDao,
    private val secureConfig: AgentSecureConfigRepository
) : AgentRepository {

    override fun getAllAgents(): Flow<List<AgentProfile>> {
        return agentDao.getAllAgents().map { entities ->
            AgentMapper.toDomainList(entities).map { injectApiKey(it) }
        }
    }

    override fun getActiveAgents(): Flow<List<AgentProfile>> {
        return agentDao.getActiveAgents().map { entities ->
            AgentMapper.toDomainList(entities).map { injectApiKey(it) }
        }
    }

    override suspend fun getAgentById(id: String): AgentProfile? {
        return agentDao.getAgentById(id)?.let { entity ->
            injectApiKey(AgentMapper.toDomain(entity))
        }
    }

    override suspend fun getAgentByAddress(address: String): AgentProfile? {
        return agentDao.getAgentByAddress(address)?.let { entity ->
            injectApiKey(AgentMapper.toDomain(entity))
        }
    }

    override suspend fun createAgent(agent: AgentProfile): AgentProfile {
        secureConfig.saveApiKey(agent.id, agent.apiKey)
        // Append at the end of the drag-to-reorder position, regardless of
        // whatever position the caller happened to construct the profile
        // with — new agents always land last, not wherever position=0 sorts to.
        val appended = agent.copy(position = (agentDao.getMaxPosition() ?: -1) + 1)
        val entity = AgentMapper.toEntity(appended)
        agentDao.insertAgent(entity)
        return appended
    }

    override suspend fun updateAgent(agent: AgentProfile) {
        secureConfig.saveApiKey(agent.id, agent.apiKey)
        val entity = AgentMapper.toEntity(agent)
        agentDao.updateAgent(entity)
    }

    override suspend fun deleteAgent(agentId: String) {
        secureConfig.deleteApiKey(agentId)
        agentDao.deleteAgentById(agentId)
    }

    override suspend fun updateLastConnected(agentId: String) {
        agentDao.updateLastConnected(agentId, System.currentTimeMillis())
    }

    override suspend fun getDefaultAgent(): AgentProfile? {
        val entities = agentDao.getActiveAgents().first()
        return entities.firstOrNull()?.let { injectApiKey(AgentMapper.toDomain(it)) }
    }

    override suspend fun reorderAgents(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            agentDao.updatePosition(id, index)
        }
    }

    private fun injectApiKey(profile: AgentProfile): AgentProfile {
        return profile.copy(apiKey = secureConfig.getApiKey(profile.id))
    }
}
