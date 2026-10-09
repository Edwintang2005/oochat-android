package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What Setup's QR scanner makes of a code — see [AgentQrCode].
 */
class AgentQrCodeTest {

    private val address = "0x" + "3d40".repeat(16)
    private val relayed = ScannedAgent(serverUrl = Constants.DEFAULT_SERVER_URL, agentAddress = address)

    // ── the website's Share QR ────────────────────────────────────────

    @Test
    fun `the website's Share QR connects to the agent through the default relay`() {
        // What oo-chat's qr-share.tsx encodes. Read as <serverUrl>/<address>
        // it saved the website as a direct server, which has no WebSocket, so
        // the agent never connected (W16AAPPLE-76).
        assertEquals(relayed, AgentQrCode.parse("https://chat.openonion.ai/$address"))
    }

    @Test
    fun `the website's old domain is read the same way`() {
        assertEquals(relayed, AgentQrCode.parse("https://oo-chat.com/$address"))
    }

    @Test
    fun `a website conversation link names its agent, and the session is dropped`() {
        // The session belongs to the browser's identity; the host would give
        // this phone a new one anyway.
        assertEquals(relayed, AgentQrCode.parse("https://chat.openonion.ai/$address/5f0c2c1e-9a8b-4c3d-8e7f-0a1b2c3d4e5f"))
    }

    @Test
    fun `a website link is read whatever case, trailing slash, query or fragment it has`() {
        listOf(
            "HTTPS://Chat.OpenOnion.AI/$address",
            "http://chat.openonion.ai/$address",
            "https://chat.openonion.ai/$address/",
            "https://chat.openonion.ai/$address?ref=share",
            "https://chat.openonion.ai/$address#top",
            "  https://chat.openonion.ai/$address\n"
        ).forEach { scanned ->
            assertEquals(scanned, relayed, AgentQrCode.parse(scanned))
        }
    }

    @Test
    fun `a website page that names no agent is not an agent QR code`() {
        // Falling through to <serverUrl>/<address> would make "explore" the
        // address and the website the server.
        listOf(
            "https://chat.openonion.ai",
            "https://chat.openonion.ai/",
            "https://chat.openonion.ai/explore",
            "https://chat.openonion.ai/0x1234"
        ).forEach { scanned ->
            assertNull(scanned, AgentQrCode.parse(scanned))
        }
    }

    // ── the app's own deep link ───────────────────────────────────────

    @Test
    fun `the app's deep link connects through the default relay`() {
        assertEquals(relayed, AgentQrCode.parse("connectonion://agent/$address"))
    }

    @Test
    fun `a deep link with no valid address is not an agent QR code`() {
        assertNull(AgentQrCode.parse("connectonion://agent/"))
        assertNull(AgentQrCode.parse("connectonion://agent/not-an-address"))
    }

    // ── <serverUrl>/<address> ─────────────────────────────────────────

    @Test
    fun `a server URL and address connect to that server`() {
        assertEquals(
            ScannedAgent(serverUrl = "https://agent.example.com:8000", agentAddress = address),
            AgentQrCode.parse("https://agent.example.com:8000/$address")
        )
    }

    @Test
    fun `a server URL with a path keeps the path`() {
        assertEquals(
            ScannedAgent(serverUrl = "https://example.com/agents", agentAddress = address),
            AgentQrCode.parse("https://example.com/agents/$address")
        )
    }

    @Test
    fun `the default relay's URL and an address stay on the relay`() {
        assertEquals(relayed, AgentQrCode.parse("${Constants.DEFAULT_SERVER_URL}/$address"))
    }

    @Test
    fun `a trailing slash after the address is ignored`() {
        // It used to leave nothing after the last "/", and the scan was
        // silently dropped.
        assertEquals(
            ScannedAgent(serverUrl = "http://192.168.1.20:8000", agentAddress = address),
            AgentQrCode.parse("http://192.168.1.20:8000/$address/")
        )
    }

    // ── not an agent QR code ──────────────────────────────────────────

    @Test
    fun `text with no server and address is not an agent QR code`() {
        // A bare server URL used to split into a server of "https:/" and an
        // address of the host name.
        listOf("", "   ", "hello", "/$address", "https://example.com", "https://example.com/").forEach { scanned ->
            assertNull("\"$scanned\"", AgentQrCode.parse(scanned))
        }
    }
}
