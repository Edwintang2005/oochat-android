package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the 7 status/structural event types added to close the
 * dead-code gap between [ChatItem]'s web-parity subtypes and what
 * [ProtocolParser] actually understood: intent, eval, compact,
 * tool_blocked, plan_review, files_received, ulw_turns_reached.
 *
 * Field names in the JSON fixtures match oo-chat-web's
 * components/chat/types.ts verbatim (verified against the live reference
 * repo, not assumed) — see the plan doc for the source citations.
 */
class ProtocolParserStatusEventsTest {

    private val parser = ProtocolParser

    private fun parse(json: String) = parser.parse(json, outstandingInputIds = setOf("inp-1"), isDirect = false)

    // ── intent ───────────────────────────────────────────────────────

    @Test
    fun `intent analyzing maps to IntentStatus ANALYZING`() {
        val result = parse("""{"type":"intent","id":"i1","status":"analyzing","ack":"checking files"}""")

        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val item = ev.item as ChatItem.IntentItem
        assertEquals("i1", item.id)
        assertEquals(IntentStatus.ANALYZING, item.status)
        assertEquals("checking files", item.ack)
    }

    @Test
    fun `intent understood carries is_build flag`() {
        val result = parse("""{"type":"intent","id":"i1","status":"understood","ack":"got it","is_build":true}""")

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.IntentItem
        assertEquals(IntentStatus.UNDERSTOOD, item.status)
        assertEquals(true, item.isBuild)
    }

    @Test
    fun `intent without id falls back to a generated id`() {
        val result = parse("""{"type":"intent","status":"analyzing"}""")

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.IntentItem
        assertTrue(item.id.isNotBlank())
    }

    // ── eval ─────────────────────────────────────────────────────────

    @Test
    fun `eval evaluating leaves passed null`() {
        val result = parse("""{"type":"eval","id":"e1","status":"evaluating"}""")

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.EvalItem
        assertEquals(EvalStatus.EVALUATING, item.status)
        assertNull(item.passed)
    }

    @Test
    fun `eval done maps passed summary expected and eval_path`() {
        val result = parse(
            """{"type":"eval","id":"e1","status":"done","passed":true,"summary":"looks good","expected":"200 OK","eval_path":"/evals/e1.json"}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.EvalItem
        assertEquals(EvalStatus.DONE, item.status)
        assertEquals(true, item.passed)
        assertEquals("looks good", item.summary)
        assertEquals("200 OK", item.expected)
        assertEquals("/evals/e1.json", item.evalPath)
    }

    // ── compact ──────────────────────────────────────────────────────

    @Test
    fun `compact compacting maps percentages`() {
        val result = parse(
            """{"type":"compact","id":"c1","status":"compacting","context_before":80,"context_after":40,"context_percent":50}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.CompactItem
        assertEquals(CompactStatus.COMPACTING, item.status)
        assertEquals(80.0, item.contextBefore)
        assertEquals(40.0, item.contextAfter)
        assertEquals(50.0, item.contextPercent)
    }

    @Test
    fun `compact context_before and context_after parse a real float percentage without throwing`() {
        // Regression test: auto_compact.py sends agent.context_percent (a
        // float, e.g. 32.951) as both context_before and context_after on
        // the "done" frame. Int here threw "Unexpected symbol '.' in
        // numeric literal", silently dropping the whole compact event and
        // leaving the UI stuck on "Compacting context…".
        val result = parse(
            """{"type":"compact","id":"c1","status":"done","context_before":90.0,"context_after":32.951}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.CompactItem
        assertEquals(90.0, item.contextBefore!!, 0.0001)
        assertEquals(32.951, item.contextAfter!!, 0.0001)
    }

    @Test
    fun `compact compacting parses a fractional context_percent without throwing`() {
        // Regression test: contextPercent was typed Int, which threw
        // "Unexpected symbol '.' in numeric literal" on any frame carrying a
        // float here — silently dropping the whole event (and whatever
        // Thinking bubble it should have resolved to DONE along with it).
        // The value below is only about parsing a decimal; the scale itself
        // is 0-100, same as context_before/after.
        val result = parse(
            """{"type":"compact","id":"c1","status":"compacting","context_percent":0.3295}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.CompactItem
        assertEquals(0.3295, item.contextPercent!!, 0.0001)
    }

    @Test
    fun `compact error status carries the error message`() {
        val result = parse("""{"type":"compact","id":"c1","status":"error","error":"context too large"}""")

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.CompactItem
        assertEquals(CompactStatus.ERROR, item.status)
        assertEquals("context too large", item.error)
    }

    @Test
    fun `compact done status`() {
        val result = parse("""{"type":"compact","id":"c1","status":"done","message":"compacted"}""")

        val item = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.CompactItem
        assertEquals(CompactStatus.DONE, item.status)
        assertEquals("compacted", item.message)
    }

    // ── tool_blocked ─────────────────────────────────────────────────

    @Test
    fun `tool_blocked maps tool reason message and optional command`() {
        val result = parse(
            """{"type":"tool_blocked","id":"b1","tool":"bash","reason":"file_creation","message":"Blocked by policy","command":"rm -rf /"}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val item = ev.item as ChatItem.ToolBlockedItem
        assertEquals("b1", item.id)
        assertEquals("bash", item.tool)
        assertEquals("file_creation", item.reason)
        assertEquals("Blocked by policy", item.message)
        assertEquals("rm -rf /", item.command)
    }

    // ── plan_review ──────────────────────────────────────────────────

    @Test
    fun `plan_review carries raw plan_content and sets waiting`() {
        val result = parse("""{"type":"plan_review","id":"p1","plan_content":"# Step 1\nDo the thing"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.PlanReviewItem
        assertEquals("# Step 1\nDo the thing", item.planContent)
        assertTrue(result.setWaiting)
    }

    // ── files_received ───────────────────────────────────────────────

    @Test
    fun `files_received maps name and path pairs`() {
        val result = parse(
            """{"type":"files_received","id":"f1","files":[{"name":"a.txt","path":"/tmp/a.txt"},{"name":"b.txt","path":"/tmp/b.txt"}]}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.FilesReceivedItem
        assertEquals(2, item.files.size)
        assertEquals("a.txt", item.files[0].name)
        assertEquals("/tmp/a.txt", item.files[0].path)
    }

    @Test
    fun `files_received with no files list defaults to empty`() {
        val result = parse("""{"type":"files_received","id":"f1"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.FilesReceivedItem
        assertTrue(item.files.isEmpty())
    }

    // ── diff_preview ─────────────────────────────────────────────────

    @Test
    fun `diff_preview maps path preview truncated and file_exists`() {
        val result = parse(
            """{"type":"diff_preview","id":"d1","path":"src/App.tsx","preview":"--- a/src/App.tsx\n+++ b/src/App.tsx","truncated":false,"file_exists":true}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val item = ev.item as ChatItem.DiffPreviewItem
        assertEquals("d1", item.id)
        assertEquals("src/App.tsx", item.path)
        assertEquals("--- a/src/App.tsx\n+++ b/src/App.tsx", item.preview)
        assertEquals(false, item.truncated)
        assertEquals(true, item.fileExists)
        // Purely informational — must not gate the input like ask_user/approval_needed.
        assertTrue(!result.setWaiting)
    }

    @Test
    fun `diff_preview for a new file carries file_exists false`() {
        val result = parse(
            """{"type":"diff_preview","id":"d1","path":"src/New.tsx","preview":"+ export {}","truncated":false,"file_exists":false}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.DiffPreviewItem
        assertEquals(false, item.fileExists)
    }

    @Test
    fun `diff_preview truncated true is preserved`() {
        val result = parse(
            """{"type":"diff_preview","id":"d1","path":"src/Big.tsx","preview":"...(truncated)","truncated":true,"file_exists":true}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.DiffPreviewItem
        assertEquals(true, item.truncated)
    }

    @Test
    fun `diff_preview missing preview or path does not crash and defaults to blank`() {
        val result = parse("""{"type":"diff_preview","id":"d1"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.DiffPreviewItem
        assertEquals("", item.path)
        assertEquals("", item.preview)
        assertEquals(false, item.truncated)
        assertEquals(true, item.fileExists)
    }

    @Test
    fun `diff_preview without id falls back to a generated id`() {
        val result = parse("""{"type":"diff_preview","path":"src/App.tsx","preview":"diff"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.DiffPreviewItem
        assertTrue(item.id.isNotBlank())
    }

    // ── ulw_turns_reached ────────────────────────────────────────────

    @Test
    fun `ulw_turns_reached maps turns_used and max_turns and sets waiting`() {
        val result = parse("""{"type":"ulw_turns_reached","id":"u1","turns_used":20,"max_turns":20}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.UlwTurnsReachedItem
        assertEquals(20, item.turnsUsed)
        assertEquals(20, item.maxTurns)
        assertTrue(result.setWaiting)
    }

    // ── mode_changed ─────────────────────────────────────────────────
    // Fixtures match the 4 real emission sites verbatim: ulw.py:86
    // (triggered_by="user"), ulw.py:127 (triggered_by="ulw_checkpoint"),
    // tool_approval/approval.py:341 (triggered_by="agent"), and
    // plan_mode.py's exit_plan_and_implement() (no triggered_by key at all).

    @Test
    fun `mode_changed from ulw entry carries mode and triggered_by user`() {
        val result = parse("""{"type":"mode_changed","mode":"ulw","triggered_by":"user"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ModeChangedItem
        assertEquals("ulw", item.mode)
        assertEquals("user", item.triggeredBy)
    }

    @Test
    fun `mode_changed from ulw checkpoint exit carries triggered_by ulw_checkpoint`() {
        val result = parse("""{"type":"mode_changed","mode":"safe","triggered_by":"ulw_checkpoint"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ModeChangedItem
        assertEquals("safe", item.mode)
        assertEquals("ulw_checkpoint", item.triggeredBy)
    }

    @Test
    fun `mode_changed from tool_approval carries triggered_by agent`() {
        val result = parse("""{"type":"mode_changed","mode":"plan","triggered_by":"agent"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ModeChangedItem
        assertEquals("plan", item.mode)
        assertEquals("agent", item.triggeredBy)
    }

    @Test
    fun `mode_changed from plan_mode exit has no triggered_by field at all`() {
        // plan_mode.py's exit_plan_and_implement() sends
        // {'type':'mode_changed','mode':previous_mode} with the key entirely
        // absent, not present-and-null.
        val result = parse("""{"type":"mode_changed","mode":"safe"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ModeChangedItem
        assertEquals("safe", item.mode)
        assertNull(item.triggeredBy)
    }

    @Test
    fun `mode_changed without an id falls back to a generated id`() {
        val result = parse("""{"type":"mode_changed","mode":"accept_edits"}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ModeChangedItem
        assertTrue(item.id.isNotBlank())
    }

    @Test
    fun `mode_changed missing the required mode field is dropped as Unknown`() {
        val result = parse("""{"type":"mode_changed","triggered_by":"agent"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    // ── SESSION_STATUS (reply) ──────────────────────────────────────────

    @Test
    fun `SESSION_STATUS running maps to SessionStatusReceived`() {
        val result = parse("""{"type":"SESSION_STATUS","session_id":"sess-1","status":"running"}""")

        val event = result.event as ConnectionEvent.SessionStatusReceived
        assertEquals("sess-1", event.sessionId)
        assertEquals("running", event.status)
    }

    @Test
    fun `SESSION_STATUS not_found maps sessionId through even without a live session`() {
        val result = parse("""{"type":"SESSION_STATUS","session_id":"sess-2","status":"not_found"}""")

        val event = result.event as ConnectionEvent.SessionStatusReceived
        assertEquals("sess-2", event.sessionId)
        assertEquals("not_found", event.status)
    }

    @Test
    fun `SESSION_STATUS connected status parses`() {
        val result = parse("""{"type":"SESSION_STATUS","session_id":"sess-3","status":"connected"}""")

        val event = result.event as ConnectionEvent.SessionStatusReceived
        assertEquals("connected", event.status)
    }

    @Test
    fun `SESSION_STATUS missing the required status field is dropped as Unknown`() {
        val result = parse("""{"type":"SESSION_STATUS","session_id":"sess-1"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
    }
}
