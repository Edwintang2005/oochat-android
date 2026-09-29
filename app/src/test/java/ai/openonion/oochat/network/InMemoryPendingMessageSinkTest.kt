package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.FileAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [InMemoryPendingMessageSink]: per-address isolation, FIFO
 * drain order, [PendingMessageSink.clear], and the
 * [AgentConnection.MAX_PENDING_MESSAGES] eviction cap — none of which had any
 * test coverage despite backing every queued-message send retried on
 * reconnect.
 */
class InMemoryPendingMessageSinkTest {

    private val sink = InMemoryPendingMessageSink()

    /** The session these messages are queued in; see PendingMessageSink. */
    private val SESSION = "sess-1"

    @Test
    fun `enqueue then drain hands back messages oldest first`() {
        sink.enqueue("0xagent", SESSION, "first", null)
        sink.enqueue("0xagent", SESSION, "second", listOf("img.png"))

        val dequeued = sink.drainAll("0xagent", SESSION)

        assertEquals(2, dequeued.size)
        assertEquals("first", dequeued[0].prompt)
        assertEquals(null, dequeued[0].images)
        assertEquals("second", dequeued[1].prompt)
        assertEquals(listOf("img.png"), dequeued[1].images)
    }

    @Test
    fun `drain removes only the matching address, leaving other addresses queued`() {
        sink.enqueue("0xagentA", "for A")
        sink.enqueue("0xagentB", "for B")

        val dequeuedA = sink.drainAll("0xagentA", SESSION)

        assertEquals(listOf("for A"), dequeuedA.map { it.prompt })
        assertEquals(listOf("for B"), sink.drainAll("0xagentB", SESSION).map { it.prompt })
    }

    @Test
    fun `drain on an address with nothing queued hands back nothing`() {
        assertTrue(sink.drainAll("0xnever-queued", SESSION).isEmpty())
    }

    @Test
    fun `drain empties the queue so a second call hands back nothing`() {
        sink.enqueue("0xagent", "only message")

        sink.drainAll("0xagent", SESSION)
        val second = sink.drainAll("0xagent", SESSION)

        assertTrue(second.isEmpty())
    }

    @Test
    fun `clear discards queued messages for one address without touching others`() {
        sink.enqueue("0xagentA", "for A")
        sink.enqueue("0xagentB", "for B")

        sink.clear("0xagentA")

        assertTrue(sink.drainAll("0xagentA", SESSION).isEmpty())
        assertEquals(listOf("for B"), sink.drainAll("0xagentB", SESSION).map { it.prompt })
    }

    @Test
    fun `enqueue beyond MAX_PENDING_MESSAGES evicts the oldest entry for that address`() {
        // A stuck reconnect loop combined with repeated sends must not grow
        // this queue unboundedly — see AgentConnection.MAX_PENDING_MESSAGES.
        repeat(AgentConnection.MAX_PENDING_MESSAGES) { i ->
            sink.enqueue("0xagent", "message-$i")
        }
        sink.enqueue("0xagent", "message-overflow")

        val dequeued = sink.drainAll("0xagent", SESSION)

        assertEquals(AgentConnection.MAX_PENDING_MESSAGES, dequeued.size)
        assertEquals("message-1", dequeued.first().prompt)
        assertEquals("message-overflow", dequeued.last().prompt)
    }

    @Test
    fun `enqueue then drain carries files through alongside images`() {
        // A message sent while disconnected must not drop its files — see
        // AgentConnection.sendMessage / MIGRATION_8_9's files_json column.
        val files = listOf(FileAttachment(name = "report.pdf", data = "data:application/pdf;base64,AAAA"))

        sink.enqueue("0xagent", SESSION, "see attached", listOf("img.png"), files)
        val dequeued = sink.drainAll("0xagent", SESSION)

        assertEquals(1, dequeued.size)
        assertEquals(files, dequeued.single().files)
    }

    @Test
    fun `enqueue without files defaults to null files on drain`() {
        sink.enqueue("0xagent", "no attachments")

        assertEquals(null, sink.drainAll("0xagent", SESSION).single().files)
    }

    private fun PendingMessageSink.enqueue(
        agentAddress: String,
        prompt: String,
        sessionId: String? = SESSION
    ) = enqueue(agentAddress, sessionId, prompt, images = null)

    /**
     * [PendingMessageSink.drain] as a list, for assertions. The production
     * caller deliberately never does this — see the interface's own doc — but
     * order and contents are what these tests are about.
     */
    private fun PendingMessageSink.drainAll(agentAddress: String, sessionId: String?): List<QueuedMessage> =
        buildList { drain(agentAddress, sessionId) { add(it) } }

    @Test
    fun `enqueue past the attachment byte budget evicts the oldest, not just past the row cap`() {
        // 50 rows says nothing about size: each one can carry a base64
        // attachment, so a handful of them can outweigh a full queue of text.
        val big = "x".repeat((AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1)
        sink.enqueue("0xagent", SESSION, "first big", listOf(big))
        sink.enqueue("0xagent", SESSION, "second big", listOf(big))

        val drained = sink.drainAll("0xagent", SESSION)

        assertEquals(
            "two attachments over the budget must not both stay queued",
            listOf("second big"),
            drained.map { it.prompt }
        )
    }

    @Test
    fun `a single message over the whole budget is still queued rather than dropped`() {
        // Evicting down to empty and then refusing the message would lose the
        // send the user just made, which is worse than exceeding the bound.
        val huge = "x".repeat(AgentConnection.MAX_PENDING_ATTACHMENT_BYTES.toInt() + 1)

        sink.enqueue("0xagent", SESSION, "oversized", listOf(huge))

        assertEquals(listOf("oversized"), sink.drainAll("0xagent", SESSION).map { it.prompt })
    }

    @Test
    fun `a message queued in one session is not drained by another`() {
        // Switching conversation before a queued message is acknowledged used
        // to flush it onto the conversation switched to instead.
        sink.enqueue("0xagent", "meant for B", sessionId = "sess-B")

        assertTrue(sink.drainAll("0xagent", "sess-C").isEmpty())
        assertEquals(listOf("meant for B"), sink.drainAll("0xagent", "sess-B").map { it.prompt })
    }

    @Test
    fun `a message queued before any session existed is flushed by the first one`() {
        // Queued while offline, before a CONNECT named a session: it belongs
        // to whichever session the connection comes back on.
        sink.enqueue("0xagent", "queued offline", sessionId = null)

        assertEquals(listOf("queued offline"), sink.drainAll("0xagent", "sess-A").map { it.prompt })
    }
}
