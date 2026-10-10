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
    fun `a refused protocol version names both versions and what to do`() {
        assertEquals(
            "This agent uses a different version of the chat protocol (oip/0.2) than this app (oip/0.1). " +
                "Update the app or the agent, then reconnect.",
            ServerErrorText.incompatibleProtocol(hostProtocol = "oip/0.2", clientProtocol = "oip/0.1")
        )
    }

    @Test
    fun `a refusal that did not say the Host's version still reads clearly`() {
        assertEquals(
            "This agent doesn't support this app's version of the chat protocol (oip/0.1). " +
                "Update the app or the agent, then reconnect.",
            ServerErrorText.incompatibleProtocol(hostProtocol = null, clientProtocol = "oip/0.1")
        )
    }

    @Test
    fun `a stale-session rejection is not an offline verdict`() {
        // The other definitive-looking error, which has its own recovery path
        // and does want the second attempt.
        assertFalse(ServerErrorText.isOfflineVerdict("Session is already attached to another connection"))
    }

    /**
     * auth.py `_authenticate_signed` refuses any signature whose timestamp is
     * more than 300s from the Host's clock. Since W16AAPPLE-16 signs every
     * command, a phone with a wrong clock has every message refused, and a
     * correct client hits this error for no other reason, so the user should be
     * told to check their date and time (W16AAPPLE-90). This only asks that the
     * wording names the cause.
     */
    @Test
    fun `an expired signature is explained as the device clock`() {
        val shown = ServerErrorText.humanize("unauthorized: signature expired")
        assertTrue("shown: $shown", shown.contains("date and time", ignoreCase = true))
    }

    @Test
    fun `an expired signature is neither a stale session nor an offline agent`() {
        // Either reading would send the connection down a recovery path
        // (a fresh session, or waiting for the agent) that cannot fix a clock.
        // Checked on the humanized line, so it guards the wording
        // the user actually reads.
        val shown = ServerErrorText.humanize("unauthorized: signature expired")
        assertFalse(StaleSessionDetector.isStaleSessionError(shown))
        assertFalse(ServerErrorText.isOfflineVerdict(shown))
    }

    @Test
    fun `other signature refusals survive verbatim`() {
        // A correct client never causes these, so there is no advice to give,
        // and the raw text is the useful thing in a bug report.
        assertEquals("unauthorized: invalid signature", ServerErrorText.humanize("unauthorized: invalid signature"))
        assertEquals(
            "unauthorized: signed command already used",
            ServerErrorText.humanize("unauthorized: signed command already used")
        )
    }

    @Test
    fun `an unmapped server error survives verbatim`() {
        assertEquals("Insufficient ConnectOnion Credits", ServerErrorText.humanize("Insufficient ConnectOnion Credits"))
    }
}
