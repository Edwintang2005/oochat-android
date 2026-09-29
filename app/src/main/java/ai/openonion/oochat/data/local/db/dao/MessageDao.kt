package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesListBySession(sessionId: String): List<ChatMessageEntity>

    /**
     * One page of a conversation, oldest-first, counted from its *start*.
     *
     * Front-anchored rather than newest-first-with-a-reversed-offset because
     * live messages land at the tail: an offset measured from the newest row
     * shifts under every arriving message, which would duplicate or skip rows
     * across two page reads. Offsets from the start only move if history is
     * deleted, which no live path does.
     */
    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "ORDER BY timestamp ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getMessagesPageBySession(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    /**
     * Drops one row by id. Used to retire a turn's footer row once its reply
     * arrives and the two become a single item — see PersistenceTransaction.
     */
    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    /**
     * Whether a message with this id has already been persisted — an O(1)
     * indexed lookup on the primary key. Used to tell a genuinely new
     * agent reply apart from a `session_sync` replay of one already shown
     * (both use the same content-derived id), e.g. for haptics/sound
     * effects that must not re-fire on every replay.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * Null if the row is new. Callers re-inserting a replayed message must keep
     * this value rather than stamping `now` — the list is ordered by timestamp,
     * so restamping moves an old message to the end of the conversation.
     */
    @Query("SELECT timestamp FROM chat_messages WHERE id = :id")
    suspend fun getTimestampById(id: String): Long?

    /**
     * Which session already owns this id, or null if it hasn't been
     * persisted yet. Content-derived ids can collide across sessions (a
     * `session_sync` replay landing in a new conversation); callers use
     * this to keep the row filed under its original session instead of
     * letting REPLACE relocate it there.
     */
    @Query("SELECT session_id FROM chat_messages WHERE id = :id")
    suspend fun getSessionIdById(id: String): String?

    /**
     * The stored payload for a row, or null if it has none. A replayed turn
     * arrives without its thinking metadata, so callers keep this rather
     * than letting REPLACE null out a footer that was already recorded.
     */
    @Query("SELECT payload FROM chat_messages WHERE id = :id")
    suspend fun getPayloadById(id: String): String?

    /**
     * The conversation that sent a given outgoing message, newest first.
     *
     * A replayed assistant reply follows the question it answers, and that
     * question is a row we wrote ourselves — so the question's owner is the
     * reply's owner. Matched on content because a replay carries no ids of
     * ours.
     */
    @Query(
        "SELECT session_id FROM chat_messages WHERE role = 'USER' AND content = :content " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getSessionIdByUserContent(content: String): String?
}
