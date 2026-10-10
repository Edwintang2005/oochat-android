package ai.openonion.oochat.network

import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatEventReducer
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.PlanEntry
import ai.openonion.oochat.domain.model.PlanPriority
import ai.openonion.oochat.domain.model.PlanStatus
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.stableAssistantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-17: the streaming frames — `thinking`, `tool_call`, `tool_result`
 * and `plan` — checked against what the Host actually sends, not against the
 * client's older reading of it.
 *
 * Wire shapes are taken from connectonion: tool_executor.py builds the
 * tool_call/tool_result pair on one `tool_id`, wire_events.py rewrites their
 * statuses to OIP's lifecycle (`in_progress`, `completed`, `failed`) on the
 * way out, re_act.py and reflect.py record `thinking` with a `kind`, and
 * agent.py's `_record_plan` sends the complete Todo List. Every logged frame
 * also carries its own trace `id`, distinct per frame.
 *
 * Each frame type gets parser tests, then parse-and-reduce tests for the
 * streamed order and for a reconnect replay.
 */
class StreamingFramesSpecTest {

    private fun parse(json: String) =
        ProtocolParser.parse(json, outstandingInputIds = setOf("inp-1"), isDirect = false)

    /** The repository's ConnectionEvent → ChatEvent mapping, for chat-item frames. */
    private fun ConnectionEvent.toChatEvent(): ChatEvent? = when (this) {
        is ConnectionEvent.ChatItemReceived -> ChatEvent.ChatItemReceived(item, fromSessionSnapshot, answeredQuestion)
        is ConnectionEvent.ChatItemUpdated -> ChatEvent.ChatItemUpdated(item)
        is ConnectionEvent.OutputReceived -> ChatEvent.OutputReceived(result, null)
        else -> null
    }

    /** Parses and folds [frames] in order, the way the chat list sees them. */
    private fun stream(frames: List<String>, start: List<ChatItem> = emptyList()): List<ChatItem> =
        frames.fold(start) { items, frame ->
            val result = parse(frame)
            (result.extraEvents + result.event).mapNotNull { it.toChatEvent() }
                .fold(items) { acc, event -> ChatEventReducer.reduce(acc, event, ignoredIds = emptySet()).chatItems }
        }

    private inline fun <reified T : ChatItem> List<ChatItem>.only(id: String): T =
        filter { it.id == id }.also { assertEquals("exactly one item for $id", 1, it.size) }.single() as T

    // ── thinking ─────────────────────────────────────────────────────

    @Test
    fun `thinking with text is reasoning under the Host's trace id`() {
        val result = parse("""{"type":"thinking","id":"tr-7","kind":"intent","content":"You want the tests run."}""")

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Thinking
        assertEquals("tr-7", item.id)
        assertEquals(ThinkingStatus.DONE, item.status)
        assertEquals("You want the tests run.", item.content)
        assertEquals("intent", item.kind)
        assertTrue(item.isReasoning)
        assertEquals("tr-7", result.msgId)
    }

    @Test
    fun `thinking with text but no kind is still reasoning`() {
        val item = (parse("""{"type":"thinking","id":"tr-8","content":"hmm"}""").event
            as ConnectionEvent.ChatItemReceived).item as ChatItem.Thinking

        assertEquals(ProtocolParser.REASONING_KIND, item.kind)
    }

    @Test
    fun `thinking with no id gets a stable id that cannot collide with a reply`() {
        val first = (parse("""{"type":"thinking","content":"same thought"}""").event
            as ConnectionEvent.ChatItemReceived).item
        val again = (parse("""{"type":"thinking","content":"same thought"}""").event
            as ConnectionEvent.ChatItemReceived).item

        assertEquals("a redelivery must dedupe", first.id, again.id)
        assertNotEquals("a reply with the same text must stay a separate item", stableAssistantId("same thought"), first.id)
    }

    @Test
    fun `thinking with no text stays a turn footer`() {
        val item = (parse("""{"type":"thinking","id":"tr-9","model":"m"}""").event
            as ConnectionEvent.ChatItemReceived).item as ChatItem.Turn

        assertEquals("tr-9", item.id)
        assertNull(item.agent)
        assertEquals(ThinkingStatus.DONE, item.thinking!!.status)
    }

    @Test
    fun `streamed reasoning is not repeated as a reply by the session_sync that follows it`() {
        // re_act appends its acknowledgement to the session's messages, so the
        // session_sync sent right after the thinking frame lists it again as
        // an assistant message.
        val items = stream(listOf(
            """{"type":"thinking","id":"tr-1","kind":"intent","content":"Checking the build."}""",
            """{"type":"session_sync","session":{"session_id":"s","messages":[""" +
                """{"role":"user","content":"build it"},{"role":"assistant","content":"Checking the build."}]}}"""
        ))

        assertEquals(1, items.size)
        assertTrue((items.single() as ChatItem.Thinking).isReasoning)
    }

    @Test
    fun `a real reply in the same session_sync still arrives`() {
        val items = stream(listOf(
            """{"type":"thinking","id":"tr-1","kind":"intent","content":"Checking the build."}""",
            """{"type":"session_sync","session":{"session_id":"s","messages":[""" +
                """{"role":"assistant","content":"Checking the build."},{"role":"assistant","content":"Build passed."}]}}"""
        ))

        assertEquals(listOf("Build passed."), items.filterIsInstance<ChatItem.Turn>().map { it.agent?.content })
    }

    @Test
    fun `a replayed thinking frame does not duplicate the reasoning`() {
        val frame = """{"type":"thinking","id":"tr-1","kind":"reflect","content":"That worked."}"""

        assertEquals(1, stream(listOf(frame, frame)).size)
    }

    @Test
    fun `reasoning keeps its kind through a save and reload`() {
        val item = ChatItem.Thinking(id = "tr-1", status = ThinkingStatus.DONE, content = "That worked.", kind = "reflect")

        val reloaded = TranscriptItemCodec.decode(TranscriptItemCodec.encode(TranscriptItemCodec.toPayload(item)!!))

        assertEquals(item, reloaded)
    }

    // ── tool_call ────────────────────────────────────────────────────

    @Test
    fun `tool_call is keyed on tool_id, not on the frame's own trace id`() {
        val result = parse(
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash",""" +
                """"args":{"command":"ls","timeout":30},"status":"in_progress"}"""
        )

        val item = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall
        assertEquals("call_abc", item.id)
        assertEquals("bash", item.name)
        assertEquals(mapOf("command" to "ls", "timeout" to "30"), item.args)
        assertEquals(ToolStatus.RUNNING, item.status)
        assertEquals("the replay cursor still uses the trace id", "tr-20", result.msgId)
    }

    @Test
    fun `tool_call start statuses all read as running`() {
        for (status in listOf("pending", "running", "in_progress", null)) {
            val field = status?.let { ""","status":"$it"""" } ?: ""
            val item = (parse("""{"type":"tool_call","tool_id":"c1","name":"x"$field}""").event
                as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall
            assertEquals("status=$status", ToolStatus.RUNNING, item.status)
        }
    }

    @Test
    fun `tool_call with a status no start can have fails closed`() {
        val item = (parse("""{"type":"tool_call","tool_id":"c1","name":"x","status":"bogus"}""").event
            as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall

        assertEquals(ToolStatus.ERROR, item.status)
    }

    // ── tool_result ──────────────────────────────────────────────────

    @Test
    fun `tool_result statuses map as the Host defines them`() {
        val expected = mapOf(
            // The live wire (wire_events.py).
            "completed" to ToolStatus.DONE,
            "failed" to ToolStatus.ERROR,
            // Trace spellings a replay or an older Host can still send.
            "success" to ToolStatus.DONE,
            "done" to ToolStatus.DONE,
            "error" to ToolStatus.ERROR,
            "not_found" to ToolStatus.ERROR,
            "interrupted" to ToolStatus.ERROR,
            // A result is terminal: no status, or one nobody defined, is not success.
            "in_progress" to ToolStatus.ERROR,
            "whatever" to ToolStatus.ERROR
        )
        for ((status, want) in expected) {
            val item = (parse("""{"type":"tool_result","tool_id":"c1","name":"x","status":"$status"}""").event
                as ConnectionEvent.ChatItemUpdated).item as ChatItem.ToolCall
            assertEquals("status=$status", want, item.status)
        }
        val missing = (parse("""{"type":"tool_result","tool_id":"c1","name":"x"}""").event
            as ConnectionEvent.ChatItemUpdated).item as ChatItem.ToolCall
        assertEquals(ToolStatus.ERROR, missing.status)
    }

    @Test
    fun `tool_result carries its result and timing`() {
        val item = (parse(
            """{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash",""" +
                """"status":"completed","result":"a.txt","timing_ms":12.7}"""
        ).event as ConnectionEvent.ChatItemUpdated).item as ChatItem.ToolCall

        assertEquals("call_abc", item.id)
        assertEquals("a.txt", item.result)
        assertEquals(12L, item.timingMs)
    }

    @Test
    fun `a streamed tool call settles in place and keeps what it ran`() {
        val items = stream(listOf(
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","args":{"command":"ls"},"status":"in_progress"}""",
            """{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash","args":{"command":"ls"},"status":"completed","result":"a.txt"}"""
        ))

        val tool = items.only<ChatItem.ToolCall>("call_abc")
        assertEquals(ToolStatus.DONE, tool.status)
        assertEquals("a.txt", tool.result)
        assertEquals(mapOf("command" to "ls"), tool.args)
    }

    @Test
    fun `a failed tool is shown as failed`() {
        // The live wire's spelling of failure; it used to render as success.
        val items = stream(listOf(
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","status":"in_progress"}""",
            """{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash","status":"failed","result":"exit 1"}"""
        ))

        assertEquals(ToolStatus.ERROR, items.only<ChatItem.ToolCall>("call_abc").status)
    }

    @Test
    fun `parallel tool results each attach to their own call`() {
        val items = stream(listOf(
            """{"type":"tool_call","id":"tr-1","tool_id":"call_a","name":"read","status":"in_progress"}""",
            """{"type":"tool_call","id":"tr-2","tool_id":"call_b","name":"grep","status":"in_progress"}""",
            """{"type":"tool_result","id":"tr-3","tool_id":"call_b","name":"grep","status":"completed","result":"B"}""",
            """{"type":"tool_result","id":"tr-4","tool_id":"call_a","name":"read","status":"failed","result":"A"}"""
        ))

        assertEquals(listOf("call_a", "call_b"), items.map { it.id })
        assertEquals("A", items.only<ChatItem.ToolCall>("call_a").result)
        assertEquals(ToolStatus.ERROR, items.only<ChatItem.ToolCall>("call_a").status)
        assertEquals("B", items.only<ChatItem.ToolCall>("call_b").result)
        assertEquals(ToolStatus.DONE, items.only<ChatItem.ToolCall>("call_b").status)
    }

    // ── reconnect ────────────────────────────────────────────────────

    @Test
    fun `after a reconnect a tool result attaches to the call already on screen`() {
        // The socket dropped while the tool ran; the replay after CONNECT
        // resumes after the client's cursor, so only the result arrives.
        val beforeDrop = stream(listOf(
            """{"type":"llm_call","id":"llm-1","model":"m"}""",
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","args":{"command":"make"},"status":"in_progress"}"""
        ))

        val afterReplay = stream(
            listOf("""{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash","status":"completed","result":"ok"}"""),
            start = beforeDrop
        )

        assertEquals(beforeDrop.map { it.id }, afterReplay.map { it.id })
        val tool = afterReplay.only<ChatItem.ToolCall>("call_abc")
        assertEquals(ToolStatus.DONE, tool.status)
        assertEquals("ok", tool.result)
        assertEquals(mapOf("command" to "make"), tool.args)
    }

    @Test
    fun `a replayed tool_call never reopens a finished call`() {
        // A replay can start before the client's cursor (the Host resumes
        // after the first frame carrying that id), re-sending the start of a
        // call whose result is already on screen.
        val finished = stream(listOf(
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","args":{"command":"make"},"status":"in_progress"}""",
            """{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash","status":"completed","result":"ok"}"""
        ))

        val replayed = stream(
            listOf("""{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","args":{"command":"make"},"status":"in_progress"}"""),
            start = finished
        )

        assertEquals(finished, replayed)
    }

    @Test
    fun `a result that arrives before its call keeps its status once the call shows up`() {
        // A cursor the Host does not know replays the whole execution, but a
        // result can still reach the list first (e.g. after a cleared window).
        val items = stream(listOf(
            """{"type":"tool_result","id":"tr-21","tool_id":"call_abc","name":"bash","status":"failed","result":"exit 2"}""",
            """{"type":"tool_call","id":"tr-20","tool_id":"call_abc","name":"bash","args":{"command":"make"},"status":"in_progress"}"""
        ))

        val tool = items.only<ChatItem.ToolCall>("call_abc")
        assertEquals(ToolStatus.ERROR, tool.status)
        assertEquals("exit 2", tool.result)
        assertEquals("the late start still supplies what ran", mapOf("command" to "make"), tool.args)
    }

    @Test
    fun `replaying the whole execution changes nothing`() {
        // What a fresh AgentConnection gets (Reconnect button, app restart):
        // no cursor, no frame keys, so every frame of the run arrives again.
        val run = listOf(
            """{"type":"llm_call","id":"llm-1","model":"m"}""",
            """{"type":"thinking","id":"tr-1","kind":"intent","content":"Running the build."}""",
            """{"type":"llm_result","id":"llm-1","status":"success","model":"m","duration_ms":120}""",
            """{"type":"tool_call","id":"tr-2","tool_id":"call_a","name":"bash","args":{"command":"make"},"status":"in_progress"}""",
            """{"type":"tool_result","id":"tr-3","tool_id":"call_a","name":"bash","status":"completed","result":"ok"}""",
            """{"type":"plan","id":"tr-4","entries":[{"content":"Build","priority":"high","status":"completed"}]}""",
            """{"type":"thinking","id":"tr-5","kind":"reflect","content":"Build passed."}"""
        )

        val live = stream(run)
        val replayed = stream(run, start = live)

        assertEquals(live, replayed)
        assertEquals(listOf("llm-1", "tr-1", "call_a", "tr-5"), live.map { it.id })
    }

    // ── plan ─────────────────────────────────────────────────────────

    @Test
    fun `plan replaces the Todo List and moves the replay cursor`() {
        val result = parse(
            """{"type":"plan","id":"tr-30","entries":[""" +
                """{"content":"Run tests","priority":"high","status":"in_progress"},""" +
                """{"content":"Update docs","priority":"medium","status":"pending"}]}"""
        )

        assertEquals(
            ConnectionEvent.PlanUpdated(listOf(
                PlanEntry("Run tests", PlanPriority.HIGH, PlanStatus.IN_PROGRESS),
                PlanEntry("Update docs", PlanPriority.MEDIUM, PlanStatus.PENDING)
            )),
            result.event
        )
        assertEquals("tr-30", result.msgId)
        assertNull("a full replacement is safe to apply twice; nothing to drop", result.dedupeKey)
    }

    @Test
    fun `an empty plan clears it`() {
        assertEquals(
            ConnectionEvent.PlanUpdated(emptyList()),
            parse("""{"type":"plan","id":"tr-31","entries":[]}""").event
        )
    }

    @Test
    fun `a malformed plan is ignored whole but still moves the cursor`() {
        val bad = listOf(
            """[{"content":"","priority":"high","status":"pending"}]""",
            """[{"content":"x","priority":"urgent","status":"pending"}]""",
            """[{"content":"x","priority":"high","status":"blocked"}]""",
            """[{"content":"ok","priority":"low","status":"completed"},{"priority":"low","status":"pending"}]"""
        )
        for (entries in bad) {
            val result = parse("""{"type":"plan","id":"tr-32","entries":$entries}""")
            assertEquals(entries, ConnectionEvent.Unknown, result.event)
            assertEquals(entries, "tr-32", result.msgId)
        }
        assertEquals(ConnectionEvent.Unknown, parse("""{"type":"plan","id":"tr-33"}""").event)
    }

    @Test
    fun `plan is not a chat item`() {
        val items = stream(listOf(
            """{"type":"plan","id":"tr-30","entries":[{"content":"Run tests","priority":"high","status":"in_progress"}]}"""
        ))

        assertTrue(items.isEmpty())
    }

    @Test
    fun `a session snapshot's plan decodes, and an absent one says nothing`() {
        val withPlan = parse(
            """{"type":"session_sync","session":{"session_id":"s","messages":[],""" +
                """"plan":[{"content":"Build","priority":"low","status":"pending"}]}}"""
        )
        val withoutPlan = parse("""{"type":"session_sync","session":{"session_id":"s","messages":[]}}""")

        assertEquals(
            listOf(PlanEntry("Build", PlanPriority.LOW, PlanStatus.PENDING)),
            ProtocolParser.decodePlan(withPlan.updateSession!!.plan)
        )
        assertNull(ProtocolParser.decodePlan(withoutPlan.updateSession!!.plan))
    }
}
