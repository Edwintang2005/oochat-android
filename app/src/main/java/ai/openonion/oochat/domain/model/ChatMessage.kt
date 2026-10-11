package ai.openonion.oochat.domain.model

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val model: String? = null,
    val durationMs: Long? = null,
    /** Local file:// paths (USER) or remote URLs/data URLs (ASSISTANT). */
    val images: List<String>? = null,
    /** Non-image file attachments (USER only). See [ChatFileAttachment]. */
    val files: List<ChatFileAttachment>? = null,
    /**
     * Non-null for a transcript row (tool call, thinking, eval, …) rather than
     * plain chat text. [payload] then holds its JSON; [content] is empty. See
     * [ai.openonion.oochat.data.local.mapper.TranscriptItemPayload].
     */
    val itemType: String? = null,
    val payload: String? = null,
    /**
     * USER only: the message did not get through, or might not have, so it
     * comes back as failed and offers a resend. See UserMessageState.FAILED.
     */
    val failedToSend: Boolean = false
)

enum class Role {
    USER,
    ASSISTANT,
    SYSTEM
}
