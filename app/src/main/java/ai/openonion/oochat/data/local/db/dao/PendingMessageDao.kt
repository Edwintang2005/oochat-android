package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO for the pending-message outbox. Blocking rather than `suspend`: the
 * callers in [ai.openonion.oochat.network.AgentConnection] are the
 * synchronous send/reconnect path, which already runs off the main thread
 * (Dispatchers.IO or an OkHttp listener callback), so a suspend boundary
 * would buy nothing.
 *
 * An abstract class rather than an interface so the two compound operations
 * below can carry [Transaction] — Room only wraps a concrete method, and a
 * Kotlin interface's default method doesn't reliably give it one.
 */
@Dao
abstract class PendingMessageDao {

    @Insert
    abstract fun insert(entity: PendingMessageEntity): Long

    /**
     * Every row at once. Not on the flush path — see [claimNextForAgent] for
     * why — and only safe here because migration tests write rows with no
     * attachments in them.
     */
    @Query("SELECT * FROM pending_messages WHERE agent_address = :agentAddress ORDER BY id ASC")
    abstract fun getAllForAgent(agentAddress: String): List<PendingMessageEntity>

    @Query("DELETE FROM pending_messages WHERE agent_address = :agentAddress")
    abstract fun deleteAllForAgent(agentAddress: String)

    @Query("SELECT COUNT(*) FROM pending_messages WHERE agent_address = :agentAddress")
    abstract fun countForAgent(agentAddress: String): Int

    @Query("SELECT id FROM pending_messages WHERE agent_address = :agentAddress ORDER BY id ASC")
    abstract fun idsForAgent(agentAddress: String): List<Long>

    /** Every queued row, any agent — the sweep in [RoomPendingMessageSink] needs it. */
    @Query("SELECT id FROM pending_messages")
    abstract fun allIds(): List<Long>

    @Query("SELECT MAX(id) FROM pending_messages WHERE agent_address = :agentAddress")
    abstract fun newestIdForAgent(agentAddress: String): Long?

    @Query("SELECT id FROM pending_messages WHERE agent_address = :agentAddress ORDER BY id ASC LIMIT 1")
    abstract fun oldestIdForAgent(agentAddress: String): Long?

    @Query("DELETE FROM pending_messages WHERE id = :id")
    abstract fun deleteById(id: Long)

    /**
     * The oldest row for [agentAddress] that belongs on [sessionId] and is no
     * newer than [maxId] — its own, plus any queued before there was a session
     * to name (`session_id = NULL` never matches, which is why the IS NULL arm
     * is separate). One row, not the lot: see [claimNextForAgent].
     */
    @Query(
        "SELECT * FROM pending_messages WHERE agent_address = :agentAddress AND id <= :maxId " +
            "AND (session_id IS NULL OR session_id = :sessionId) ORDER BY id ASC LIMIT 1"
    )
    abstract fun oldestClaimableForAgent(
        agentAddress: String,
        sessionId: String?,
        maxId: Long
    ): PendingMessageEntity?

    /**
     * Take the next queued row, oldest first, and delete it in the same
     * transaction. LIMIT 1 rather than the whole queue: a row carrying
     * attachments used to be read back with every other one at once, so a
     * flush materialized the entire outbox at peak. Atomic per row for the
     * reason the old read-and-clear was: two flushes racing (a reconnect
     * landing while the caller is already draining) must not both see it.
     */
    @Transaction
    open fun claimNextForAgent(agentAddress: String, sessionId: String?, maxId: Long): PendingMessageEntity? {
        val row = oldestClaimableForAgent(agentAddress, sessionId, maxId) ?: return null
        deleteById(row.id)
        return row
    }

    /** Drop the oldest row for [agentAddress], returning its id so its spilled attachments go too. */
    @Transaction
    open fun evictOldestForAgent(agentAddress: String): Long? {
        val id = oldestIdForAgent(agentAddress) ?: return null
        deleteById(id)
        return id
    }
}
