package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-disk shape of one [ChatFileAttachment] — a separate DTO (rather than
 * marking the domain class itself `@Serializable`) for the same reason
 * [ai.openonion.oochat.data.local.mapper.TranscriptItemPayload] isn't
 * just `ChatItem`: keeps kotlinx.serialization wiring out of `domain/`.
 */
@Serializable
private data class FileAttachmentDto(val name: String, val path: String)

object MessageMapper {

    // No TypeConverter precedent exists in this schema yet, and images/files
    // are the only List columns so far — a JSON-encoded TEXT column is
    // simpler than introducing that machinery for two fields.
    private val json = Json { ignoreUnknownKeys = true }

    fun toDomain(entity: ChatMessageEntity): ChatMessage {
        return ChatMessage(
            id = entity.id,
            sessionId = entity.sessionId,
            role = Role.valueOf(entity.role),
            content = entity.content,
            timestamp = entity.timestamp,
            model = entity.model,
            durationMs = entity.durationMs,
            images = entity.images?.let { json.decodeFromString<List<String>>(it) },
            files = decodeFiles(entity.files),
            itemType = entity.itemType,
            payload = entity.payload
        )
    }

    fun toEntity(domain: ChatMessage): ChatMessageEntity {
        return ChatMessageEntity(
            id = domain.id,
            sessionId = domain.sessionId,
            role = domain.role.name,
            content = domain.content,
            timestamp = domain.timestamp,
            model = domain.model,
            durationMs = domain.durationMs,
            images = domain.images?.let { json.encodeToString(it) },
            files = encodeFiles(domain.files),
            itemType = domain.itemType,
            payload = domain.payload
        )
    }

    fun toDomainList(entities: List<ChatMessageEntity>): List<ChatMessage> {
        return entities.map { toDomain(it) }
    }

    /** Shared with [ai.openonion.oochat.domain.usecase.PersistenceTransaction], which writes [ChatMessageEntity.files] directly rather than through [toEntity]. */
    fun encodeFiles(files: List<ChatFileAttachment>?): String? =
        files?.let { json.encodeToString(it.map { f -> FileAttachmentDto(f.name, f.path) }) }

    fun decodeFiles(raw: String?): List<ChatFileAttachment>? =
        raw?.let { json.decodeFromString<List<FileAttachmentDto>>(it) }?.map { ChatFileAttachment(it.name, it.path) }
}
