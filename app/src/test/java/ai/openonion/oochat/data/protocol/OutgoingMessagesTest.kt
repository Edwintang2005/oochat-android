package ai.openonion.oochat.data.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
    fun `EXEC carries the command ws_router-exec reads, with the args object untouched`() {
        // The unsigned command only: AgentConnection signs it with
        // SignedCommand.envelope like every other command, which
        // SignedCommandTest covers (EXEC included).
        val args = json.parseToJsonElement(
            """{"command":"co status","timeout":30,"verbose":false}"""
        ).jsonObject

        val frame = json.parseToJsonElement(
            json.encodeToString(ExecMessage.serializer(), ExecMessage(execId = "exec-123", tool = "bash", args = args, to = "0xagent"))
        ).jsonObject

        assertEquals(setOf("type", "exec_id", "tool", "args", "to"), frame.keys)
        assertEquals(JsonPrimitive("EXEC"), frame["type"])
        assertEquals(JsonPrimitive("exec-123"), frame["exec_id"])
        assertEquals(JsonPrimitive("bash"), frame["tool"])
        assertEquals(args, frame["args"])
        assertEquals(JsonPrimitive("0xagent"), frame["to"])
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
                payload = ConnectPayload(timestamp = 1_760_000_000L, to = "0xagent", nonce = "n-1"),
                from = "0xme",
                signature = "sig",
                timestamp = 1_760_000_000L,
                to = "0xagent",
                sessionId = "sess-42"
            )
        )

        assertEquals(
            """{"type":"CONNECT","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"},""" +
                """"payload":{"timestamp":1760000000,"to":"0xagent","nonce":"n-1","signed_commands":1},"from":"0xme","signature":"sig",""" +
                """"timestamp":1760000000,"to":"0xagent","session_id":"sess-42"}""",
            encoded
        )
    }

    @Test
    fun `a reconnect CONNECT names the last message handled at the envelope top level`() {
        val encoded = json.encodeToString(
            ConnectMessage.serializer(),
            ConnectMessage(
                payload = ConnectPayload(timestamp = 1_760_000_000L, to = "0xagent", nonce = "n-1"),
                from = "0xme",
                signature = "sig",
                timestamp = 1_760_000_000L,
                to = "0xagent",
                sessionId = "sess-42",
                lastMsgId = "ev-9f12"
            )
        )

        // Next to session_id and outside the signed payload, which carries the
        // signed-command capability and replay-prevention nonce.
        assertEquals(
            """{"type":"CONNECT","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"},""" +
                """"payload":{"timestamp":1760000000,"to":"0xagent","nonce":"n-1","signed_commands":1},"from":"0xme","signature":"sig",""" +
                """"timestamp":1760000000,"to":"0xagent","session_id":"sess-42","last_msg_id":"ev-9f12"}""",
            encoded
        )
    }

    @Test
    fun `a direct CONNECT for a new session still declares the OIP descriptor`() {
        val encoded = json.encodeToString(
            ConnectMessage.serializer(),
            ConnectMessage(
                payload = ConnectPayload(timestamp = 1_760_000_000L, nonce = "n-1"),
                from = "0xme",
                signature = "sig",
                timestamp = 1_760_000_000L
            )
        )

        assertEquals(
            """{"type":"CONNECT","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"},""" +
                """"payload":{"timestamp":1760000000,"nonce":"n-1","signed_commands":1},"from":"0xme","signature":"sig","timestamp":1760000000}""",
            encoded
        )
    }

    @Test
    fun `INPUT preserves prompt images and file attachments`() {
        val encoded = json.encodeToString(
            InputMsg.serializer(),
            InputMsg(
                inputId = "input-1",
                prompt = "Inspect this file",
                images = listOf("data:image/jpeg;base64,image-data"),
                files = listOf(
                    FileAttachment(
                        name = "notes.txt",
                        data = "data:text/plain;base64,file-data"
                    )
                )
            )
        )

        assertEquals(
            """{"type":"INPUT","input_id":"input-1","prompt":"Inspect this file","images":["data:image/jpeg;base64,image-data"],""" +
                """"files":[{"name":"notes.txt","data":"data:text/plain;base64,file-data"}]}""",
            encoded
        )
    }

    @Test
    fun `INPUT omits optional attachments when they are absent`() {
        val encoded = json.encodeToString(
            InputMsg.serializer(),
            InputMsg(
                inputId = "input-2",
                prompt = "No attachments"
            )
        )

        assertEquals(
            """{"type":"INPUT","input_id":"input-2","prompt":"No attachments"}""",
            encoded
        )
    }

    @Test
    fun `PONG contains only its frame type`() {
        assertEquals(
            """{"type":"PONG"}""",
            json.encodeToString(PongMessage.serializer(), PongMessage())
        )
    }

    @Test
    fun `INTERRUPT contains only its frame type`() {
        assertEquals(
            """{"type":"INTERRUPT"}""",
            json.encodeToString(InterruptMessage.serializer(), InterruptMessage())
        )
    }

    @Test
    fun `ASK_USER_RESPONSE carries the answer`() {
        assertEquals(
            """{"type":"ASK_USER_RESPONSE","answer":"Use the existing file"}""",
            json.encodeToString(
                AskUserResponse.serializer(),
                AskUserResponse(answer = "Use the existing file")
            )
        )
    }

    @Test
    fun `PLAN_REVIEW_RESPONSE carries the review message`() {
        assertEquals(
            """{"type":"PLAN_REVIEW_RESPONSE","message":"Approved"}""",
            json.encodeToString(
                PlanReviewResponse.serializer(),
                PlanReviewResponse(message = "Approved")
            )
        )
    }

    @Test
    fun `ULW_RESPONSE continue carries the turn budget`() {
        assertEquals(
            """{"type":"ULW_RESPONSE","action":"continue","turns":5}""",
            json.encodeToString(
                UlwResponse.serializer(),
                UlwResponse(action = "continue", turns = 5)
            )
        )
    }

    @Test
    fun `ULW_RESPONSE switch mode carries the selected mode`() {
        assertEquals(
            """{"type":"ULW_RESPONSE","action":"switch_mode","mode":"plan"}""",
            json.encodeToString(
                UlwResponse.serializer(),
                UlwResponse(action = "switch_mode", mode = "plan")
            )
        )
    }

    @Test
    fun `mode_change carries the selected mode and optional turns`() {
        assertEquals(
            """{"type":"mode_change","mode":"ulw","turns":3}""",
            json.encodeToString(
                ModeChangeMessage.serializer(),
                ModeChangeMessage(mode = "ulw", turns = 3)
            )
        )
    }

    @Test
    fun `mode_change omits turns when it is not provided`() {
        assertEquals(
            """{"type":"mode_change","mode":"plan"}""",
            json.encodeToString(
                ModeChangeMessage.serializer(),
                ModeChangeMessage(mode = "plan")
            )
        )
    }
}
