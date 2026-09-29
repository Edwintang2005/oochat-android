package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.protocol.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Suppresses session reads when the Memory toggle is off, so a disabled toggle starts each connection fresh without deleting the saved session (re-enabling resumes where it left off). Writes/deletes always pass through.
 */
class MemoryGatedSessionStore(
    private val delegate: SessionStore,
    private val memoryEnabled: Flow<Boolean>
) : SessionStore {

    override suspend fun saveSession(conversationId: String, session: SessionState) {
        delegate.saveSession(conversationId, session)
    }

    override suspend fun getSession(conversationId: String): SessionState? {
        return if (memoryEnabled.first()) delegate.getSession(conversationId) else null
    }

    override suspend fun deleteOrphanedSessions(): Int = delegate.deleteOrphanedSessions()

    override suspend fun deleteSessionByConversation(conversationId: String) {
        delegate.deleteSessionByConversation(conversationId)
    }
}
