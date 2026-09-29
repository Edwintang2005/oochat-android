package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.stableAssistantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the [ProtocolParser.parse] message-type branches not already
 * covered by [ProtocolParserLiveFlowTest] (llm_call/llm_result/thinking/
 * assistant) or [ProtocolParserSessionSyncTest] (session_sync): PING,
 * CONNECTED, tool_call, tool_result, ask_user, approval_needed,
 * ONBOARD_REQUIRED, ONBOARD_SUCCESS, user_input, OUTPUT, and ERROR.
 *
 * [ProtocolParser.parse] is a pure function (JSON string in, [ParseResult]
 * out) — the cheapest possible high-value unit-test target, and previously
 * under half its branches were covered.
 */
class ProtocolParserTest {

    private val parser = ProtocolParser

    private fun parse(json: String, currentInputId: String? = "inp-1", isDirect: Boolean = false) =
        parser.parse(json, outstandingInputIds = setOfNotNull(currentInputId), isDirect = isDirect)

    // ── PING ─────────────────────────────────────────────────────────

    @Test
    fun `PING requests a pong reply`() {
        val result = parse("""{"type":"PING"}""")

        assertEquals(ConnectionEvent.Ping, result.event)
        assertTrue(result.shouldSendPong)
    }

    // ── CONNECTED ────────────────────────────────────────────────────

    @Test
    fun `CONNECTED carries session id and marks session for update`() {
        val result = parse("""{"type":"CONNECTED","session_id":"sess-42"}""")

        val ev = result.event as ConnectionEvent.Connected
        assertEquals("sess-42", ev.sessionId)
        assertEquals("sess-42", result.updateSessionId)
    }

    @Test
    fun `CONNECTED leaves address blank for the caller to fill in`() {
        // ProtocolParser doesn't know the agent address it connected to —
        // that's supplied by the caller (AgentConnection) after parsing.
        val result = parse("""{"type":"CONNECTED"}""")

        val ev = result.event as ConnectionEvent.Connected
        assertEquals("", ev.address)
    }

    // ── AGENT_PROFILE ────────────────────────────────────────────────

    @Test
    fun `AGENT_PROFILE parses a full frame into an AgentProfileReceived event`() {
        val result = parse(
            """{"type":"AGENT_PROFILE","session_id":"sess-1","name":"research-assistant",
              |"address":"0xabc123","model":"gemini-2.5-pro",
              |"tools":["bash","search"],"skills":["web-scraping","pdf"],"balance_usd":4.2}"""
                .trimMargin()
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertEquals("sess-1", ev.profile.sessionId)
        assertEquals("research-assistant", ev.profile.name)
        assertEquals("0xabc123", ev.profile.address)
        assertEquals("gemini-2.5-pro", ev.profile.model)
        assertEquals(listOf("bash", "search"), ev.profile.tools)
        // Bare strings are not the shape any live agent sends, but they decode
        // to name-only skills rather than dropping the frame — see SkillWire.
        assertEquals(
            listOf(AgentSkill("web-scraping"), AgentSkill("pdf")),
            ev.profile.skills
        )
        assertEquals(4.2, ev.profile.balanceUsd!!, 0.0001)
    }

    @Test
    fun `AGENT_PROFILE without balance_usd leaves it null`() {
        // connect.py only sends balance_usd when it's non-None — must not
        // fail decoding, and must not default to 0.0 (that's a real balance).
        val result = parse(
            """{"type":"AGENT_PROFILE","name":"assistant","model":"gpt-5","tools":[],"skills":[]}"""
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertNotNull(ev.profile)
        assertEquals(null, ev.profile.balanceUsd)
    }

    @Test
    fun `AGENT_PROFILE with empty tools and skills arrays parses to empty lists, not null`() {
        val result = parse(
            """{"type":"AGENT_PROFILE","name":"assistant","model":"gpt-5","tools":[],"skills":[]}"""
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertTrue(ev.profile.tools.isEmpty())
        assertTrue(ev.profile.skills.isEmpty())
    }

    @Test
    fun `AGENT_PROFILE keeps a skills list of objects, the shape real agents send`() {
        // Verbatim from the live relay directory (GET /api/agents, agent
        // 0x5513e629e0 "oo"): every published skill is {name, description}.
        // A String-typed `skills` fails to decode and drops the *whole*
        // frame — model and tools with it.
        val result = parse(
            """{"type":"AGENT_PROFILE","session_id":"sess-1","name":"oo","model":"claude-opus-4",
              |"tools":["bash","read_file"],"skills":[
              |{"name":"code-review","description":"Review a code diff or pull request for correctness, security, and style, reporting findings ranked by severity with concrete failure scenarios and fixes."},
              |{"name":"api-docs","description":"Generate reference documentation for a project's public API or HTTP endpoints directly from the source."}]}"""
                .trimMargin()
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertEquals("claude-opus-4", ev.profile.model)
        assertEquals(listOf("bash", "read_file"), ev.profile.tools)
        assertEquals(listOf("code-review", "api-docs"), ev.profile.skills.map { it.name })
        assertTrue(ev.profile.skills[0].description!!.startsWith("Review a code diff"))
    }

    @Test
    fun `AGENT_PROFILE keeps one entry per skill name, whatever the agent registered`() {
        // The palette renders these in a LazyColumn keyed on the name, and a
        // repeated key throws. Nothing on the wire rules a repeat out — an
        // agent that registers the same skill twice would take the app down
        // the moment the user typed `/`. First one wins; the description that
        // survives is the first, not the last.
        val result = parse(
            """{"type":"AGENT_PROFILE","name":"dup","skills":[
              |{"name":"search","description":"first"},
              |{"name":"deploy"},
              |{"name":"search","description":"second"}]}"""
                .trimMargin()
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertEquals(listOf("search", "deploy"), ev.profile.skills.map { it.name })
        assertEquals("first", ev.profile.skills[0].description)
    }

    @Test
    fun `AGENT_PROFILE treats a placeholder or missing description as none at all`() {
        // "No description" is what the host emits for a skill file with no
        // summary — the live naturewill-mapping agent sends exactly that.
        val result = parse(
            """{"type":"AGENT_PROFILE","name":"naturewill-mapping","skills":[
              |{"name":"candidate-mapping","description":"No description"},
              |{"name":"quiet"},
              |{"name":"blank","description":"   "}]}"""
                .trimMargin()
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertEquals(listOf("candidate-mapping", "quiet", "blank"), ev.profile.skills.map { it.name })
        assertTrue(ev.profile.skills.all { it.description == null })
    }

    @Test
    fun `AGENT_PROFILE drops an unusable skill entry but keeps the frame`() {
        // Anything without a name is not a command anyone can type. Dropping
        // the entry costs one palette row; failing the decode costs the model,
        // the tools and every other skill.
        val result = parse(
            """{"type":"AGENT_PROFILE","name":"agent","model":"gpt-5","skills":[
              |{"description":"nameless"},[],"bare-string",
              |{"name":"code-review","description":"Review a diff"}]}"""
                .trimMargin()
        )

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertEquals("gpt-5", ev.profile.model)
        assertEquals(listOf("bare-string", "code-review"), ev.profile.skills.map { it.name })
    }

    @Test
    fun `AGENT_PROFILE with tools and skills absent defaults to empty lists`() {
        val result = parse("""{"type":"AGENT_PROFILE","name":"assistant","model":"gpt-5"}""")

        val ev = result.event as ConnectionEvent.AgentProfileReceived
        assertTrue(ev.profile.tools.isEmpty())
        assertTrue(ev.profile.skills.isEmpty())
    }

    // ── DASHBOARD_SNAPSHOT ───────────────────────────────────────────

    @Test
    fun `DASHBOARD_SNAPSHOT carries the agent's HTML through verbatim`() {
        val html = "<html><body><h1>Home</h1><button data-ochat-skill=\\\"pdf\\\">Run</button></body></html>"
        val result = parse("""{"type":"DASHBOARD_SNAPSHOT","html":"$html"}""")

        val ev = result.event as ConnectionEvent.DashboardSnapshotReceived
        // Verbatim is the contract: nothing here parses, strips or rewrites
        // the payload — containment is the WebView's CSP, see DashboardDocument.
        assertEquals(html.replace("\\\"", "\""), ev.html)
    }

    @Test
    fun `DASHBOARD_SNAPSHOT without an html field is dropped`() {
        // "No Home page" and "a blank Home page" must not look the same to
        // the UI — the top bar's entry point is gated on a snapshot existing.
        assertEquals(ConnectionEvent.Unknown, parse("""{"type":"DASHBOARD_SNAPSHOT"}""").event)
    }

    @Test
    fun `DASHBOARD_SNAPSHOT with blank html is dropped`() {
        assertEquals(ConnectionEvent.Unknown, parse("""{"type":"DASHBOARD_SNAPSHOT","html":"   "}""").event)
    }

    @Test
    fun `DASHBOARD_SNAPSHOT larger than the host's own cap is dropped`() {
        // ws_router/dashboard.py refuses to send more than MAX_DASHBOARD_BYTES,
        // so anything past it is a runaway file or a hostile relay. It must
        // never reach a WebView, where the cost lands on the UI thread.
        val huge = "<p>x</p>".repeat(ProtocolParser.MAX_DASHBOARD_HTML_CHARS / 8 + 1)
        assertEquals(
            ConnectionEvent.Unknown,
            parse("""{"type":"DASHBOARD_SNAPSHOT","html":"$huge"}""").event
        )
    }

    @Test
    fun `DASHBOARD_SNAPSHOT at exactly the cap is still accepted`() {
        val atLimit = "x".repeat(ProtocolParser.MAX_DASHBOARD_HTML_CHARS)
        val ev = parse("""{"type":"DASHBOARD_SNAPSHOT","html":"$atLimit"}""").event
        assertTrue(ev is ConnectionEvent.DashboardSnapshotReceived)
    }

    // ── tool_call ────────────────────────────────────────────────────

    @Test
    fun `tool_call emits a ToolCall item in RUNNING status`() {
        val result = parse(
            """{"type":"tool_call","tool_id":"t1","name":"search","args":{"query":"weather"}}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val toolCall = ev.item as ChatItem.ToolCall
        assertEquals("t1", toolCall.id)
        assertEquals("search", toolCall.name)
        assertEquals(mapOf("query" to "weather"), toolCall.args)
        assertEquals(ToolStatus.RUNNING, toolCall.status)
    }

    @Test
    fun `tool_call falls back to id when tool_id is absent`() {
        val result = parse("""{"type":"tool_call","id":"t2","name":"lookup"}""")

        val toolCall = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall
        assertEquals("t2", toolCall.id)
    }

    @Test
    fun `tool_call defaults name to unknown when absent`() {
        val result = parse("""{"type":"tool_call","tool_id":"t3"}""")

        val toolCall = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall
        assertEquals("unknown", toolCall.name)
    }

    @Test
    fun `tool_call with non-string args (int, bool) is not dropped and renders their literal value`() {
        // Regression test: bash.py's `timeout: int = 120` and other tools'
        // bool/number params make the server send args with native JSON
        // types, not strings. ProtocolParser's Json is strict (no
        // isLenient) — a Map<String, String>-typed field previously threw a
        // hard SerializationException on this exact shape, which the outer
        // catch in parse() maps to ConnectionEvent.Unknown, silently
        // dropping the whole tool_call frame.
        val result = parse(
            """{"type":"tool_call","tool_id":"t4","name":"bash","args":{"command":"ls","timeout":120,"quiet":true}}"""
        )

        val toolCall = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.ToolCall
        assertEquals(
            mapOf("command" to "ls", "timeout" to "120", "quiet" to "true"),
            toolCall.args
        )
    }

    // ── tool_result ──────────────────────────────────────────────────

    @Test
    fun `tool_result updates the matching ToolCall to DONE with a result`() {
        val result = parse(
            """{"type":"tool_result","tool_id":"t1","name":"search","status":"ok","result":"sunny, 72F"}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val toolCall = ev.item as ChatItem.ToolCall
        assertEquals("t1", toolCall.id)
        assertEquals(ToolStatus.DONE, toolCall.status)
        assertEquals("sunny, 72F", toolCall.result)
    }

    @Test
    fun `tool_result with error status maps to ToolStatus ERROR`() {
        val result = parse("""{"type":"tool_result","tool_id":"t1","status":"error"}""")

        val toolCall = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.ToolCall
        assertEquals(ToolStatus.ERROR, toolCall.status)
    }

    @Test
    fun `tool_result with not_found status maps to ToolStatus ERROR`() {
        // Regression test: tool_executor.py sets status="not_found" (not
        // "error") when the LLM calls a tool that doesn't exist — see
        // execute_single_tool's early-return branch, and its own
        // `status in ("error", "not_found")` on_error gate a few lines
        // later confirms the two are meant to be treated alike. The parser
        // only recognized the literal string "error", so a not-found tool
        // rendered as a normal successful DONE result.
        val result = parse(
            """{"type":"tool_result","tool_id":"t5","name":"nonexistent_tool","status":"not_found","result":"Tool 'nonexistent_tool' not found"}"""
        )

        val toolCall = (result.event as ConnectionEvent.ChatItemUpdated).item as ChatItem.ToolCall
        assertEquals(ToolStatus.ERROR, toolCall.status)
    }

    @Test
    fun `tool_result with neither tool_id nor id is dropped as Unknown`() {
        val result = parse("""{"type":"tool_result","status":"ok"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    // ── agent_image ──────────────────────────────────────────────────

    @Test
    fun `agent_image emits a standalone Agent item carrying the image`() {
        val result = parse("""{"type":"agent_image","image":"https://oo.openonion.ai/img/abc"}""")

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val agent = ev.item as ChatItem.Agent
        assertEquals("", agent.content)
        assertEquals(listOf("https://oo.openonion.ai/img/abc"), agent.images)
    }

    @Test
    fun `agent_image with no image field is dropped as Unknown`() {
        val result = parse("""{"type":"agent_image"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    @Test
    fun `agent_image derives a content-stable id, not a random one, so redelivery dedupes`() {
        // Same image URL parsed twice (e.g. a redelivered frame after
        // reconnect) must produce the same id so dedupeUI() collapses it
        // instead of duplicating the bubble.
        val first = parse("""{"type":"agent_image","image":"https://oo.openonion.ai/img/abc"}""")
        val second = parse("""{"type":"agent_image","image":"https://oo.openonion.ai/img/abc"}""")

        val firstId = ((first.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Agent).id
        val secondId = ((second.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Agent).id
        assertEquals(firstId, secondId)
        assertEquals(stableAssistantId("https://oo.openonion.ai/img/abc"), firstId)
    }

    @Test
    fun `agent_image with a different image gets a different id`() {
        val first = parse("""{"type":"agent_image","image":"https://oo.openonion.ai/img/abc"}""")
        val second = parse("""{"type":"agent_image","image":"https://oo.openonion.ai/img/xyz"}""")

        val firstId = ((first.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Agent).id
        val secondId = ((second.event as ConnectionEvent.ChatItemReceived).item as ChatItem.Agent).id
        assertNotEquals(firstId, secondId)
    }

    // ── ask_user ─────────────────────────────────────────────────────

    @Test
    fun `ask_user emits an AskUser item and sets waiting`() {
        val result = parse(
            """{"type":"ask_user","id":"q1","text":"Continue?","options":["yes","no"],"multi_select":false}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val askUser = ev.item as ChatItem.AskUser
        assertEquals("q1", askUser.id)
        assertEquals("Continue?", askUser.text)
        assertEquals(listOf("yes", "no"), askUser.options)
        assertFalse(askUser.multiSelect)
        assertTrue(result.setWaiting)
    }

    @Test
    fun `ask_user defaults missing fields to blank text and no options`() {
        val result = parse("""{"type":"ask_user","id":"q2"}""")

        val askUser = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.AskUser
        assertEquals("", askUser.text)
        assertTrue(askUser.options.isEmpty())
        assertFalse(askUser.multiSelect)
    }

    @Test
    fun `ask_user reads the question field the server actually sends`() {
        // Regression test: ask_user.py's event payload is
        // {"type": "ask_user", "question": ..., ...} — there is no "text"
        // field on the wire. ProtocolParser previously read event.text,
        // which is never populated for this event type, so the question
        // rendered blank every time.
        val result = parse(
            """{"type":"ask_user","id":"q3","question":"Which date?","options":["Mon","Tue"]}"""
        )

        val askUser = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.AskUser
        assertEquals("Which date?", askUser.text)
    }

    @Test
    fun `ask_user falls back to text when question is absent`() {
        // Belt-and-suspenders: an older or non-standard frame that only
        // carries "text" must still render something rather than going
        // blank now that "question" is the primary field.
        val result = parse("""{"type":"ask_user","id":"q4","text":"Continue?"}""")

        val askUser = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.AskUser
        assertEquals("Continue?", askUser.text)
    }

    // ── approval_needed ──────────────────────────────────────────────

    @Test
    fun `approval_needed emits an ApprovalNeeded item and sets waiting`() {
        val result = parse(
            """{"type":"approval_needed","id":"a1","tool":"delete_file","arguments":{"path":"/tmp/x"},"description":"Delete a file"}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val approval = ev.item as ChatItem.ApprovalNeeded
        assertEquals("a1", approval.id)
        assertEquals("delete_file", approval.tool)
        assertEquals(mapOf("path" to "/tmp/x"), approval.arguments)
        assertEquals("Delete a file", approval.description)
        assertTrue(result.setWaiting)
    }

    @Test
    fun `approval_needed with non-string arguments (int, bool) is not dropped`() {
        // Regression test: this is the highest-impact instance of the
        // strict-decode-too-narrow bug. tool_approval's approval.py sends
        // the tool's raw arguments dict verbatim, e.g. bash's
        // {"command": ..., "timeout": 120}. A Map<String, String>-typed
        // field threw a SerializationException on this exact shape, which
        // parse()'s outer catch maps to ConnectionEvent.Unknown — the
        // approval_needed frame vanished entirely, the approval card never
        // rendered, and the agent stayed blocked forever in io.receive()
        // waiting for a response nobody could send. This is a functional
        // deadlock, not just a cosmetic drop.
        val result = parse(
            """{"type":"approval_needed","id":"a2","tool":"bash","arguments":{"command":"npm install","timeout":120,"background":false}}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val approval = ev.item as ChatItem.ApprovalNeeded
        assertEquals(
            mapOf("command" to "npm install", "timeout" to "120", "background" to "false"),
            approval.arguments
        )
        assertTrue(result.setWaiting)
    }

    // ── ONBOARD_REQUIRED ─────────────────────────────────────────────

    @Test
    fun `ONBOARD_REQUIRED emits an OnboardRequired item and sets waiting`() {
        val result = parse(
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code","payment"],"payment_amount":5.0,"payment_address":"0xpay"}"""
        )

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val onboard = ev.item as ChatItem.OnboardRequired
        assertEquals(listOf("invite_code", "payment"), onboard.methods)
        assertEquals(5.0, onboard.paymentAmount!!, 0.001)
        assertEquals("0xpay", onboard.paymentAddress)
        assertTrue(result.setWaiting)
    }

    @Test
    fun `ONBOARD_REQUIRED defaults methods to empty list when absent`() {
        val result = parse("""{"type":"ONBOARD_REQUIRED","id":"ob2"}""")

        val onboard = (result.event as ConnectionEvent.ChatItemReceived).item as ChatItem.OnboardRequired
        assertTrue(onboard.methods.isEmpty())
    }

    // ── ONBOARD_SUCCESS ──────────────────────────────────────────────

    @Test
    fun `ONBOARD_SUCCESS emits an OnboardSuccess item without setting waiting`() {
        val result = parse("""{"type":"ONBOARD_SUCCESS","id":"ob3","level":"verified","message":"Welcome aboard"}""")

        val ev = result.event as ConnectionEvent.ChatItemReceived
        val success = ev.item as ChatItem.OnboardSuccess
        assertEquals("verified", success.level)
        assertEquals("Welcome aboard", success.message)
        assertFalse("ONBOARD_SUCCESS does not gate on user input", result.setWaiting)
    }

    // ── user_input ───────────────────────────────────────────────────

    @Test
    fun `user_input echo is silently dropped`() {
        // The server echoes the user's own input back; the local
        // ChatItem.User already renders it, so this must not double it.
        val result = parse("""{"type":"user_input","content":"hello"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    // ── OUTPUT ───────────────────────────────────────────────────────

    @Test
    fun `OUTPUT with no input_id (relay broadcast) is delivered`() {
        val result = parse("""{"type":"OUTPUT","result":"final answer"}""", currentInputId = "inp-1")

        val ev = result.event as ConnectionEvent.OutputReceived
        assertEquals("final answer", ev.result)
    }

    @Test
    fun `OUTPUT matching the current input_id is delivered`() {
        val result = parse("""{"type":"OUTPUT","result":"final answer","input_id":"inp-1"}""", currentInputId = "inp-1")

        val ev = result.event as ConnectionEvent.OutputReceived
        assertEquals("final answer", ev.result)
    }

    @Test
    fun `the run's OUTPUT still arrives after an interjection added a second input_id`() {
        // An INPUT sent mid-run is pushed into the running agent as runtime
        // input and answered by that run's own OUTPUT, which on the relay path
        // echoes the *first* input_id. A single-slot "current" id meant the
        // interjection invalidated the reply it was waiting for, and the
        // composer sat on Stop forever.
        val result = parser.parse(
            """{"type":"OUTPUT","result":"final answer","input_id":"inp-1"}""",
            outstandingInputIds = setOf("inp-1", "inp-2"),
            isDirect = false
        )

        val ev = result.event as ConnectionEvent.OutputReceived
        assertEquals("final answer", ev.result)
    }

    @Test
    fun `OUTPUT for a different input_id is dropped on a relay connection`() {
        // Relay mode multiplexes multiple clients' inputs through one
        // socket — an OUTPUT tagged for someone else's input_id must not
        // be rendered as our own reply.
        val result = parse(
            """{"type":"OUTPUT","result":"someone else's answer","input_id":"inp-OTHER"}""",
            currentInputId = "inp-1",
            isDirect = false
        )

        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    @Test
    fun `OUTPUT is always delivered on a direct connection regardless of input_id`() {
        // Direct connections are 1:1 (no multiplexing), so input_id
        // filtering doesn't apply.
        val result = parse(
            """{"type":"OUTPUT","result":"the answer","input_id":"inp-OTHER"}""",
            currentInputId = "inp-1",
            isDirect = true
        )

        val ev = result.event as ConnectionEvent.OutputReceived
        assertEquals("the answer", ev.result)
    }

    @Test
    fun `OUTPUT defaults result to empty string when absent`() {
        val result = parse("""{"type":"OUTPUT"}""")

        val ev = result.event as ConnectionEvent.OutputReceived
        assertEquals("", ev.result)
    }

    // ── ERROR ────────────────────────────────────────────────────────

    @Test
    fun `ERROR prefers the message field over error`() {
        val result = parse("""{"type":"ERROR","message":"human readable","error":"raw code"}""")

        val ev = result.event as ConnectionEvent.ConnectionError
        assertEquals("human readable", ev.message)
    }

    @Test
    fun `ERROR falls back to the error field when message is absent`() {
        val result = parse("""{"type":"ERROR","error":"Session is already attached to another connection"}""")

        val ev = result.event as ConnectionEvent.ConnectionError
        assertEquals("Session is already attached to another connection", ev.message)
    }

    @Test
    fun `ERROR with neither field falls back to a generic message`() {
        val result = parse("""{"type":"ERROR"}""")

        val ev = result.event as ConnectionEvent.ConnectionError
        assertEquals("Unknown error", ev.message)
    }

    // ── Unrecognized type ────────────────────────────────────────────

    @Test
    fun `an unrecognized type falls through to Unknown`() {
        val result = parse("""{"type":"some_future_event_type"}""")

        assertEquals(ConnectionEvent.Unknown, result.event)
        assertFalse(result.shouldSendPong)
        assertFalse(result.setWaiting)
        assertNull(result.updateSession)
    }

    // ── Malformed frame ──────────────────────────────────────────────

    @Test
    fun `malformed JSON is caught and mapped to Unknown instead of throwing`() {
        val result = parse("""{"type": not valid json at all""")

        assertEquals(ConnectionEvent.Unknown, result.event)
        assertFalse(result.shouldSendPong)
        assertFalse(result.setWaiting)
        assertNull(result.updateSession)
    }
}
