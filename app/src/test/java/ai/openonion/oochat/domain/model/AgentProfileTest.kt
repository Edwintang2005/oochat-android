package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileTest {

    @Test
    fun `required fields are set correctly`() {
        val agent = createTestAgent()

        assertEquals("agent-1", agent.id)
        assertEquals("0xabc123", agent.address)
        assertEquals("My Agent", agent.name)
        assertEquals("https://server.example.com", agent.serverUrl)
        assertEquals(1000L, agent.createdAt)
    }

    @Test
    fun `optional fields default to null`() {
        val agent = createTestAgent()

        assertNull(agent.description)
        assertNull(agent.apiKey)
        assertNull(agent.avatarUrl)
        assertNull(agent.lastConnectedAt)
    }

    @Test
    fun `isActive defaults to true`() {
        assertTrue(createTestAgent().isActive)
    }

    @Test
    fun `connectionMode defaults to relay`() {
        assertEquals("relay", createTestAgent().connectionMode)
    }

    @Test
    fun `nullable fields can be set to non-null values`() {
        val agent = createTestAgent().copy(
            description = "Description",
            apiKey = "key-123",
            avatarUrl = "https://avatar.example.com",
            lastConnectedAt = 2000L
        )

        assertEquals("Description", agent.description)
        assertEquals("key-123", agent.apiKey)
        assertEquals("https://avatar.example.com", agent.avatarUrl)
        assertEquals(2000L, agent.lastConnectedAt)
    }

    @Test
    fun `connectionMode accepts relay and direct`() {
        val relay = createTestAgent().copy(connectionMode = "relay")
        val direct = createTestAgent().copy(connectionMode = "direct")

        assertEquals("relay", relay.connectionMode)
        assertEquals("direct", direct.connectionMode)
    }

    @Test
    fun `two instances with same fields are equal`() {
        val a = createTestAgent()
        val b = createTestAgent()
        assertEquals(a, b)
    }

    @Test
    fun `two instances with different ids are not equal`() {
        val a = createTestAgent().copy(id = "id-1")
        val b = createTestAgent().copy(id = "id-2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves all fields except changed one`() {
        val original = createTestAgent()
        val copied = original.copy(name = "New Name")

        assertEquals(original.id, copied.id)
        assertEquals(original.address, copied.address)
        assertEquals("New Name", copied.name)
        assertEquals(original.serverUrl, copied.serverUrl)
        assertEquals(original.createdAt, copied.createdAt)
        assertEquals(original.isActive, copied.isActive)
        assertEquals(original.connectionMode, copied.connectionMode)
    }

    private fun createTestAgent() = AgentProfile(
        id = "agent-1",
        address = "0xabc123",
        name = "My Agent",
        serverUrl = "https://server.example.com",
        createdAt = 1000L
    )
}
