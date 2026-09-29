package ai.openonion.oochat.data.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for [ApprovalResponse]. Uses the same [Json] config as
 * [ai.openonion.oochat.network.AgentConnection]'s send path
 * (encodeDefaults = true, explicitNulls = false) — AgentConnection itself
 * can't be unit-tested (needs a real Android Keystore, see
 * [ai.openonion.oochat.network.AgentConnectionTest]'s doc), so this
 * verifies the frame shape it produces without going through it.
 */
class OutgoingMessagesTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `a rejection with feedback carries the field on the wire`() {
        val encoded = json.encodeToString(
            ApprovalResponse.serializer(),
            ApprovalResponse(approved = false, scope = "once", mode = "reject_soft", feedback = "Use yarn instead")
        )

        assertTrue(
            "feedback must be present when non-null: $encoded",
            encoded.contains(""""feedback":"Use yarn instead"""")
        )
        assertEquals(
            """{"type":"APPROVAL_RESPONSE","approved":false,"scope":"once","mode":"reject_soft","feedback":"Use yarn instead"}""",
            encoded
        )
    }

    @Test
    fun `an approval with no feedback omits the field from the wire entirely`() {
        val encoded = json.encodeToString(
            ApprovalResponse.serializer(),
            ApprovalResponse(approved = true, scope = "once")
        )

        assertFalse(
            "a null feedback must be dropped, not sent as JSON null: $encoded",
            encoded.contains("feedback")
        )
        assertEquals(
            """{"type":"APPROVAL_RESPONSE","approved":true,"scope":"once"}""",
            encoded
        )
    }

    @Test
    fun `a rejection without feedback also omits the field`() {
        val encoded = json.encodeToString(
            ApprovalResponse.serializer(),
            ApprovalResponse(approved = false, scope = "once", mode = "reject_hard")
        )

        assertFalse(encoded.contains("feedback"))
        assertEquals(
            """{"type":"APPROVAL_RESPONSE","approved":false,"scope":"once","mode":"reject_hard"}""",
            encoded
        )
    }

    /**
     * Wire shape verified against the server's
     * network/host/ws_router/session.py SESSION_STATUS branch:
     * `sid = (data.get("session") or {}).get("session_id")` — the id lives
     * inside a nested `session` object, not at the envelope's top level
     * (unlike [ConnectMessage.sessionId]).
     */
    @Test
    fun `SessionStatusQuery nests session_id under a session object, not top-level`() {
        val encoded = json.encodeToString(
            SessionStatusQuery.serializer(),
            SessionStatusQuery(session = SessionStatusQuerySession(sessionId = "sess-42"))
        )

        assertEquals(
            """{"type":"SESSION_STATUS","session":{"session_id":"sess-42"}}""",
            encoded
        )
    }
}
