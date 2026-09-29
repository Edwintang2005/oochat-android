package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE agent_id = :agentId ORDER BY updated_at DESC")
    fun getSessionsByAgent(agentId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSessionById(id: String)

    @Query("UPDATE chat_sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET message_count = :count, last_message_preview = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageInfo(id: String, count: Int, preview: String?, updatedAt: Long)
}
