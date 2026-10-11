package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SessionActivity.of]: the top bar's status line (W16AAPPLE-18). */
class SessionActivityTest {

    private fun of(
        connected: Boolean = true,
        turnRunning: Boolean = false,
        awaitsUser: Boolean = false,
        host: ServerSessionStatus = ServerSessionStatus.UNKNOWN
    ) = SessionActivity.of(connected, turnRunning, awaitsUser, host)

    @Test
    fun `nothing is shown while not connected, whatever the Host last said`() {
        ServerSessionStatus.entries.forEach { host ->
            assertNull(of(connected = false, turnRunning = true, host = host))
        }
    }

    @Test
    fun `idle when connected and nothing is running`() {
        assertEquals(SessionActivity.IDLE, of(host = ServerSessionStatus.UNKNOWN))
        assertEquals(SessionActivity.IDLE, of(host = ServerSessionStatus.NEW))
        assertEquals(SessionActivity.IDLE, of(host = ServerSessionStatus.CONNECTED))
    }

    @Test
    fun `running from this app's own turn or from the Host's answer`() {
        assertEquals(SessionActivity.RUNNING, of(turnRunning = true))
        assertEquals(SessionActivity.RUNNING, of(host = ServerSessionStatus.RUNNING))
    }

    @Test
    fun `not found from a SESSION_STATUS reply`() {
        assertEquals(SessionActivity.NOT_FOUND, of(host = ServerSessionStatus.NOT_FOUND))
    }

    @Test
    fun `a turn this app starts outranks an old not_found`() {
        assertEquals(SessionActivity.RUNNING, of(turnRunning = true, host = ServerSessionStatus.NOT_FOUND))
    }

    @Test
    fun `an open gate outranks a running turn`() {
        assertEquals(
            SessionActivity.AWAITING_USER,
            of(turnRunning = true, awaitsUser = true, host = ServerSessionStatus.RUNNING)
        )
    }

    @Test
    fun `SESSION_STATUS wire values map onto the statuses`() {
        assertEquals(ServerSessionStatus.RUNNING, ServerSessionStatus.fromWire("running"))
        assertEquals(ServerSessionStatus.CONNECTED, ServerSessionStatus.fromWire("connected"))
        assertEquals(ServerSessionStatus.NOT_FOUND, ServerSessionStatus.fromWire("not_found"))
        assertEquals(ServerSessionStatus.UNKNOWN, ServerSessionStatus.fromWire("index"))
    }
}
