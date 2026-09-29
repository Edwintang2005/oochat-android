package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for dedupeUI() behavior. The previous implementation
 * kept the LAST occurrence of each id, which made session_sync replays
 * "push" earlier ChatItems to the bottom of the list every turn — that
 * is the visible bug where replies appear to "shift down" after each
 * new user message.
 *
 * After the fix: dedupeUI() must keep the FIRST occurrence of each id,
 * preserving the original position of any item that was already in the
 * list (since it was placed there by a real, in-order event).
 */
class DedupUtilsTest {

    private fun user(id: String) = ChatItem.User(id = id, content = "u-$id")
    private fun thinking(id: String) = ChatItem.Thinking(id = id, status = ThinkingStatus.DONE)
    private fun agent(id: String, content: String = id) =
        ChatItem.Agent(id = id, content = content)

    @Test
    fun `dedupeUI keeps first occurrence so later duplicates do not displace originals`() {
        // Simulates the bug: after session_sync replays the assistant
        // messages, the dedupe pass must NOT push the previously-rendered
        // agent (id="agent-1") to the end of the list. The original
        // position (right after thinking-1) must be preserved.
        val original = listOf(
            user("u-hi"),
            thinking("t-1"),
            agent("agent-1", "first reply"),
            user("u-who"),
            thinking("t-2"),
            agent("agent-2", "second reply")
        )
        val withReplay = original + listOf(
            // session_sync replays both old assistants + adds the new one
            agent("agent-3", "third reply"),
            agent("agent-1", "first reply"),   // replay of agent-1
            agent("agent-2", "second reply")   // replay of agent-2
        )

        val deduped = withReplay.dedupeUI()

        // The replay duplicates must NOT move the originals.
        assertEquals(
            "agent-1 must stay at index 2 (right after thinking-1), " +
                "not be displaced to the end by dedupeUI",
            2,
            deduped.indexOfFirst { it.id == "agent-1" }
        )
        assertEquals(
            "agent-2 must stay at index 5 (right after thinking-2)",
            5,
            deduped.indexOfFirst { it.id == "agent-2" }
        )
        // The new agent from the latest session_sync stays appended at the end.
        assertEquals(
            "agent-3 (the new reply) must be at the end of the list, not earlier",
            deduped.size - 1,
            deduped.indexOfFirst { it.id == "agent-3" }
        )
        // No duplicates of any id.
        assertEquals(deduped.size, deduped.map { it.id }.toSet().size)
    }

    @Test
    fun `dedupeUI preserves order of unique items`() {
        val items = listOf(
            user("1"),
            thinking("2"),
            agent("3", "a"),
            user("4"),
            agent("5", "b")
        )
        assertEquals(items, items.dedupeUI())
    }

    @Test
    fun `dedupeUI removes trailing duplicates without affecting earlier position`() {
        val items = listOf(
            agent("a", "first"),
            agent("b", "second"),
            agent("a", "first duplicate")   // duplicate at the end
        )
        val deduped = items.dedupeUI()
        assertEquals(2, deduped.size)
        // Keep FIRST occurrence → "a" stays at index 0 with original content
        assertEquals("a", deduped[0].id)
        assertEquals("first", (deduped[0] as ChatItem.Agent).content)
        assertEquals("b", deduped[1].id)
    }
}
