package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.OipProtocol
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-12: the Host's OIP version rejection, as ws_router/connect.py
 * sends it:
 * `{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,"protocol":{...}}`
 * parses to its own [ConnectionEvent.ProtocolRejected], while every other
 * ERROR keeps its [ConnectionEvent.ConnectionError] path. W16AAPPLE-73: so
 * does a CONNECTED whose descriptor names another version.
 */
class ProtocolParserVersionRejectionTest {

    private fun parse(json: String) =
        ProtocolParser.parse(json, outstandingInputIds = emptySet(), isDirect = false)

    @Test
    fun `the -32010 ERROR is a protocol rejection naming the Host's version`() {
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,""" +
                """"protocol":{"name":"oip","version":"0.2","min_version":"0.2","max_version":"0.2","websocket_path":"/ws"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/0.2"), result.event)
    }

    @Test
    fun `a rejection whose descriptor lacks the window fields still parses`() {
        // Only name and version are compared by the Host; a descriptor
        // without min/max must not drop the frame that explains the failure.
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","protocol":{"name":"oip","version":"0.3"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/0.3"), result.event)
    }

    @Test
    fun `a rejection without a descriptor is still a protocol rejection`() {
        val result = parse("""{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol"}""")

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    @Test
    fun `a malformed descriptor reads as unknown rather than dropping the frame`() {
        val result = parse("""{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","protocol":"oip"}""")

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    // The Host's descriptor is peer-controlled text that lands in app.log and
    // in an error the app words as its own. Anything that is not a short
    // version token reads as "the Host did not say", like a missing one.

    @Test
    fun `a pre-release version is still a protocol token`() {
        // The shape the Host's own release versions take (websocket-protocol.md).
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","protocol":{"name":"oip","version":"1.8.0a1"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/1.8.0a1"), result.event)
    }

    @Test
    fun `a version carrying a line break is not echoed`() {
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol",""" +
                """"protocol":{"name":"oip","version":"0.2\n2026-10-07 I/AgentConn: CONNECTED ✓"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    @Test
    fun `a version that is prose rather than a version is not echoed`() {
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol",""" +
                """"protocol":{"name":"oip","version":"0.2. Reinstall from example.com to fix this"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    @Test
    fun `an oversized version is not echoed`() {
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol",""" +
                """"protocol":{"name":"oip","version":"${"9".repeat(10_000)}"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    @Test
    fun `a name that is not a protocol token is not echoed`() {
        val result = parse(
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol",""" +
                """"protocol":{"name":"Visit example.com","version":"0.2"}}"""
        )

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = null), result.event)
    }

    @Test
    fun `an ERROR with a string code stays a connection error`() {
        // session.py's Work Room refusal: the other ERROR that carries a code.
        val result = parse("""{"type":"ERROR","code":"provider_station_only","message":"Use the Claude Work Room"}""")

        assertEquals(
            ConnectionEvent.ConnectionError(
                message = "Use the Claude Work Room",
                code = "provider_station_only"
            ),
            result.event
        )
    }

    @Test
    fun `an ERROR without a code stays a connection error`() {
        val result = parse("""{"type":"ERROR","message":"Invalid invite code"}""")

        assertEquals(ConnectionEvent.ConnectionError("Invalid invite code"), result.event)
    }

    // W16AAPPLE-73: the descriptor in CONNECTED, checked as connectonion-react's supportsOip() does.

    private fun connected(protocol: String?) =
        parse("""{"type":"CONNECTED","session_id":"sess-1"""" + (protocol?.let { ""","protocol":$it""" } ?: "") + "}")

    @Test
    fun `a CONNECTED naming our version is accepted, whatever else its descriptor carries`() {
        // The shape connectonion 1.9.3rc25 sends (ws_router/connect.py, oip_descriptor).
        val result = connected(
            """{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1",""" +
                """"websocket_path":"/ws","extensions":{"session-sync":"0.1"}}"""
        )

        assertTrue(result.event is ConnectionEvent.Connected)
        assertEquals("sess-1", result.updateSessionId)
    }

    @Test
    fun `a CONNECTED without a descriptor is still accepted during the compatibility window`() {
        assertTrue(connected(null).event is ConnectionEvent.Connected)
        assertTrue(connected("null").event is ConnectionEvent.Connected)
    }

    @Test
    fun `a CONNECTED naming another version is a protocol rejection, and its session is not taken on`() {
        val result = connected("""{"name":"oip","version":"0.2","min_version":"0.2","max_version":"0.2"}""")

        assertEquals(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/0.2"), result.event)
        assertNull(result.updateSessionId)
        assertNull(result.updateSession)
    }

    @Test
    fun `a CONNECTED naming another protocol is a protocol rejection`() {
        assertEquals(
            ConnectionEvent.ProtocolRejected(hostProtocol = "acp/0.1"),
            connected("""{"name":"acp","version":"0.1"}""").event
        )
    }

    @Test
    fun `a CONNECTED descriptor that cannot be read as ours is a protocol rejection`() {
        // As supportsOip(): only an object whose name and version are exactly ours passes.
        listOf(
            """{"name":"oip"}""",
            """{"name":"oip","version":0.1}""",
            """{"version":"0.1"}""",
            """"oip/0.1"""",
            "[]"
        ).forEach { protocol ->
            assertTrue(protocol, connected(protocol).event is ConnectionEvent.ProtocolRejected)
        }
    }

    @Test
    fun `a refused descriptor that reads like our own version is reported as unknown`() {
        // Otherwise the error would say the Host speaks oip/0.1 and so does the app.
        listOf("""{"version":"0.1"}""", """{"name":"oip","version":0.1}""").forEach { protocol ->
            assertEquals(protocol, ConnectionEvent.ProtocolRejected(hostProtocol = null), connected(protocol).event)
        }
    }

    @Test
    fun `a JSON null descriptor counts as missing`() {
        // The parser already reads a null field as missing; the check agrees for any other caller.
        assertTrue(OipProtocol.supports(JsonNull))
    }
}
