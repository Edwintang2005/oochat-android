package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.dao.MessageDao
import ai.openonion.oochat.data.local.mapper.MessageMapper
import ai.openonion.oochat.domain.model.ChatMessage

/**
 * Implementation of MessageRepository.
 *
 * Uses Room database for persistence.
 * Follows single source of truth principle.
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao
) : MessageRepository {

    override suspend fun getMessagesListBySession(sessionId: String): List<ChatMessage> {
        return MessageMapper.toDomainList(
            messageDao.getMessagesListBySession(sessionId)
        )
    }

    override suspend fun getMessagesPageBySession(sessionId: String, limit: Int, offset: Int): List<ChatMessage> {
        return MessageMapper.toDomainList(
            messageDao.getMessagesPageBySession(sessionId, limit, offset)
        )
    }

    override suspend fun createMessage(message: ChatMessage) {
        val entity = MessageMapper.toEntity(message)
        messageDao.insertMessage(entity)
    }

    override suspend fun deleteMessagesBySession(sessionId: String) {
        messageDao.deleteMessagesBySession(sessionId)
    }

    override suspend fun getMessageCount(sessionId: String): Int {
        return messageDao.getMessageCount(sessionId)
    }

    override suspend fun existsById(id: String): Boolean {
        return messageDao.existsById(id)
    }

    override suspend fun getOwningSessionId(id: String): String? {
        return messageDao.getSessionIdById(id)
    }

    override suspend fun getSessionIdByUserContent(content: String): String? {
        return messageDao.getSessionIdByUserContent(content)
    }
}
