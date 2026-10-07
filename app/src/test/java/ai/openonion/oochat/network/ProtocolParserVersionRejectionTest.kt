package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W16AAPPLE-12: the Host's OIP version rejection, as ws_router/connect.py
 * sends it:
 * `{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,"protocol":{...}}`
 * parses to its own [ConnectionEvent.ProtocolRejected], while every other
 * ERROR keeps its [ConnectionEvent.ConnectionError] path.
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

    @Test
    fun `an ERROR with a string code stays a connection error`() {
        // session.py's Work Room refusal: the other ERROR that carries a code.
        val result = parse("""{"type":"ERROR","code":"provider_station_only","message":"Use the Claude Work Room"}""")

        assertEquals(ConnectionEvent.ConnectionError("Use the Claude Work Room"), result.event)
    }

    @Test
    fun `an ERROR without a code stays a connection error`() {
        val result = parse("""{"type":"ERROR","message":"Invalid invite code"}""")

        assertEquals(ConnectionEvent.ConnectionError("Invalid invite code"), result.event)
    }
}
