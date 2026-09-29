package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.ChatMessage

/**
 * Repository interface for chat messages.
 *
 * Provides abstraction over message data access.
 * UI must NOT access database directly - only through this interface.
 */
interface MessageRepository {

    /**
     * Get all messages for a session as a list (for non-reactive use).
     */
    suspend fun getMessagesListBySession(sessionId: String): List<ChatMessage>

    /**
     * One page of a conversation, oldest-first, [offset] rows in from its
     * start — how the chat screen loads only the newest stretch of a long
     * conversation and pages older ones back in on demand.
     *
     * Defaulted so in-memory implementations (tests, fakes) inherit the same
     * semantics for free; the Room-backed one overrides it with a real
     * LIMIT/OFFSET so the rows never leave SQLite in the first place.
     */
    suspend fun getMessagesPageBySession(sessionId: String, limit: Int, offset: Int): List<ChatMessage> =
        getMessagesListBySession(sessionId).drop(offset).take(limit)

    /**
     * Add a new message.
     */
    suspend fun createMessage(message: ChatMessage)

    /**
     * Delete all messages for a session.
     */
    suspend fun deleteMessagesBySession(sessionId: String)

    /**
     * Get message count for a session.
     */
    suspend fun getMessageCount(sessionId: String): Int

    /** Whether a message with this id has already been persisted. */
    suspend fun existsById(id: String): Boolean

    /**
     * The conversation a persisted message belongs to, or null if this id
     * has never been written.
     *
     * A reply can still arrive for a conversation the user has switched away
     * from, so an id Room already files elsewhere is what tells "already
     * someone else's" apart from "new".
     */
    suspend fun getOwningSessionId(id: String): String?

    /**
     * The conversation that sent [content] as an outgoing message, or null if
     * no local row matches. Used to attribute a replayed assistant reply to
     * the conversation whose question it answers.
     */
    suspend fun getSessionIdByUserContent(content: String): String?
}
