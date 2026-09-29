package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.db.dao.MessageDao
import ai.openonion.oochat.data.local.db.dao.SessionDao
import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Placeholder DAOs for fake [ai.openonion.oochat.data.local.db.AppDatabase]
 * subclasses used only to satisfy [PersistenceTransaction]'s constructor when its
 * `persistMessageAtomically` is overridden to a no-op (the constructor eagerly
 * calls `messageDao()`/`sessionDao()`, so those accessors must return an object
 * rather than throw — the object's own methods are never invoked).
 */
object NoOpMessageDao : MessageDao {
    override suspend fun getMessagesListBySession(sessionId: String): List<ChatMessageEntity> = throw NotImplementedError()
    override suspend fun getMessagesPageBySession(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity> = throw NotImplementedError()
    override suspend fun insertMessage(message: ChatMessageEntity) = throw NotImplementedError()
    override suspend fun deleteMessagesBySession(sessionId: String) = throw NotImplementedError()
    override suspend fun getMessageCount(sessionId: String): Int = throw NotImplementedError()
    override suspend fun existsById(id: String): Boolean = throw NotImplementedError()
    override suspend fun getTimestampById(id: String): Long? = throw NotImplementedError()
    override suspend fun getSessionIdById(id: String): String? = throw NotImplementedError()
    override suspend fun getPayloadById(id: String): String? = throw NotImplementedError()
    override suspend fun deleteMessageById(id: String) = throw NotImplementedError()
    override suspend fun getSessionIdByUserContent(content: String): String? = throw NotImplementedError()
}

object NoOpSessionDao : SessionDao {
    override fun getAllSessions(): Flow<List<ChatSessionEntity>> = throw NotImplementedError()
    override fun getSessionsByAgent(agentId: String): Flow<List<ChatSessionEntity>> = throw NotImplementedError()
    override suspend fun getSessionById(id: String): ChatSessionEntity? = throw NotImplementedError()
    override suspend fun insertSession(session: ChatSessionEntity) = throw NotImplementedError()
    override suspend fun deleteSessionById(id: String) = throw NotImplementedError()
    override suspend fun updateTitle(id: String, title: String, updatedAt: Long) = throw NotImplementedError()
    override suspend fun updateMessageInfo(id: String, count: Int, preview: String?, updatedAt: Long) = throw NotImplementedError()
}
