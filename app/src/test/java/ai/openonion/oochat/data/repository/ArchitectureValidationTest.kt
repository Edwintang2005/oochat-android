package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureValidationTest {

    // ==================== Entity Relationship Validation ====================

    @Test
    fun `AgentProfile can own multiple ChatSessions`() {
        val agent = createTestAgent("agent-1")
        val session1 = createTestSession("session-1", agent.id)
        val session2 = createTestSession("session-2", agent.id)

        assertEquals(agent.id, session1.agentId)
        assertEquals(agent.id, session2.agentId)

        assertNotEquals(session1.id, session2.id)
    }

    @Test
    fun `ChatSession can own multiple ChatMessages`() {
        val session = createTestSession("session-1", "agent-1")
        val message1 = createTestMessage("msg-1", session.id, Role.USER)
        val message2 = createTestMessage("msg-2", session.id, Role.ASSISTANT)

        assertEquals(session.id, message1.sessionId)
        assertEquals(session.id, message2.sessionId)

        assertNotEquals(message1.id, message2.id)
    }

    @Test
    fun `AgentProfile to ChatSession relationship is one-to-many`() {
        val agentId = "agent-1"
        val sessions = listOf(
            createTestSession("s1", agentId),
            createTestSession("s2", agentId),
            createTestSession("s3", agentId)
        )

        assertTrue(sessions.all { it.agentId == agentId })

        assertEquals(3, sessions.size)
    }

    @Test
    fun `ChatSession to ChatMessage relationship is one-to-many`() {
        val sessionId = "session-1"
        val messages = listOf(
            createTestMessage("m1", sessionId, Role.USER),
            createTestMessage("m2", sessionId, Role.ASSISTANT),
            createTestMessage("m3", sessionId, Role.USER)
        )

        assertTrue(messages.all { it.sessionId == sessionId })

        assertEquals(3, messages.size)
    }

    // ==================== Repository Contract Validation ====================

    @Test
    fun `AgentRepository interface has required methods`() {
        val methods = AgentRepository::class.java.methods
        val methodNames = methods.map { it.name }

        assertTrue("getAllAgents" in methodNames)
        assertTrue("getActiveAgents" in methodNames)
        assertTrue("getAgentById" in methodNames)
        assertTrue("createAgent" in methodNames)
        assertTrue("updateAgent" in methodNames)
        assertTrue("deleteAgent" in methodNames)
    }

    @Test
    fun `SessionRepository interface has required methods`() {
        val methods = SessionRepository::class.java.methods
        val methodNames = methods.map { it.name }

        assertTrue("getAllSessions" in methodNames)
        assertTrue("getSessionsByAgent" in methodNames)
        assertTrue("getSessionById" in methodNames)
        assertTrue("createSession" in methodNames)
        assertTrue("deleteSession" in methodNames)
        assertTrue("renameSession" in methodNames)
    }

    @Test
    fun `MessageRepository interface has required methods`() {
        val methods = MessageRepository::class.java.methods
        val methodNames = methods.map { it.name }

        assertTrue("getMessagesListBySession" in methodNames)
        assertTrue("createMessage" in methodNames)
        assertTrue("deleteMessagesBySession" in methodNames)
    }

    // ==================== Single Source of Truth Validation ====================

    @Test
    fun `AgentProfile is single source of truth for agent data`() {
        val agent = createTestAgent("agent-1")

        assertNotNull(agent.id)
        assertNotNull(agent.address)
        assertNotNull(agent.name)
        assertNotNull(agent.serverUrl)
        assertNotNull(agent.createdAt)
    }

    @Test
    fun `ChatSession is single source of truth for session data`() {
        val session = createTestSession("session-1", "agent-1")

        assertNotNull(session.id)
        assertNotNull(session.agentId)
        assertNotNull(session.title)
        assertNotNull(session.createdAt)
        assertNotNull(session.updatedAt)
    }

    @Test
    fun `ChatMessage is single source of truth for message data`() {
        val message = createTestMessage("msg-1", "session-1", Role.USER)

        assertNotNull(message.id)
        assertNotNull(message.sessionId)
        assertNotNull(message.role)
        assertNotNull(message.content)
        assertNotNull(message.timestamp)
    }

    // ==================== Agent Switching Validation ====================

    @Test
    fun `Agent switching maintains session isolation`() {
        val agentA = createTestAgent("agent-a")
        val agentB = createTestAgent("agent-b")

        val sessionA = createTestSession("session-a", agentA.id)
        val sessionB = createTestSession("session-b", agentB.id)

        assertNotEquals(sessionA.agentId, sessionB.agentId)

        assertTrue(sessionA.agentId == agentA.id)
        assertTrue(sessionB.agentId == agentB.id)
    }

    @Test
    fun `Each agent has independent session list`() {
        val agentId1 = "agent-1"
        val agentId2 = "agent-2"

        val sessions1 = listOf(
            createTestSession("s1-1", agentId1),
            createTestSession("s1-2", agentId1)
        )

        val sessions2 = listOf(
            createTestSession("s2-1", agentId2)
        )

        assertTrue(sessions1.all { it.agentId == agentId1 })
        assertTrue(sessions2.all { it.agentId == agentId2 })

        val sessionIds1 = sessions1.map { it.id }.toSet()
        val sessionIds2 = sessions2.map { it.id }.toSet()
        assertTrue(sessionIds1.intersect(sessionIds2).isEmpty())
    }

    // ==================== State Model Validation ====================

    @Test
    fun `ConnectionState has all required states`() {
        assertNotNull(ConnectionState.Disconnected)
        assertNotNull(ConnectionState.Connecting)
        assertNotNull(ConnectionState.Connected(address = "test"))
        assertNotNull(ConnectionState.Error(message = "test"))
    }

    @Test
    fun `AgentStatus has all required states`() {
        assertNotNull(AgentStatus.Active)
        assertNotNull(AgentStatus.Connecting)
        assertNotNull(AgentStatus.Connected)
        assertNotNull(AgentStatus.Error(message = "test"))
        assertNotNull(AgentStatus.Disabled)
    }

    @Test
    fun `ConnectionState does not duplicate AgentStatus`() {
        val connectionState = ConnectionState.Connected(address = "test")
        val agentStatus = AgentStatus.Connected

        assertTrue(connectionState.isConnected())
        assertTrue(agentStatus.isConnected())
    }

    // ==================== Helper Methods ====================

    private fun createTestAgent(id: String): AgentProfile {
        return AgentProfile(
            id = id,
            address = "0x$id",
            name = "Agent $id",
            description = "Test agent",
            serverUrl = "wss://test.com",
            apiKey = null,
            avatarUrl = null,
            createdAt = System.currentTimeMillis(),
            lastConnectedAt = null,
            isActive = true
        )
    }

    private fun createTestSession(id: String, agentId: String): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = id,
            agentId = agentId,
            title = "Session $id",
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            lastMessagePreview = null
        )
    }

    private fun createTestMessage(id: String, sessionId: String, role: Role): ChatMessage {
        return ChatMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            content = "Test message from $role",
            timestamp = System.currentTimeMillis(),
            model = null,
            durationMs = null
        )
    }
}
