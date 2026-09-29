package ai.openonion.oochat.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two-wordings trap in [ServerErrorText]: the mapper runs inside
 * ConnectionRepositoryImpl, so every later reader of the connection state sees
 * the humanized line, never the relay's raw text.
 */
class ServerErrorTextTest {

    @Test
    fun `the relay's raw wording is an offline verdict`() {
        assertTrue(ServerErrorText.isAgentOffline("Agent not connected: 0xc31dd64abc"))
    }

    @Test
    fun `humanize replaces the raw wording with the shared line`() {
        assertEquals(ServerErrorText.AGENT_OFFLINE, ServerErrorText.humanize("Agent not connected: 0xc31dd64abc"))
    }

    @Test
    fun `the humanized line is still the same verdict`() {
        // The whole point: ConnectToAgentUseCase decides whether to retry from
        // connectionState, which is already humanized. Matching only the raw
        // prefix there compiles, passes review, and never fires once.
        val humanized = ServerErrorText.humanize("Agent not connected: 0xc31dd64abc")

        assertFalse("the raw matcher alone cannot see it", ServerErrorText.isAgentOffline(humanized))
        assertTrue(ServerErrorText.isOfflineVerdict(humanized))
    }

    @Test
    fun `a transport failure is not an offline verdict`() {
        // Nothing here says the agent is gone, so this must stay retryable.
        assertFalse(ServerErrorText.isOfflineVerdict("Connection timeout"))
        assertFalse(ServerErrorText.isOfflineVerdict("Network unreachable"))
        assertFalse(ServerErrorText.isOfflineVerdict(""))
    }

    @Test
    fun `a stale-session rejection is not an offline verdict`() {
        // The other definitive-looking error, which has its own recovery path
        // and does want the second attempt.
        assertFalse(ServerErrorText.isOfflineVerdict("Session is already attached to another connection"))
    }

    @Test
    fun `an unmapped server error survives verbatim`() {
        assertEquals("Insufficient ConnectOnion Credits", ServerErrorText.humanize("Insufficient ConnectOnion Credits"))
    }
}
