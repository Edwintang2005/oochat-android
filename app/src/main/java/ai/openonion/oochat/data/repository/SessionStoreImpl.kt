package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.dao.SessionStateDao
import ai.openonion.oochat.data.local.db.entity.SessionStateEntity
import ai.openonion.oochat.data.protocol.SessionMessage
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room-backed implementation of SessionStore.
 *
 * Serializes the transcript as a JSON string in a single Room entity row.
 * `trace_json` is written null on purpose — see [SessionState] — which also
 * clears whatever an older build left in the column, since saves REPLACE.
 */
class SessionStoreImpl(
    private val sessionStateDao: SessionStateDao
) : SessionStore {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun saveSession(conversationId: String, session: SessionState) {
        runCatchingCancellable {
            val entity = SessionStateEntity(
                conversationId = conversationId,
                sessionId = session.sessionId,
                turn = session.turn,
                messagesJson = session.messages?.let { json.encodeToString(it) },
                traceJson = null,
                lastUpdated = System.currentTimeMillis()
            )
            sessionStateDao.saveSession(entity)
            FileLogger.d(LogTags.SESSION_STORE, "Saved session for conversation $conversationId: sid=${session.sessionId}, turn=${session.turn}")
        }.onFailure { e ->
            // Loud on purpose. A dropped save is the difference between
            // resuming this conversation and silently starting a new one, and
            // it used to cost exactly one swallowed line.
            FileLogger.e(
                LogTags.SESSION_STORE,
                "LOST SESSION for conversation $conversationId (sid=${session.sessionId}, " +
                    "turn=${session.turn}) — it will not resume: ${e.message}"
            )
        }
    }

    override suspend fun getSession(conversationId: String): SessionState? {
        return runCatchingCancellable {
            val entity = sessionStateDao.getSession(conversationId) ?: return@runCatchingCancellable null
            SessionState(
                sessionId = entity.sessionId,
                messages = entity.messagesJson?.let {
                    json.decodeFromString<List<SessionMessage>>(it)
                },
                turn = entity.turn
            ).also {
                FileLogger.d(LogTags.SESSION_STORE, "Restored session for conversation $conversationId: sid=${it.sessionId}, turn=${it.turn}")
            }
        }.onFailure { e ->
            FileLogger.e(LogTags.SESSION_STORE, "Failed to restore session: ${e.message}")
        }.getOrNull()
    }

    override suspend fun deleteOrphanedSessions(): Int =
        runCatchingCancellable {
            sessionStateDao.deleteOrphaned().also {
                if (it > 0) FileLogger.d(LogTags.SESSION_STORE, "Swept $it orphaned session row(s)")
            }
        }.onFailure { e -> FileLogger.e(LogTags.SESSION_STORE, "Orphan sweep failed: ${e.message}") }
            .getOrDefault(0)

    override suspend fun deleteSessionByConversation(conversationId: String) {
        runCatchingCancellable {
            sessionStateDao.deleteSession(conversationId)
            FileLogger.d(LogTags.SESSION_STORE, "Deleted session for conversation $conversationId")
        }.onFailure { e -> FileLogger.e(LogTags.SESSION_STORE, "Failed to delete session: ${e.message}") }
    }
}
