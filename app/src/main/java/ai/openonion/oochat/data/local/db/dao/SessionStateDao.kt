package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.SessionStateEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for protocol-layer session state, one row per local conversation —
 * see [SessionStateEntity].
 */
@Dao
interface SessionStateDao {

    @Query("SELECT * FROM session_states WHERE conversation_id = :conversationId")
    suspend fun getSession(conversationId: String): SessionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: SessionStateEntity)

    @Query("DELETE FROM session_states WHERE conversation_id = :conversationId")
    suspend fun deleteSession(conversationId: String)

    /**
     * Drop rows whose conversation no longer exists — deleted, or a new one
     * the user abandoned before writing to it. Takes the place of the
     * CASCADE this table used to carry; see [SessionStateEntity].
     */
    @Query("DELETE FROM session_states WHERE conversation_id NOT IN (SELECT id FROM chat_sessions)")
    suspend fun deleteOrphaned(): Int
}
