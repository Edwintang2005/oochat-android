package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.dao.SessionDao
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.mapper.SessionMapper
import ai.openonion.oochat.domain.model.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of SessionRepository.
 *
 * Uses Room database for persistence.
 * Follows single source of truth principle.
 */
class SessionRepositoryImpl(
    private val sessionDao: SessionDao
) : SessionRepository {

    override fun getAllSessions(): Flow<List<ChatSession>> {
        return sessionDao.getAllSessions().map { entities ->
            SessionMapper.toDomainList(entities)
        }
    }

    override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> {
        return sessionDao.getSessionsByAgent(agentId).map { entities ->
            SessionMapper.toDomainList(entities)
        }
    }

    override suspend fun getSessionById(id: String): ChatSession? {
        return sessionDao.getSessionById(id)?.let { entity ->
            SessionMapper.toDomain(entity)
        }
    }

    override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = id,
            agentId = agentId,
            title = title,
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            lastMessagePreview = null
        )
        sessionDao.insertSession(session)
        return SessionMapper.toDomain(session)
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
    }

    override suspend fun renameSession(sessionId: String, newTitle: String) {
        sessionDao.updateTitle(sessionId, newTitle, System.currentTimeMillis())
    }

    override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
        sessionDao.updateMessageInfo(sessionId, count, preview, System.currentTimeMillis())
    }
}
