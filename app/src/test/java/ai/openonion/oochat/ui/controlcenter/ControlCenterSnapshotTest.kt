package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSnapshotTest {

    private fun of(
        activity: SessionActivity? = SessionActivity.IDLE,
        connection: ConnectionState = ConnectionState.Connected(address = "0xagent"),
        skills: List<AgentSkill> = emptyList()
    ) = ControlCenterSnapshot.of("conv-1", "0xagent", activity, connection, skills)

    @Test
    fun `the agent's status is the chat's own status line`() {
        assertEquals(ControlCenterAgentStatus.WORKING, of(SessionActivity.RUNNING).status)
        assertEquals(ControlCenterAgentStatus.WAITING, of(SessionActivity.AWAITING_USER).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(SessionActivity.IDLE).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(SessionActivity.NOT_FOUND).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(null).status)
    }

    @Test
    fun `only a live connection reads as connected, and only a reconnect as reconnecting`() {
        assertEquals(ControlCenterConnection.CONNECTED, of(connection = ConnectionState.Connected(address = "0xagent")).connectionState)
        assertEquals(ControlCenterConnection.RECONNECTING, of(connection = ConnectionState.Reconnecting).connectionState)
        listOf(ConnectionState.Idle, ConnectionState.Connecting, ConnectionState.Disconnected, ConnectionState.Error("x"))
            .forEach { assertEquals(it.toString(), ControlCenterConnection.DISCONNECTED, of(connection = it).connectionState) }
    }

    @Test
    fun `skills are cut to what the app's SDK accepts`() {
        val many = (0 until 2001).map { AgentSkill(name = "s$it") }
        val long = AgentSkill(name = "n".repeat(70), description = "d".repeat(300))

        val bounded = of(skills = many)
        val trimmed = of(skills = listOf(long, AgentSkill(name = "plain", description = " "))).skills

        assertEquals(2000, bounded.skills.size)
        assertTrue("dropping skills is reported", bounded.truncated)
        assertFalse(of(skills = many.take(5)).truncated)
        assertEquals(64, trimmed[0].name.length)
        assertEquals(256, trimmed[0].description?.length)
        assertNull("a blank description is left out", trimmed[1].description)
    }
}
