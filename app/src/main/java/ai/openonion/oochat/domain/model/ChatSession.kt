package ai.openonion.oochat.domain.model

data class ChatSession(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val lastMessagePreview: String? = null
)
