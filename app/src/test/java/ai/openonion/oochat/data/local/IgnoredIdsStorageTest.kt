package ai.openonion.oochat.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the storage layer behind ChatViewModel.ignoredIds.
 *
 * The storage is keyed by agent address so a clear-history in one agent
 * doesn't suppress the same chat-id family in another agent (defensive
 * — ids happen to be content-derived hashes today, but if we ever
 * switch to e.g. UUID per ChatItem the cross-agent bleed-through would
 * become a real bug).
 */
class IgnoredIdsStorageTest {

    /** In-memory fake mirroring the production interface. */
    private class FakeIgnoredIdsStorage : IgnoredIdsStorage {
        private var backing: Map<String, Set<String>> = emptyMap()
        var saveCount: Int = 0

        override suspend fun loadAll(): Map<String, Set<String>> = backing
        override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {
            backing = backing + (agentAddress to ids)
            saveCount++
        }
    }

    @Test
    fun `loadAll returns empty map when nothing has been saved`() = runBlocking {
        val storage = FakeIgnoredIdsStorage()
        assertTrue(
            "Fresh storage must report an empty ignored-ids map",
            storage.loadAll().isEmpty()
        )
    }

    @Test
    fun `saveForAgent then loadAll returns the saved ids under that agent`() = runBlocking {
        val storage = FakeIgnoredIdsStorage()
        val ids = setOf("turn-A", "turn-B")
        storage.saveForAgent("0xA1", ids)
        assertEquals(mapOf("0xA1" to ids), storage.loadAll())
    }

    @Test
    fun `two agents keep separate id sets`() = runBlocking {
        val storage = FakeIgnoredIdsStorage()
        storage.saveForAgent("0xA1", setOf("a", "b"))
        storage.saveForAgent("0xB2", setOf("c"))
        val all = storage.loadAll()
        assertEquals(setOf("a", "b"), all["0xA1"])
        assertEquals(setOf("c"), all["0xB2"])
        assertEquals(2, all.size)
    }

    @Test
    fun `saveForAgent replaces only the entries for that agent`() = runBlocking {
        val storage = FakeIgnoredIdsStorage()
        storage.saveForAgent("0xA1", setOf("a", "b"))
        storage.saveForAgent("0xB2", setOf("c"))
        storage.saveForAgent("0xA1", setOf("x", "y", "z"))
        val all = storage.loadAll()
        assertEquals(setOf("x", "y", "z"), all["0xA1"])
        assertEquals(setOf("c"), all["0xB2"])
        assertEquals(2, all.size)
    }

    @Test
    fun `saveForAgent with empty set still keeps the agent entry`() = runBlocking {
        // save() REPLACES, so passing emptySet must overwrite with
        // an empty set (not delete the key). This matches the
        // contract for clearChat's snapshot: after a clear, the agent
        // still has an entry — it's just empty.
        val storage = FakeIgnoredIdsStorage()
        storage.saveForAgent("0xA1", setOf("a"))
        storage.saveForAgent("0xA1", emptySet())
        val all = storage.loadAll()
        assertTrue("Agent entry must still exist after empty save", all.containsKey("0xA1"))
        assertTrue("Empty save yields empty set", all["0xA1"]!!.isEmpty())
    }

    @Test
    fun `each saveForAgent call increments saveCount`() = runBlocking {
        val storage = FakeIgnoredIdsStorage()
        storage.saveForAgent("0xA1", setOf("a"))
        storage.saveForAgent("0xB2", setOf("b"))
        storage.saveForAgent("0xA1", setOf("c"))
        assertEquals(3, storage.saveCount)
    }
}
