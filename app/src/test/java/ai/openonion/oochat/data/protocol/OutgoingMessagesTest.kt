package ai.openonion.oochat.data.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for outgoing frames. Uses the same [Json] config as
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

    /**
     * The invite-code gate's answer. The server's WS router only recognises
     * the literal "ONBOARD_SUBMIT" and verifies the signature against
     * `payload`, so the timestamp lives there and nowhere else — the Python
     * SDK's `_build_onboard_submit` sends no envelope timestamp either.
     */
    @Test
    fun `an invite-code ONBOARD_SUBMIT carries the code inside the signed payload only`() {
        val encoded = json.encodeToString(
            OnboardSubmitMessage.serializer(),
            OnboardSubmitMessage(
                payload = OnboardSubmitPayload(timestamp = 1_760_000_000L, inviteCode = "BETA2024"),
                from = "0xme",
                signature = "sig"
            )
        )

        assertEquals(
            """{"type":"ONBOARD_SUBMIT","payload":{"timestamp":1760000000,"invite_code":"BETA2024"},"from":"0xme","signature":"sig"}""",
            encoded
        )
    }

    @Test
    fun `a payment ONBOARD_SUBMIT carries the amount and no invite code`() {
        val encoded = json.encodeToString(
            OnboardSubmitMessage.serializer(),
            OnboardSubmitMessage(
                payload = OnboardSubmitPayload(timestamp = 1_760_000_000L, payment = 10.0),
                from = "0xme",
                signature = "sig"
            )
        )

        // 10.0, not 10: the relay re-encodes the payload it received before
        // verifying, and the app signed 10.0 (see IdentityVectorsTest).
        assertEquals(
            """{"type":"ONBOARD_SUBMIT","payload":{"timestamp":1760000000,"payment":10.0},"from":"0xme","signature":"sig"}""",
            encoded
        )
    }

    /**
     * W16AAPPLE-11. The Host's connect.py reads `data.get("protocol")` off the
     * CONNECT envelope and rejects anything but `name == "oip"` and a matching
     * `version`; connectonion-react's OIP_PROTOCOL is the reference value.
     */
    @Test
    fun `a relay CONNECT declares the OIP descriptor at the envelope's top level`() {
        val encoded = json.encodeToString(
            ConnectMessage.serializer(),
            ConnectMessage(
                payload = ConnectPayload(timestamp = 1_760_000_000L, to = "0xagent"),
                from = "0xme",
                signature = "sig",
                timestamp = 1_760_000_000L,
                to = "0xagent",
                sessionId = "sess-42"
            )
        )

        assertEquals(
            """{"type":"CONNECT","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"},""" +
                """"payload":{"timestamp":1760000000,"to":"0xagent"},"from":"0xme","signature":"sig",""" +
                """"timestamp":1760000000,"to":"0xagent","session_id":"sess-42"}""",
            encoded
        )
    }

    @Test
    fun `a direct CONNECT for a new session still declares the OIP descriptor`() {
        val encoded = json.encodeToString(
            ConnectMessage.serializer(),
            ConnectMessage(
                payload = ConnectPayload(timestamp = 1_760_000_000L),
                from = "0xme",
                signature = "sig",
                timestamp = 1_760_000_000L
            )
        )

        assertEquals(
            """{"type":"CONNECT","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"},""" +
                """"payload":{"timestamp":1760000000},"from":"0xme","signature":"sig","timestamp":1760000000}""",
            encoded
        )
    }
}
