package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.FileAttachment

/** A message [AgentConnection] queued while the connection wasn't ready, as handed to [PendingMessageSink.drain]'s callback. */
data class QueuedMessage(val prompt: String, val images: List<String>?, val files: List<FileAttachment>? = null)

/**
 * Persistence seam for messages queued while the connection isn't ready.
 * [AgentConnection] depends on this plain interface rather than on Room
 * directly, so it stays constructible with no Android dependency in plain
 * JUnit tests; [ai.openonion.oochat.di.AppContainer] injects the
 * Room-backed implementation in production.
 */
interface PendingMessageSink {
    /**
     * Enqueues a message for [agentAddress], tagged with [sessionId] — the
     * conversation's session it was written in. Implementations bound their
     * own size by both row count and total attachment bytes; see
     * [AgentConnection.MAX_PENDING_MESSAGES] and
     * [AgentConnection.MAX_PENDING_ATTACHMENT_BYTES].
     */
    fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>? = null)

    /**
     * Removes the messages for [agentAddress] that belong on [sessionId],
     * oldest first — its own, plus any queued before there was a session to
     * name — handing each to [send] as it is read. A message queued for a
     * different conversation stays put: flushing it here would send it as
     * this conversation.
     *
     * A callback rather than a returned list, and that is the point: an
     * attachment is base64, so materializing the whole queue at once meant
     * every queued attachment resident simultaneously. One message is decoded
     * at a time and is free again as soon as it has been sent.
     */
    fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit)

    /** Discards every message queued for [agentAddress] without sending them. */
    fun clear(agentAddress: String)
}

/**
 * Default sink when no persisted one is injected (plain unit tests
 * constructing [AgentConnection] directly). Matches the pre-outbox
 * behaviour: queued messages don't survive process death.
 */
class InMemoryPendingMessageSink : PendingMessageSink {
    private data class Entry(val agentAddress: String, val sessionId: String?, val message: QueuedMessage)

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?) {
        val incoming = attachmentBytes(images, files)
        synchronized(lock) {
            // Bytes as well as rows: 50 rows says nothing about size when each
            // one can carry a base64 attachment. Drops oldest-first until both
            // fit; a single message larger than the whole budget empties the
            // queue and is still kept, since losing the newest send outright
            // is worse than losing the ones already superseded.
            while (
                entries.count { it.agentAddress == agentAddress } >= AgentConnection.MAX_PENDING_MESSAGES ||
                (incoming > 0 && queuedBytes(agentAddress) + incoming > AgentConnection.MAX_PENDING_ATTACHMENT_BYTES)
            ) {
                val oldest = entries.firstOrNull { it.agentAddress == agentAddress } ?: break
                entries.remove(oldest)
            }
            entries.add(Entry(agentAddress, sessionId, QueuedMessage(prompt, images, files)))
        }
    }

    override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) {
        while (true) {
            val next = synchronized(lock) {
                val entry = entries.firstOrNull {
                    it.agentAddress == agentAddress && (it.sessionId == null || it.sessionId == sessionId)
                } ?: return
                entries.remove(entry)
                entry.message
            }
            send(next)
        }
    }

    private fun queuedBytes(agentAddress: String): Long =
        entries.filter { it.agentAddress == agentAddress }
            .sumOf { attachmentBytes(it.message.images, it.message.files) }

    private fun attachmentBytes(images: List<String>?, files: List<FileAttachment>?): Long =
        images.orEmpty().sumOf { it.length.toLong() } +
            files.orEmpty().sumOf { it.name.length.toLong() + it.data.length.toLong() }

    override fun clear(agentAddress: String) {
        synchronized(lock) { entries.removeAll { it.agentAddress == agentAddress } }
    }
}
