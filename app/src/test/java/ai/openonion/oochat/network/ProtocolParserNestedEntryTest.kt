package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nested lists inside a frame — `batch_remaining`, `files`, a session's
 * `messages` — decode as part of the enclosing frame, so a single entry with
 * a missing key or a wrong JSON type used to abort the whole decode and drop
 * the frame. For `approval_needed` that leaves the agent blocked in
 * `io.receive()` forever. The frame must survive; only the bad entry goes.
 */
class ProtocolParserNestedEntryTest {

    private fun parse(json: String) =
        ProtocolParser.parse(json, outstandingInputIds = emptySet(), isDirect = false)

    // ── approval_needed / batch_remaining ────────────────────────────

    @Test
    fun `an approval survives a batch entry whose arguments are an object, not a string`() {
        val result = parse(
            """{"type":"approval_needed","id":"a1","tool":"bash","arguments":{"command":"ls"},""" +
                """"batch_remaining":[{"tool":"write","arguments":{"file_path":"/tmp/x"}}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ApprovalNeeded
        assertEquals("bash", item.tool)
        assertEquals(1, item.batchRemaining?.size)
        assertEquals("write", item.batchRemaining?.first()?.tool)
        assertTrue(
            "an object-shaped arguments must degrade to its JSON text, not blank",
            item.batchRemaining?.first()?.arguments?.contains("/tmp/x") == true
        )
    }

    @Test
    fun `an approval survives a batch entry with no arguments key at all`() {
        val result = parse(
            """{"type":"approval_needed","id":"a2","tool":"bash","batch_remaining":[{"tool":"write"}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ApprovalNeeded
        assertEquals(1, item.batchRemaining?.size)
        assertEquals("", item.batchRemaining?.first()?.arguments)
    }

    @Test
    fun `a nameless batch entry is dropped, the rest of the batch is kept`() {
        val result = parse(
            """{"type":"approval_needed","id":"a3","tool":"bash","batch_remaining":""" +
                """[{"arguments":"{}"},{"tool":"write","arguments":"{}"}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ApprovalNeeded
        assertEquals(listOf("write"), item.batchRemaining?.map { it.tool })
    }

    // ── files_received ───────────────────────────────────────────────

    @Test
    fun `a files_received frame survives an entry with no path`() {
        val result = parse(
            """{"type":"files_received","id":"f1","files":[{"name":"a.txt"},{"name":"b.txt","path":"/tmp/b.txt"}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.FilesReceivedItem
        assertEquals(listOf("a.txt", "b.txt"), item.files.map { it.name })
        assertEquals("", item.files.first().path)
    }

    @Test
    fun `a nameless file entry is dropped, the rest of the frame is kept`() {
        val result = parse(
            """{"type":"files_received","id":"f2","files":[{"path":"/tmp/a.txt"},{"name":"b.txt","path":"/tmp/b.txt"}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.FilesReceivedItem
        assertEquals(listOf("b.txt"), item.files.map { it.name })
    }

    // ── session_sync / messages ──────────────────────────────────────

    @Test
    fun `a session_sync survives a message with no role`() {
        val result = parse(
            """{"type":"session_sync","session":{"messages":""" +
                """[{"content":"orphan"},{"role":"assistant","content":"the reply"}]}}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Turn
        assertEquals("the reply", item.agent?.content)
    }
}
