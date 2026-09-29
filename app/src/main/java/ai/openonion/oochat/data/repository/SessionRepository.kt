package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.ChatSession
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat sessions.
 *
 * Provides abstraction over session data access.
 * UI must NOT access database directly - only through this interface.
 */
interface SessionRepository {

    /**
     * Get all sessions ordered by update time (newest first).
     */
    fun getAllSessions(): Flow<List<ChatSession>>

    /**
     * Get sessions for a specific agent.
     */
    fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>>

    /**
     * Get a session by ID.
     */
    suspend fun getSessionById(id: String): ChatSession?

    /**
     * Create a new session under [id] — chosen by the caller, because it is
     * also the server session id the conversation has already been using.
     */
    suspend fun createSession(
        agentId: String,
        title: String,
        id: String = java.util.UUID.randomUUID().toString()
    ): ChatSession

    /**
     * Delete a session by ID.
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Rename a session.
     */
    suspend fun renameSession(sessionId: String, newTitle: String)

    /**
     * Update message count and preview for a session.
     */
    suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?)
}
