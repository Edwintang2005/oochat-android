package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptItemCodecTest {

    private fun roundTrip(item: ChatItem): ChatItem? =
        TranscriptItemCodec.toPayload(item)
            ?.let(TranscriptItemCodec::encode)
            ?.let(TranscriptItemCodec::decode)

    @Test
    fun `tool call survives a round trip with args and result intact`() {
        val item = ChatItem.ToolCall(
            id = "t1",
            name = "bash:ls",
            args = mapOf("command" to "ls -la", "description" to "list files"),
            status = ToolStatus.DONE,
            result = "total 24\ndrwxr-xr-x  ...",
            timingMs = 143L
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `files received survives a round trip`() {
        val item = ChatItem.FilesReceivedItem(
            id = "f1",
            files = listOf(ReceivedFile("a.txt", "/tmp/a.txt"), ReceivedFile("b.png", "/tmp/b.png"))
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `thinking survives a round trip`() {
        val item = ChatItem.Thinking(
            id = "th1",
            status = ThinkingStatus.DONE,
            model = "claude-opus-5",
            durationMs = 812.5,
            content = "considered the directory listing"
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `thinking with token usage and context percent survives a round trip`() {
        val item = ChatItem.Thinking(
            id = "th1",
            status = ThinkingStatus.DONE,
            model = "claude-opus-5",
            durationMs = 812.5,
            tokensTotal = 1234,
            contextPercent = 0.3295
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `a thinking row from before usage existed decodes with tokensTotal and contextPercent null`() {
        val raw = """{"kind":"thinking","id":"th1","status":"DONE","model":"claude-opus-5","durationMs":812.5}"""
        val decoded = TranscriptItemCodec.decode(raw) as ChatItem.Thinking
        assertNull(decoded.tokensTotal)
        assertNull(decoded.contextPercent)
        assertEquals("claude-opus-5", decoded.model)
    }

    @Test
    fun `an unanswered approval gate is not persisted`() {
        // Restoring it would offer buttons for a question the server has closed.
        assertNull(TranscriptItemCodec.toPayload(ChatItem.ApprovalNeeded("a2", "bash", emptyMap())))
    }

    @Test
    fun `an answered approval survives a round trip as a read-only record`() {
        val item = ChatItem.ApprovalNeeded(
            id = "a2",
            tool = "bash",
            arguments = mapOf("command" to "rm README.md"),
            description = "Delete a file",
            decision = ApprovalDecision(approved = false, scope = "once", mode = "reject_hard")
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `an answered approval with rejection feedback survives a round trip`() {
        val item = ChatItem.ApprovalNeeded(
            id = "a2",
            tool = "bash",
            arguments = mapOf("command" to "rm README.md"),
            description = "Delete a file",
            decision = ApprovalDecision(approved = false, scope = "once", mode = "reject_soft", feedback = "Use yarn instead")
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `an approval row from before feedback existed decodes with feedback null`() {
        val raw = """{"kind":"approval","id":"a2","tool":"bash","arguments":{"command":"rm README.md"},"approved":false,"scope":"once","mode":"reject_hard"}"""
        val decoded = TranscriptItemCodec.decode(raw) as ChatItem.ApprovalNeeded
        assertNull(decoded.decision?.feedback)
        assertEquals(false, decoded.decision?.approved)
        assertEquals("reject_hard", decoded.decision?.mode)
    }

    @Test
    fun `a mode_changed item with triggeredBy survives a round trip`() {
        val item = ChatItem.ModeChangedItem(id = "m1", mode = "plan", triggeredBy = "agent")
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `a mode_changed item with no triggeredBy survives a round trip`() {
        // plan_mode.py's exit path sends no triggered_by at all — mirrored
        // here as a null field, not a missing one on this side.
        val item = ChatItem.ModeChangedItem(id = "m1", mode = "safe", triggeredBy = null)
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `a mode_changed row from before triggeredBy existed decodes with it null`() {
        val raw = """{"kind":"mode_changed","id":"m1","mode":"ulw"}"""
        val decoded = TranscriptItemCodec.decode(raw) as ChatItem.ModeChangedItem
        assertEquals("ulw", decoded.mode)
        assertNull(decoded.triggeredBy)
    }

    @Test
    fun `the other gates are still not persisted`() {
        assertNull(TranscriptItemCodec.toPayload(ChatItem.AskUser("a1", "pick", listOf("x"), false)))
        assertNull(TranscriptItemCodec.toPayload(ChatItem.PlanReviewItem("a3", "the plan")))
    }

    @Test
    fun `plain chat text is not persisted through this path`() {
        // User/Agent/Turn keep using the role/content columns.
        assertNull(TranscriptItemCodec.toPayload(ChatItem.User("u1", "hi")))
        assertNull(TranscriptItemCodec.toPayload(ChatItem.Agent("g1", "hello")))
    }

    @Test
    fun `diff preview survives a round trip`() {
        val item = ChatItem.DiffPreviewItem(
            id = "d1",
            path = "src/App.tsx",
            preview = "--- a/src/App.tsx\n+++ b/src/App.tsx\n-old\n+new",
            truncated = true,
            fileExists = true
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `a diff preview row from before this payload type existed is skipped, not fatal`() {
        // Simulates a row persisted by an older build that predates
        // TranscriptItemPayload.DiffPreview — the codec must degrade to
        // "not restored" rather than crash the whole transcript load.
        val raw = """{"kind":"no_such_kind","id":"d1","path":"src/App.tsx"}"""
        assertNull(TranscriptItemCodec.decode(raw))
    }

    @Test
    fun `a corrupt row decodes to null rather than throwing`() {
        assertNull(TranscriptItemCodec.decode("not json at all"))
        assertNull(TranscriptItemCodec.decode("""{"kind":"no_such_kind","id":"x"}"""))
        assertNull(TranscriptItemCodec.decode("{}"))
    }

    @Test
    fun `an unknown field from a newer build is ignored, not fatal`() {
        val raw = """{"kind":"tool_call","id":"t1","name":"grep","status":"DONE","futureField":42}"""
        val decoded = TranscriptItemCodec.decode(raw)
        assertEquals("grep", (decoded as? ChatItem.ToolCall)?.name)
    }

    @Test
    fun `an unknown status name falls back to the terminal state`() {
        val raw = """{"kind":"tool_call","id":"t1","name":"grep","status":"SOMETHING_NEW"}"""
        val decoded = TranscriptItemCodec.decode(raw) as? ChatItem.ToolCall
        assertEquals(ToolStatus.DONE, decoded?.status)
    }

    @Test
    fun `encoded payload carries the kind discriminator`() {
        val encoded = TranscriptItemCodec.encode(
            TranscriptItemCodec.toPayload(ChatItem.ToolCall(id = "t1", name = "grep"))!!
        )
        // Decoding goes through the sealed parent, so the row is unreadable
        // without this — see TranscriptItemCodec.encode.
        assertTrue(encoded, encoded.contains(""""kind":"tool_call""""))
    }

    // ── turn metadata ────────────────────────────────────────────────

    @Test
    fun `a turn's footer metadata survives a round trip`() {
        val thinking = ChatItem.Thinking(
            id = "turn-1",
            status = ThinkingStatus.DONE,
            model = "gemini-2.5-flash",
            durationMs = 2100.0,
            tokensTotal = 46,
            costUsd = 0.0071,
            contextPercent = 12.5
        )
        val encoded = TranscriptItemCodec.encodeTurnThinking(thinking)!!
        assertEquals(thinking, TranscriptItemCodec.decodeTurnThinking(encoded))
    }

    @Test
    fun `a finished turn with nothing to report stores no payload`() {
        // Otherwise a reloaded turn shows a footer reading just "Done",
        // which says less than no footer at all.
        assertNull(
            TranscriptItemCodec.encodeTurnThinking(
                ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.DONE)
            )
        )
    }

    @Test
    fun `a failed turn is stored even with no numbers on it`() {
        // "Failed" is worth restoring on its own; only a bare DONE is noise.
        val failed = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.ERROR)
        val encoded = TranscriptItemCodec.encodeTurnThinking(failed)!!
        assertEquals(ThinkingStatus.ERROR, TranscriptItemCodec.decodeTurnThinking(encoded)?.status)
    }

    @Test
    fun `a non-turn payload does not decode as turn metadata`() {
        val toolCall = TranscriptItemCodec.encode(
            TranscriptItemCodec.toPayload(ChatItem.ToolCall(id = "t1", name = "grep"))!!
        )
        assertNull(TranscriptItemCodec.decodeTurnThinking(toolCall))
    }

    // ── footer-only turns (the live llm_call/llm_result row) ─────────

    @Test
    fun `a footer-only turn round-trips as a turn, not a bare thinking`() {
        val turn = ChatItem.Turn(
            id = "call-7",
            thinking = ChatItem.Thinking(
                id = "call-7",
                status = ThinkingStatus.DONE,
                model = "gemini-2.5-flash",
                durationMs = 2100.0,
                tokensTotal = 46,
                costUsd = 0.0071,
                contextPercent = 12.5
            )
        )
        // Must come back as a Turn: sessionUsageTotals only counts Turns.
        assertEquals(turn, roundTrip(turn))
    }

    @Test
    fun `a turn that already carries its reply is not a transcript row`() {
        // It takes the assistant-row path instead, keeping its content
        // searchable — see PersistenceTransaction.
        assertNull(
            TranscriptItemCodec.toPayload(
                ChatItem.Turn(
                    id = "t1",
                    thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, model = "m"),
                    agent = ChatItem.Agent(id = "t1", content = "hello")
                )
            )
        )
    }

    @Test
    fun `a still-running turn is not stored`() {
        // llm_result replaces it under the same id moments later; restoring a
        // RUNNING row would be a spinner that can never finish.
        assertNull(
            TranscriptItemCodec.toPayload(
                ChatItem.Turn(
                    id = "call-7",
                    thinking = ChatItem.Thinking(id = "call-7", status = ThinkingStatus.RUNNING, model = "m")
                )
            )
        )
    }

    @Test
    fun `a footer-only turn reporting nothing is not stored`() {
        assertNull(
            TranscriptItemCodec.toPayload(
                ChatItem.Turn(id = "call-7", thinking = ChatItem.Thinking(id = "call-7", status = ThinkingStatus.DONE))
            )
        )
    }
}
