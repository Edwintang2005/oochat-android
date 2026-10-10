package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RUNTIME_INPUT_ACK` and its refusal, `ERROR` with
 * `code: RUNTIME_INPUT_REJECTED`.
 *
 * Fixtures match the frames the host actually sends — ws_router/session.py in
 * openonion/connectonion, which sends the ACK only after `push_runtime_input`
 * accepted the text, and the refusal when it did not. The ACK's `id` is a UUID
 * the host minted, not the `input_id` the client sent; nothing here may assume
 * the two match.
 */
class ProtocolParserRuntimeInputTest {

    private val parser = ProtocolParser

    private fun parse(json: String) =
        parser.parse(json, outstandingInputIds = setOf("inp-1"), isDirect = false)

    // ── RUNTIME_INPUT_ACK ────────────────────────────────────────────

    @Test
    fun `RUNTIME_INPUT_ACK maps to RuntimeInputAcknowledged`() {
        val result = parse(
            """{"type":"RUNTIME_INPUT_ACK","session_id":"sess-1","id":"host-7c2a"}"""
        )

        val event = result.event as ConnectionEvent.RuntimeInputAcknowledged
        assertEquals("sess-1", event.sessionId)
        assertEquals("the host's own id, passed through untouched", "host-7c2a", event.hostInputId)
    }

    @Test
    fun `RUNTIME_INPUT_ACK is not mistaken for the turn's OUTPUT`() {
        // An ACK answers a message queued *into* a running turn. Treating it as
        // OUTPUT would clear the outstanding input ids and report the turn done
        // while the agent is still working.
        val result = parse("""{"type":"RUNTIME_INPUT_ACK","session_id":"sess-1","id":"host-1"}""")

        assertTrue(result.event !is ConnectionEvent.OutputReceived)
        assertFalse("it is not a gate that waits on the user", result.setWaiting)
        assertNull("and carries no session snapshot", result.updateSession)
    }

    @Test
    fun `RUNTIME_INPUT_ACK without optional fields still parses`() {
        val result = parse("""{"type":"RUNTIME_INPUT_ACK"}""")

        val event = result.event as ConnectionEvent.RuntimeInputAcknowledged
        assertNull(event.sessionId)
        assertNull(event.hostInputId)
    }

    // ── ERROR / RUNTIME_INPUT_REJECTED ───────────────────────────────

    @Test
    fun `RUNTIME_INPUT_REJECTED maps to RuntimeInputRejected, not a ConnectionError`() {
        val result = parse(
            """{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED",""" +
                """"message":"running turn is not accepting runtime input; retry after OUTPUT",""" +
                """"session_id":"sess-1","retryable":true}"""
        )

        // ConnectionError would clear the outstanding input ids and can start
        // the reconnect ladder, for a socket that is perfectly healthy.
        val event = result.event as ConnectionEvent.RuntimeInputRejected
        assertEquals("sess-1", event.sessionId)
        assertTrue(event.retryable)
        assertEquals(
            "running turn is not accepting runtime input; retry after OUTPUT",
            event.message
        )
    }

    @Test
    fun `RUNTIME_INPUT_REJECTED is not retryable unless the host says so`() {
        val missing = parse("""{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED","session_id":"s"}""")
        val explicitFalse = parse(
            """{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED","session_id":"s","retryable":false}"""
        )
        val wrongType = parse(
            """{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED","session_id":"s","retryable":"yes"}"""
        )

        assertFalse((missing.event as ConnectionEvent.RuntimeInputRejected).retryable)
        assertFalse((explicitFalse.event as ConnectionEvent.RuntimeInputRejected).retryable)
        assertFalse(
            "a string is not the boolean true",
            (wrongType.event as ConnectionEvent.RuntimeInputRejected).retryable
        )
    }

    // ── ERROR frames that are not a refusal ──────────────────────────

    @Test
    fun `an ERROR without a code is still a ConnectionError`() {
        val result = parse("""{"type":"ERROR","message":"prompt required"}""")

        assertEquals("prompt required", (result.event as ConnectionEvent.ConnectionError).message)
    }

    @Test
    fun `an ERROR with a different code is still a ConnectionError`() {
        val result = parse(
            """{"type":"ERROR","code":"STALE_ANSWER","reason":"stale","message":"answer expired"}"""
        )

        assertEquals("answer expired", (result.event as ConnectionEvent.ConnectionError).message)
    }

    @Test
    fun `an ERROR whose code is not a string is not dropped`() {
        // `code` is declared as a JsonElement for exactly this: a typed field
        // would fail to decode a numeric code and drop the whole frame, which
        // is the failure that leaves an agent blocked waiting on the client.
        val numeric = parse("""{"type":"ERROR","code":4001,"message":"boom"}""")
        val nested = parse("""{"type":"ERROR","code":{"n":1},"message":"boom"}""")

        assertEquals("boom", (numeric.event as ConnectionEvent.ConnectionError).message)
        assertEquals("boom", (nested.event as ConnectionEvent.ConnectionError).message)
    }
}
