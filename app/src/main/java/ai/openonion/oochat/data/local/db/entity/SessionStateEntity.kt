package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The server-side session backing one local conversation.
 *
 * Keyed by [ChatSessionEntity.id], not by agent address: `session_id` is
 * client-chosen and the server holds no per-agent limit, so each
 * conversation owns a separate server session and inbound frames need no
 * attribution.
 *
 * Deliberately NOT foreign-keyed to `chat_sessions`. A conversation's row is
 * created lazily on its first message, but it is connected and has a session
 * well before that, so a CASCADE parent constraint rejected every save for a
 * conversation the user had not yet written to — which is most of them, and
 * the app then silently forgot the session. Rows whose conversation never
 * materialised, or was deleted, are swept by
 * [ai.openonion.oochat.data.local.db.dao.SessionStateDao.deleteOrphaned].
 */
@Entity(tableName = "session_states")
data class SessionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** Server-allocated; null until this conversation's first CONNECTED lands. */
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    @ColumnInfo(name = "turn")
    val turn: Int? = null,

    /**
     * Serialized JSON array of SessionMessage objects.
     * Stored as TEXT since Room has no native support for complex nested types.
     */
    @ColumnInfo(name = "messages_json")
    val messagesJson: String? = null,

    /**
     * Always null now — see [ai.openonion.oochat.data.protocol.SessionState].
     * The column stays because dropping it is a schema change (and a migration
     * plus a new exported schema) to reclaim nothing: SessionStoreImpl writes
     * null over any legacy value on the row's next save.
     */
    @ColumnInfo(name = "trace_json")
    val traceJson: String? = null,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)
