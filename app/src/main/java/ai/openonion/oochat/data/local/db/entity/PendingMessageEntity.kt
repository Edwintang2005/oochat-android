package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A message queued while the connection wasn't ready to send it. Persisted
 * rather than held only in memory so a queued send survives process death,
 * drained by [ai.openonion.oochat.data.local.RoomPendingMessageSink]
 * once the connection is ready again.
 */
@Entity(
    tableName = "pending_messages",
    indices = [Index(value = ["agent_address"])]
)
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "agent_address") val agentAddress: String,
    val prompt: String,
    @ColumnInfo(name = "images_json") val imagesJson: String?,
    /** JSON-encoded List<FileAttachment> (wire shape: name+base64 data). Added in MIGRATION_8_9. */
    @ColumnInfo(name = "files_json") val filesJson: String? = null,
    /**
     * The server session this was written in, so it is not flushed into
     * whichever conversation the socket happens to be on when it reconnects.
     * Null for a message queued before any session existed. Added in
     * MIGRATION_11_12.
     */
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
