package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.domain.model.AgentProfile

/**
 * Mapper for AgentEntity to AgentProfile conversion.
 *
 * Note: apiKey is intentionally NOT mapped between entity and domain.
 * API keys are stored securely in EncryptedSharedPreferences via
 * AgentSecureConfigRepository, never in Room plaintext.
 */
@Suppress("DEPRECATION")
object AgentMapper {

    fun toDomain(entity: AgentEntity): AgentProfile {
        return AgentProfile(
            id = entity.id,
            address = entity.address,
            name = entity.name,
            description = entity.description,
            serverUrl = entity.serverUrl,
            apiKey = null, // Stored in EncryptedSharedPreferences
            avatarUrl = entity.avatarUrl,
            createdAt = entity.createdAt,
            lastConnectedAt = entity.lastConnectedAt,
            isActive = entity.isActive,
            connectionMode = entity.connectionMode,
            position = entity.position
        )
    }

    fun toEntity(domain: AgentProfile): AgentEntity {
        return AgentEntity(
            id = domain.id,
            address = domain.address,
            name = domain.name,
            description = domain.description,
            serverUrl = domain.serverUrl,
            apiKey = null, // Stored in EncryptedSharedPreferences
            avatarUrl = domain.avatarUrl,
            createdAt = domain.createdAt,
            lastConnectedAt = domain.lastConnectedAt,
            isActive = domain.isActive,
            connectionMode = domain.connectionMode,
            position = domain.position
        )
    }

    fun toDomainList(entities: List<AgentEntity>): List<AgentProfile> {
        return entities.map { toDomain(it) }
    }
}
