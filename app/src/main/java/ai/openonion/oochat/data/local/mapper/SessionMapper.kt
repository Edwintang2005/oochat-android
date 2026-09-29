package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.domain.model.ChatSession

object SessionMapper {

    fun toDomain(entity: ChatSessionEntity): ChatSession {
        return ChatSession(
            id = entity.id,
            agentId = entity.agentId,
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            messageCount = entity.messageCount,
            lastMessagePreview = entity.lastMessagePreview
        )
    }

    fun toEntity(domain: ChatSession): ChatSessionEntity {
        return ChatSessionEntity(
            id = domain.id,
            agentId = domain.agentId,
            title = domain.title,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            messageCount = domain.messageCount,
            lastMessagePreview = domain.lastMessagePreview
        )
    }

    fun toDomainList(entities: List<ChatSessionEntity>): List<ChatSession> {
        return entities.map { toDomain(it) }
    }
}
