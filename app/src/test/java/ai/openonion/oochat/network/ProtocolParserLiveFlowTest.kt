package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.stableAssistantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the Phase 2 migration that makes the live llm_call → llm_result
 * → assistant flow also emit [ChatItem.Turn], so all three "agent-side"
 * event types share the same data shape (Turn) and dedupeUI() can
 * reason about the chat list uniformly.
 *
 * These assertions cover what the parser emits per frame, where llm_call's
 * `event.id` (call id) is still distinct from the content-derived assistant
 * id. Joining the two into one bubble is `ChatEventReducer`'s job, not the
 * parser's — see ChatEventHelpers.mergeIntoPendingTurn and
 * LiveTurnPersistenceTest, which asserts the whole sequence lands as a
 * single item.
 */
class ProtocolParserLiveFlowTest {

    private val parser = ProtocolParser

    private fun parse(json: String) = parser.parse(json, outstandingInputIds = setOf("inp-1"), isDirect = false)

    // ── llm_call: emit Turn with thinking=RUNNING, agent=null ─────

    @Test
    fun `llm_call emits Turn with Thinking RUNNING and no agent`() {
        val text = """{"type":"llm_call","id":"call-7","model":"gemini-3-flash-preview"}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemReceived
        val turn = ev.item as ChatItem.Turn
        assertEquals("call-7", turn.id)
        assertNotNull("Turn.thinking must be set for llm_call", turn.thinking)
        assertEquals(ThinkingStatus.RUNNING, turn.thinking!!.status)
        assertEquals("gemini-3-flash-preview", turn.thinking!!.model)
        assertNull("Turn.agent must be null at llm_call time", turn.agent)
    }

    // ── llm_result: emit ChatItemUpdated of Turn with thinking=DONE ──

    @Test
    fun `llm_result updates existing Turn thinking to DONE keeping same id`() {
        val text = """{"type":"llm_result","id":"call-7","status":"ok","model":"gemini-3-flash-preview","duration_ms":1234.5}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val turn = ev.item as ChatItem.Turn
        assertEquals("call-7", turn.id)
        assertNotNull(turn.thinking)
        assertEquals(ThinkingStatus.DONE, turn.thinking!!.status)
        assertEquals(1234.5, turn.thinking!!.durationMs!!, 0.001)
        assertNull("Turn.agent must remain null at llm_result time", turn.agent)
    }

    @Test
    fun `llm_result with error status maps to Thinking ERROR`() {
        val text = """{"type":"llm_result","id":"call-7","status":"error"}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val turn = ev.item as ChatItem.Turn
        assertEquals(ThinkingStatus.ERROR, turn.thinking!!.status)
    }

    // ── llm_result: usage + context_percent (real frame shape) ─────

    @Test
    fun `llm_result sums input and output tokens from a real usage payload`() {
        // Shape taken from core/agent.py's response.usage.model_dump() —
        // TokenUsage carries more fields (cached_tokens, cost, ...) than this
        // client reads; ignoreUnknownKeys must not choke on them.
        // context_percent is a percentage, not a fraction: core/agent.py
        // documents "(0-100)" and returns input_tokens / limit * 100.
        val text = """{"type":"llm_result","id":"call-7","status":"success","model":"claude-sonnet-4-20250514","duration_ms":9123.0,"usage":{"input_tokens":950,"output_tokens":284,"cached_tokens":0,"cache_write_tokens":0,"cost":0.00711},"context_percent":32.95}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val turn = ev.item as ChatItem.Turn
        assertEquals(1234, turn.thinking!!.tokensTotal)
        assertEquals(32.95, turn.thinking!!.contextPercent!!, 0.0001)
        assertEquals("the server prices every call; dropping it threw the number away", 0.00711, turn.thinking!!.costUsd!!, 1e-6)
    }

    @Test
    fun `llm_result with a usage object missing one counter treats it as zero`() {
        val text = """{"type":"llm_result","id":"call-7","status":"success","usage":{"input_tokens":500}}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val turn = ev.item as ChatItem.Turn
        assertEquals(500, turn.thinking!!.tokensTotal)
    }

    @Test
    fun `llm_result prefers an explicit total_tokens over summing the sides`() {
        // Precedence copied from the reference web client's status-bar.tsx.
        val text = """{"type":"llm_result","id":"call-7","status":"success","usage":{"total_tokens":4096,"input_tokens":1,"output_tokens":1}}"""
        val turn = (parse(text).event as ConnectionEvent.ChatItemUpdated).item as ChatItem.Turn
        assertEquals(4096, turn.thinking!!.tokensTotal)
    }

    @Test
    fun `llm_result accepts the OpenAI prompt and completion token spellings`() {
        // This server sends input_tokens/output_tokens, but a provider whose
        // usage is passed through unchanged uses these — web reads both.
        val text = """{"type":"llm_result","id":"call-7","status":"success","usage":{"prompt_tokens":700,"completion_tokens":300}}"""
        val turn = (parse(text).event as ConnectionEvent.ChatItemUpdated).item as ChatItem.Turn
        assertEquals(1000, turn.thinking!!.tokensTotal)
    }

    @Test
    fun `llm_result without a usage field leaves tokensTotal and contextPercent null`() {
        val text = """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-3-flash-preview","duration_ms":812.0}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemUpdated
        val turn = ev.item as ChatItem.Turn
        assertNull("a frame with no usage at all must not fabricate a 0 token count", turn.thinking!!.tokensTotal)
        assertNull(turn.thinking!!.contextPercent)
    }

    // ── assistant: emit Turn with agent, no thinking ───────────────

    @Test
    fun `assistant emits Turn with agent content and no thinking`() {
        val text = """{"type":"assistant","id":"asst-9","content":"Hello world"}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemReceived
        val turn = ev.item as ChatItem.Turn
        // The id is content-derived (not event.id) so this Turn dedupes
        // with later session_sync replays of the same reply.
        assertEquals(
            "Turn id must be the asst-<content-hash> scheme so session_sync merges",
            stableAssistantId("Hello world"),
            turn.id
        )
        assertNull("Turn.thinking is null for the assistant frame", turn.thinking)
        assertNotNull(turn.agent)
        assertEquals("Hello world", turn.agent!!.content)
    }

    @Test
    fun `assistant without content is dropped`() {
        val text = """{"type":"assistant","id":"asst-9"}"""
        val result = parse(text)
        assertEquals(ConnectionEvent.Unknown, result.event)
    }

    // ── thinking event: dual purpose ──────────────────────────────

    @Test
    fun `thinking event with content emits Turn with agent`() {
        val text = """{"type":"thinking","id":"asst-9","content":"a streamed reply"}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemReceived
        val turn = ev.item as ChatItem.Turn
        assertEquals(
            "thinking-with-content Turn id is content-derived so it merges with assistant / session_sync",
            stableAssistantId("a streamed reply"),
            turn.id
        )
        assertEquals("a streamed reply", turn.agent!!.content)
        assertNull(turn.thinking)
    }

    @Test
    fun `thinking event without content emits Turn with thinking DONE only`() {
        val text = """{"type":"thinking","id":"think-3","model":"gemini-3-flash-preview"}"""
        val result = parse(text)
        val ev = result.event as ConnectionEvent.ChatItemReceived
        val turn = ev.item as ChatItem.Turn
        assertEquals("think-3", turn.id)
        assertEquals(ThinkingStatus.DONE, turn.thinking!!.status)
        assertNull(turn.agent)
    }
}
