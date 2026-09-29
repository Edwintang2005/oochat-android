package ai.openonion.oochat.data.local

import ai.openonion.oochat.data.local.db.dao.PendingMessageDao
import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Everything one queued message attached, spilled to [RoomPendingMessageSink]'s own directory. */
@Serializable
private data class SpilledAttachments(
    val images: List<String>? = null,
    val files: List<FileAttachment>? = null
)

/**
 * Room-backed [PendingMessageSink]: a message queued while disconnected
 * survives process death instead of dying with [AgentConnection]'s memory.
 *
 * Attachments do not live in the row. They are base64, and a 10MB file
 * (FileAttachmentStoreImpl.MAX_FILE_BYTES) inflates to ~13.3MB of it — well
 * past SQLite's 2MB CursorWindow, where reading the row throws and takes the
 * whole outbox with it, not just that message. So the row keeps the prompt
 * and the routing, and the payload goes to [spillDir] under the row's own id.
 * That also makes the queue boundable by bytes: the spill files ARE the size.
 *
 * Single instance per process (AppContainer builds one), which is what lets
 * [lock] stand in for the transaction the old count-then-insert used — the
 * bound now spans the database and the filesystem, so no single statement
 * could hold it anyway.
 */
class RoomPendingMessageSink(
    private val dao: PendingMessageDao,
    private val spillDir: File
) : PendingMessageSink {
    // Must stay compatible with how images are encoded elsewhere (see
    // MessageMapper / PersistenceTransaction): a JSON array of strings.
    // files reuses FileAttachment's own @Serializable shape (name + base64
    // data), same as the wire payload — no separate DTO needed.
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?) {
        val payload = if (images.isNullOrEmpty() && files.isNullOrEmpty()) {
            null
        } else {
            json.encodeToString(SpilledAttachments(images, files))
        }
        synchronized(lock) {
            evictUntilRoomFor(agentAddress, payload?.length?.toLong() ?: 0L)
            val id = dao.insert(
                PendingMessageEntity(
                    agentAddress = agentAddress,
                    sessionId = sessionId,
                    prompt = prompt,
                    imagesJson = null,
                    filesJson = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            if (payload != null) {
                runCatching {
                    spillDir.mkdirs()
                    spillFile(id).writeText(payload)
                }.onFailure { e ->
                    // The row stays: the text still sends, which beats dropping
                    // the message outright. Loud, because the attachments the
                    // user picked are silently not going with it.
                    FileLogger.e(
                        LogTags.PENDING_OUTBOX,
                        "Could not spill attachments for queued message $id — it will send as text only: ${e.message}"
                    )
                }
            }
        }
    }

    override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) {
        // Snapshot the watermark first: a send racing this flush (an INPUT
        // written while CONNECTED is being processed) enqueues behind it and
        // is left for the next pass rather than being reordered into this one.
        val maxId = synchronized(lock) { dao.newestIdForAgent(agentAddress) } ?: return
        while (true) {
            val row = synchronized(lock) { dao.claimNextForAgent(agentAddress, sessionId, maxId) } ?: break
            val spilled = readSpill(row.id)
            send(
                QueuedMessage(
                    prompt = row.prompt,
                    // Columns are the fallback, not the source: rows written
                    // by a build before the spill still carry theirs inline.
                    images = spilled?.images ?: row.imagesJson?.let { json.decodeFromString<List<String>>(it) },
                    files = spilled?.files ?: row.filesJson?.let { json.decodeFromString<List<FileAttachment>>(it) }
                )
            )
        }
        sweepOrphanedSpills()
    }

    override fun clear(agentAddress: String) {
        synchronized(lock) {
            dao.idsForAgent(agentAddress).forEach { spillFile(it).delete() }
            dao.deleteAllForAgent(agentAddress)
        }
    }

    /** Caller holds [lock]. Drops oldest-first until [incomingBytes] fits under both caps. */
    private fun evictUntilRoomFor(agentAddress: String, incomingBytes: Long) {
        while (
            dao.countForAgent(agentAddress) >= AgentConnection.MAX_PENDING_MESSAGES ||
            (incomingBytes > 0 && spilledBytes(agentAddress) + incomingBytes > AgentConnection.MAX_PENDING_ATTACHMENT_BYTES)
        ) {
            // Empty queue and it still doesn't fit: keep it anyway. A message
            // over the whole budget by itself is one the user just sent, and
            // dropping that is worse than exceeding the bound once.
            val evicted = dao.evictOldestForAgent(agentAddress) ?: return
            spillFile(evicted).delete()
            FileLogger.w(LogTags.PENDING_OUTBOX, "Outbox full for ${agentAddress.take(16)} — dropped queued message $evicted")
        }
    }

    private fun spilledBytes(agentAddress: String): Long =
        dao.idsForAgent(agentAddress).sumOf { spillFile(it).length() }

    private fun spillFile(id: Long) = File(spillDir, "$id.json")

    /** Reads and deletes the spill for [id]; the row it belonged to is already gone. */
    private fun readSpill(id: Long): SpilledAttachments? {
        val file = spillFile(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<SpilledAttachments>(file.readText()) }
            .onFailure { FileLogger.e(LogTags.PENDING_OUTBOX, "Unreadable spilled attachments for $id: ${it.message}") }
            .also { file.delete() }
            .getOrNull()
    }

    /**
     * Spills whose row is gone — a process death between claiming a row and
     * reading its file would otherwise leave the bytes on disk forever, and
     * they still count against the byte bound of whichever ids get reused.
     */
    private fun sweepOrphanedSpills() {
        val live = synchronized(lock) { dao.allIds() }.toSet()
        spillDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".json").toLongOrNull() ?: return@forEach
            if (id !in live) file.delete()
        }
    }
}
