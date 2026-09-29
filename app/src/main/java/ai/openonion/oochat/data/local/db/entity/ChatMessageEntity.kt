package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    val role: String,

    val content: String,

    val timestamp: Long,

    val model: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    /** JSON-encoded List<String> — see [ai.openonion.oochat.data.local.mapper.MessageMapper]. */
    val images: String? = null,

    /**
     * JSON-encoded List<ChatFileAttachment> (name+path pairs), USER rows
     * only — see [ai.openonion.oochat.data.local.mapper.MessageMapper].
     * Added in MIGRATION_8_9.
     */
    val files: String? = null,

    /**
     * Dead since voice became dictation into the composer rather than a
     * message of its own: nothing writes these and nothing reads them back.
     * Kept declared because dropping a column pre-SQLite-3.35 means rebuilding
     * the table, and this schema's version numbers are shared with the team
     * repo — burning one on three nullable columns is the worse trade.
     */
    @ColumnInfo(name = "voice_path")
    val voicePath: String? = null,

    @ColumnInfo(name = "voice_duration_seconds")
    val voiceDurationSeconds: Float? = null,

    @ColumnInfo(name = "voice_transcript_status")
    val voiceTranscriptStatus: String? = null,

    /**
     * Non-null only for transcript rows (tool calls, thinking, eval/intent/
     * compact, blocked tools, received files). Null for User/Agent/Turn, which
     * keep using [role]/[content] — that's what keeps the migration additive.
     */
    @ColumnInfo(name = "item_type")
    val itemType: String? = null,

    /**
     * JSON body for [itemType] rows. Opaque to SQL on purpose, so adding an item
     * kind is a code change rather than another migration.
     */
    val payload: String? = null
)
