package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cost of [ChatListFlags.of], not just its answers.
 *
 * The four flags used to be four independent `any {}` scans, each restarting
 * from the front, and every one of them re-ran on every server event — so a
 * turn emitting dozens of frames walked a long conversation dozens of times
 * over. Collapsing them into one loop is invisible to an assertion about the
 * returned value, which is exactly why it would come back unnoticed.
 *
 * These tests read a counting list instead, so a regression to `any {}` chains
 * fails loudly rather than quietly costing 4x on the hottest path in the app.
 */
class ChatListFlagsPerformanceTest {

    /**
     * Counts element reads. Delegates the rest to a real list, so the code
     * under test sees an ordinary [List] and cannot take a different path.
     */
    private class CountingList<T>(private val backing: List<T>) : List<T> by backing {
        var reads = 0
            private set

        override fun iterator(): Iterator<T> {
            val inner = backing.iterator()
            return object : Iterator<T> {
                override fun hasNext() = inner.hasNext()
                override fun next(): T {
                    reads++
                    return inner.next()
                }
            }
        }
    }

    private fun settledItems(n: Int): List<ChatItem> =
        (0 until n).map { ChatItem.Agent(id = "a$it", content = "reply $it") }

    @Test
    fun `reads each item exactly once, even when every flag stays false`() {
        // The all-false case is the one that matters: a flag that is never set
        // is a scan that never short-circuits, so four `any {}` calls would
        // each read the whole list.
        val items = CountingList(settledItems(200))

        val flags = ChatListFlags.of(items)

        assertEquals(200, items.reads)
        assertEquals(ChatListFlags.NONE, flags)
    }

    @Test
    fun `still reads each item once when a flag is set by the very first item`() {
        // Guards against "fixing" the count with an early return: the loop has
        // four independent accumulators, so it cannot stop at the first hit
        // without dropping the other three answers.
        val items = CountingList(
            listOf(ChatItem.AskUser(id = "ask", text = "which one?", options = listOf("a", "b"), multiSelect = false)) + settledItems(199)
        )

        val flags = ChatListFlags.of(items)

        assertEquals(200, items.reads)
        assertTrue(flags.hasPendingGate)
    }

    @Test
    fun `cost is linear in list length, not quadratic`() {
        // Pins the shape rather than a constant: 10x the items must cost 10x
        // the reads. A nested scan would land at 100x and fail here.
        val small = CountingList(settledItems(50))
        val large = CountingList(settledItems(500))

        ChatListFlags.of(small)
        ChatListFlags.of(large)

        assertEquals(50, small.reads)
        assertEquals(500, large.reads)
    }

    @Test
    fun `one walk still answers all four questions independently`() {
        // The perf tests above are only meaningful if the single pass is
        // actually correct, so pin a list where all four differ.
        val items = listOf(
            ChatItem.ToolCall(id = "tc", name = "search", status = ToolStatus.RUNNING),
            ChatItem.OnboardRequired(id = "req", methods = listOf("invite")),
            ChatItem.OnboardSuccess(id = "ok", level = "basic", message = "welcome"),
            ChatItem.AskUser(id = "ask", text = "which one?", options = listOf("a", "b"), multiSelect = false)
        )

        val flags = ChatListFlags.of(items)

        assertTrue(flags.hasInProgress)
        assertTrue(flags.hasOnboardPrompt)
        assertTrue(flags.hasOnboardSuccess)
        assertTrue(flags.hasPendingGate)
    }
}
