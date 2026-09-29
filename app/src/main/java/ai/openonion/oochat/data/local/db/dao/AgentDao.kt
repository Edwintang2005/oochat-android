package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.AgentEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    /**
     * Get all agents ordered by their drag-to-reorder position — the same
     * order AgentListScreen and NavDrawer both surface.
     */
    @Query("SELECT * FROM agent_profiles ORDER BY position ASC")
    fun getAllAgents(): Flow<List<AgentEntity>>

    /**
     * Get active agents only, same position ordering as [getAllAgents].
     */
    @Query("SELECT * FROM agent_profiles WHERE is_active = 1 ORDER BY position ASC")
    fun getActiveAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agent_profiles WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Query("SELECT * FROM agent_profiles WHERE address = :address")
    suspend fun getAgentByAddress(address: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    @Query("DELETE FROM agent_profiles WHERE id = :id")
    suspend fun deleteAgentById(id: String)

    @Query("UPDATE agent_profiles SET last_connected_at = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("UPDATE agent_profiles SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)

    /**
     * Highest position currently assigned, or null if there are no agents
     * yet — used to append newly-created agents at the end of the order.
     */
    @Query("SELECT MAX(position) FROM agent_profiles")
    suspend fun getMaxPosition(): Int?
}
