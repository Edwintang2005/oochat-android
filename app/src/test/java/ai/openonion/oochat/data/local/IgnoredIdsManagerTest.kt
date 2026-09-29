package ai.openonion.oochat.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [IgnoredIdsManager], extracted from
 * [ai.openonion.oochat.ui.chat.ChatViewModel].
 */
class IgnoredIdsManagerTest {

    private class FakeIgnoredIdsStorage : IgnoredIdsStorage {
        val data = mutableMapOf<String, Set<String>>()
        var saveCount = 0

        override suspend fun loadAll(): Map<String, Set<String>> = data.toMap()

        override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {
            saveCount++
            data[agentAddress] = ids
        }
    }

    private lateinit var storage: FakeIgnoredIdsStorage
    private lateinit var manager: IgnoredIdsManager

    @Before
    fun setUp() {
        storage = FakeIgnoredIdsStorage()
        manager = IgnoredIdsManager(storage)
    }

    @Test
    fun `ignoredIdsFor returns empty set for an agent that has never been cleared`() {
        assertTrue(manager.ignoredIdsFor("0xabc").isEmpty())
    }

    @Test
    fun `loadAll hydrates ignored ids from storage`() = runTest {
        storage.data["0xabc"] = setOf("id1", "id2")

        manager.loadAll()

        assertEquals(setOf("id1", "id2"), manager.ignoredIdsFor("0xabc"))
    }

    @Test
    fun `ignoreAll adds ids to the agent's set and persists it`() = runTest {
        manager.ignoreAll("0xabc", listOf("id1", "id2"))

        assertEquals(setOf("id1", "id2"), manager.ignoredIdsFor("0xabc"))
        assertEquals(1, storage.saveCount)
        assertEquals(setOf("id1", "id2"), storage.data["0xabc"])
    }

    @Test
    fun `ignoreAll accumulates across multiple calls for the same agent`() = runTest {
        manager.ignoreAll("0xabc", listOf("id1"))
        manager.ignoreAll("0xabc", listOf("id2"))

        assertEquals(setOf("id1", "id2"), manager.ignoredIdsFor("0xabc"))
    }

    @Test
    fun `ignoreAll for one agent does not affect another agent's ignore set`() = runTest {
        manager.ignoreAll("0xagentA", listOf("id1"))
        manager.ignoreAll("0xagentB", listOf("id2"))

        assertEquals(setOf("id1"), manager.ignoredIdsFor("0xagentA"))
        assertEquals(setOf("id2"), manager.ignoredIdsFor("0xagentB"))
    }
}
