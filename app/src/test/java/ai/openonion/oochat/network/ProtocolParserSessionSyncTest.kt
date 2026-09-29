package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug where session_sync events used
 * positional IDs ("session-msg-{index}") so ChatViewModel's dedupeUI()
 * would treat two messages as "different" if their position in the
 * session.messages array changed across syncs.
 *
 * After the fix: each assistant message gets an ID derived from its
 * content, so the same content always produces the same ID regardless
 * of position. dedupeUI() then correctly merges replays instead of
 * piling up duplicate-looking ChatItems in the chat list.
 */
class ProtocolParserSessionSyncTest {

    private val parser = ProtocolParser

    private fun sessionSyncJson(messages: List<Pair<String, String>>): String {
        val msgsJson = messages.joinToString(",") { (role, content) ->
            """{"role":${jsonStr(role)},"content":${jsonStr(content)}}"""
        }
        return """{"type":"session_sync","session":{"session_id":"s1","messages":[$msgsJson]}}"""
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun extractAssistantTurns(result: ParseResult): List<ChatItem.Turn> {
        val out = mutableListOf<ChatItem.Turn>()
        (result.event as? ConnectionEvent.ChatItemReceived)?.let { ev ->
            (ev.item as? ChatItem.Turn)?.let { out += it }
        }
        result.extraEvents.forEach { ev ->
            (ev as? ConnectionEvent.ChatItemReceived)?.let { cev ->
                (cev.item as? ChatItem.Turn)?.let { out += it }
            }
        }
        return out
    }

    // ── Core regression: same content must produce same id regardless of position ──

    @Test
    fun `session_sync produces same id for same content at different positions`() {
        // reply1 is at index 1 in the first sync (1 assistant surrounded by 1 user).
        // reply1 is at index 1 in the second sync too — but if the parser uses
        // positional IDs, then the IDs happen to match. The real test below
        // exposes the bug by varying which assistant is at which index.
        val textA = sessionSyncJson(
            listOf("user" to "Hi", "assistant" to "reply1")
        )
        val textB = sessionSyncJson(
            // Same reply1 content but now at absolute index 3 (after 3 user msgs).
            listOf(
                "user" to "u1",
                "user" to "u2",
                "user" to "u3",
                "assistant" to "reply1"
            )
        )
        val itemsA = extractAssistantTurns(parser.parse(textA, emptySet(), false))
        val itemsB = extractAssistantTurns(parser.parse(textB, emptySet(), false))

        assertEquals(1, itemsA.size)
        assertEquals(1, itemsB.size)
        assertEquals(
            "reply1 at index 1 and reply1 at index 3 must share an id. " +
                "If they differ, the id depends on position, which means " +
                "dedupeUI will treat them as separate items across syncs " +
                "and visually push the original reply down the chat list.",
            itemsA[0].id,
            itemsB[0].id
        )
    }

    @Test
    fun `session_sync replays across three turns keep each assistant id stable`() {
        // Real bug scenario: turn 3 server sends session_sync with 3 assistant
        // messages. After the fix, reply1/reply2/reply3 must each carry the
        // same id regardless of their absolute index, so dedupeUI() merges
        // them with the previous turns' identical replies instead of producing
        // "phantom duplicates" at the bottom of the chat list.
        val turn1 = sessionSyncJson(listOf("user" to "Hi", "assistant" to "reply1"))
        val turn2 = sessionSyncJson(
            listOf(
                "user" to "Hi",
                "assistant" to "reply1",
                "user" to "Who?",
                "assistant" to "reply2"
            )
        )
        val turn3 = sessionSyncJson(
            listOf(
                "user" to "Hi",
                "assistant" to "reply1",
                "user" to "Who?",
                "assistant" to "reply2",
                "user" to "Chinese?",
                "assistant" to "reply3"
            )
        )

        val a1 = extractAssistantTurns(parser.parse(turn1, emptySet(), false)).single()
        val a2 = extractAssistantTurns(parser.parse(turn2, emptySet(), false))
        val a3 = extractAssistantTurns(parser.parse(turn3, emptySet(), false))

        // reply1's id is stable: it appears at absolute index 1 in all three
        // syncs, so even positional IDs would agree here. Use content as the
        // ground truth.
        assertEquals(
            "reply1 id must be stable across syncs (by content, not position)",
            "reply1",
            a1.agent?.content
        )

        // reply2 first appears in turn 2 at absolute index 3; in turn 3 also
        // at absolute index 3. With positional IDs both give "session-msg-3"
        // — this test passes by accident. The real discriminator is below:
        // we synthesize a sync where reply2 lands at a *different* absolute
        // index and confirm id still tracks content.
        val reply2AtDifferentIndex = sessionSyncJson(
            listOf(
                "user" to "preamble-1",
                "user" to "preamble-2",
                "user" to "preamble-3",
                "user" to "Who?",
                "assistant" to "reply2"
            )
        )
        val reply2At7 = extractAssistantTurns(parser.parse(reply2AtDifferentIndex, emptySet(), false)).single()
        val reply2InTurn2 = a2.first { it.agent?.content == "reply2" }
        assertEquals(
            "reply2 at index 3 (turn 2) and reply2 at index 7 (synthesized) " +
                "must share an id. Positional ids would give 'session-msg-3' " +
                "vs 'session-msg-7' — distinct ids that break dedupeUI.",
            reply2InTurn2.id,
            reply2At7.id
        )

        // reply3 only appears in turn 3; verify it gets a unique id different
        // from reply1 / reply2.
        val reply3 = a3.first { it.agent?.content == "reply3" }
        assertNotEquals("reply3 id must differ from reply1", a1.id, reply3.id)
        assertNotEquals("reply3 id must differ from reply2", reply2InTurn2.id, reply3.id)
    }

    @Test
    fun `session_sync emits different ids for different assistant content`() {
        val textA = sessionSyncJson(listOf("assistant" to "reply A"))
        val textB = sessionSyncJson(listOf("assistant" to "reply B"))

        val idA = extractAssistantTurns(parser.parse(textA, emptySet(), false)).single().id
        val idB = extractAssistantTurns(parser.parse(textB, emptySet(), false)).single().id

        assertNotEquals(
            "Different assistant content must produce different ids",
            idA,
            idB
        )
    }

    @Test
    fun `session_sync tolerates a message with no content field instead of dropping the whole frame`() {
        val json = """{"type":"session_sync","session":{"session_id":"s1","messages":[
            {"role":"user","content":"Hi"},
            {"role":"assistant"},
            {"role":"assistant","content":"reply1"}
        ]}}""".trimIndent()

        val result = parser.parse(json, emptySet(), false)
        val items = extractAssistantTurns(result)

        assertEquals(
            "The content-less assistant message must be skipped, not crash " +
                "the whole frame — the real reply1 turn must still come through",
            1,
            items.size
        )
        assertEquals("reply1", items[0].agent?.content)
    }

    @Test
    fun `session_sync id does not use legacy positional session-msg-N scheme`() {
        // Anti-regression: the old code emitted "session-msg-0", "session-msg-1"
        // etc. Make sure the new id scheme does NOT start with that prefix.
        val text = sessionSyncJson(
            listOf("user" to "Hi", "assistant" to "any reply")
        )
        val items = extractAssistantTurns(parser.parse(text, emptySet(), false))
        assertEquals(1, items.size)
        assertTrue(
            "Assistant id must not use the legacy positional scheme (was \"session-msg-N\"), got: ${items[0].id}",
            !items[0].id.startsWith("session-msg-")
        )
    }

    /**
     * Once any message in the history carries an image, the server switches
     * that message's `content` from a string to an array of typed blocks.
     * A String-only field aborted the whole frame, so a single image
     * silently killed every session_sync and OUTPUT for that conversation —
     * observed on device as a sent message that never got a reply.
     */
    @Test
    fun `a multimodal content array does not abort the frame`() {
        val text = """
            {"type":"session_sync","session":{"session_id":"s1","messages":[
              {"role":"user","content":[
                {"type":"text","text":"describe this"},
                {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}
              ]},
              {"role":"assistant","content":"it is a cat"}
            ]}}
        """.trimIndent()

        val items = extractAssistantTurns(parser.parse(text, emptySet(), false))
        assertEquals("the assistant reply must survive the image-bearing entry", 1, items.size)
    }

    @Test
    fun `text blocks are kept and image blocks dropped`() {
        val text = """
            {"type":"session_sync","session":{"session_id":"s1","messages":[
              {"role":"assistant","content":[
                {"type":"text","text":"first"},
                {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}},
                {"type":"text","text":"second"}
              ]}
            ]}}
        """.trimIndent()

        val items = extractAssistantTurns(parser.parse(text, emptySet(), false))
        assertEquals(1, items.size)
        assertEquals("first\nsecond", items[0].agent?.content)
    }
}
