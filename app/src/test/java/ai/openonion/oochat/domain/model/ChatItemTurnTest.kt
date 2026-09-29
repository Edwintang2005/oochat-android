package ai.openonion.oochat.domain.model

import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.ParseResult
import ai.openonion.oochat.network.ProtocolParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [ChatItem.Turn] — the combined Thinking+Agent-reply unit that
 * is meant to eventually replace the flat Thinking/Agent pair entirely, and
 * lines up with how [SessionState.trace] and future streaming/multi-agent
 * work will be modelled.
 *
 * [ProtocolParser.parseSessionSync] is currently the only place that emits
 * a Turn (replayed assistant messages from the server). The live
 * llm_call → llm_result → assistant path still emits separate
 * Thinking/Agent items and migrating it is left for later, so this file
 * only exercises the session_sync path.
 */
class ChatItemTurnTest {

    private fun jsonEscape(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sessionSyncFrame(vararg roleAndContent: Pair<String, String>): String {
        val encodedMessages = roleAndContent.joinToString(",") { (role, content) ->
            """{"role":${jsonEscape(role)},"content":${jsonEscape(content)}}"""
        }
        return """{"type":"session_sync","session":{"session_id":"s1","messages":[$encodedMessages]}}"""
    }

    private fun turnsIn(parsed: ParseResult): List<ChatItem.Turn> {
        val primary = (parsed.event as? ConnectionEvent.ChatItemReceived)?.item as? ChatItem.Turn
        val extras = parsed.extraEvents.mapNotNull { (it as? ConnectionEvent.ChatItemReceived)?.item as? ChatItem.Turn }
        return listOfNotNull(primary) + extras
    }

    private fun parseSync(vararg roleAndContent: Pair<String, String>) =
        ProtocolParser.parse(sessionSyncFrame(*roleAndContent), outstandingInputIds = emptySet(), isDirect = false)

    @Test
    fun `session_sync produces a Turn holding the reply but no thinking metadata`() {
        val turn = turnsIn(parseSync("user" to "Hi", "assistant" to "Hello, I'm a large language model.")).single()

        assertNull("session_sync replay carries no live thinking data", turn.thinking)
        assertNotNull("the agent reply must be attached", turn.agent)
        assertEquals("Hello, I'm a large language model.", turn.agent!!.content)
    }

    @Test
    fun `Turn id is the content hash, not a positional session-msg-N counter`() {
        val turn = turnsIn(parseSync("assistant" to "any reply")).single()

        assertTrue("expected an asst-<hash> id, got: ${turn.id}", turn.id.startsWith("asst-"))
    }

    @Test
    fun `two syncs replaying identical content produce the same Turn id`() {
        val firstSync = turnsIn(parseSync("assistant" to "stable reply")).single()
        val secondSync = turnsIn(parseSync("assistant" to "stable reply")).single()

        assertEquals(
            "identical replayed content must hash to the same id so dedupeUI() can collapse the replay",
            firstSync.id,
            secondSync.id
        )
    }

    @Test
    fun `Turn's constructor accepts thinking and agent set together`() {
        // Not exercised by parseSessionSync today, but the live flow will
        // eventually populate both on one Turn (llm_call sets thinking
        // RUNNING, llm_result flips it to DONE, assistant fills in agent —
        // all sharing a single Turn id), so the shape has to allow it.
        val bothSet = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(
                id = "turn-1",
                status = ThinkingStatus.DONE,
                model = "gemini-3-flash-preview",
                durationMs = 1234.0
            ),
            agent = ChatItem.Agent(id = "turn-1", content = "ok")
        )

        assertEquals("turn-1", bothSet.id)
        assertEquals(ThinkingStatus.DONE, bothSet.thinking!!.status)
        assertEquals("ok", bothSet.agent!!.content)
    }
}
