package ai.openonion.oochat.data.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The OIP v2 signed-command envelope.
 *
 * A CONNECT carrying `payload.signed_commands: 1` ([ConnectPayload.signedCommands])
 * turns on the Host's command gate for that socket. From then on every frame
 * except [EXEMPT_TYPES] must carry its own Ed25519 signature, or the Host
 * answers with an ERROR and drops it. Without the gate, anyone who can write
 * to the socket (a relay, say) could inject or replay commands under the
 * identity CONNECT authenticated.
 *
 * Wire shape verified against ConnectOnion's network/connect.py
 * `_build_command_message` (the reference client) and network/host/auth.py
 * `authenticated_command_payload` (the gate):
 *
 * ```json
 * {"type":"INPUT","input_id":"…","prompt":"hi",
 *  "payload":{"type":"INPUT","input_id":"…","prompt":"hi","to":"0x…","timestamp":1760000000,"nonce":"…"},
 *  "from":"0x…","signature":"…"}
 * ```
 *
 * The payload is the whole command plus `to`, `timestamp` and `nonce`. A v2
 * Host checks that the signer is the identity that opened the socket, that
 * `payload.type` equals the frame's `type`, that `to` names this agent, that
 * `timestamp` is within 300s, and that this signature has never been used.
 * It then discards the frame and dispatches `payload` alone, so the payload
 * must carry every field the handler reads.
 *
 * The top level is the command exactly as it was sent before v2, so a pre-v2
 * Host, which never looks at `payload`, sees the same fields as ever (a direct
 * INPUT still names no agent there). The reference also repeats `to`,
 * `timestamp` and `nonce` at the top level; neither kind of Host reads them.
 */
object SignedCommand {

    /** The value of `payload.signed_commands` that enables the gate; the Host compares with `== 1`. */
    const val CAPABILITY = 1

    /**
     * Frame types the Host lets through unsigned on a gated socket
     * (ws_router/session.py `exempt`). CONNECT and ONBOARD_SUBMIT carry their
     * own signatures; PONG is transport; SESSION_STATUS on a live socket uses
     * the identity CONNECT verified.
     */
    val EXEMPT_TYPES = setOf("CONNECT", "PONG", "SESSION_STATUS", "ONBOARD_SUBMIT")

    /**
     * Wrap [command] (a serialised outgoing frame, `type` included) in a
     * signed envelope.
     *
     * @param to the agent address CONNECT named. Signed even on a direct
     *   socket, as the reference does, so a frame captured for one agent
     *   cannot be delivered to another.
     * @param nonce fresh and unpredictable per frame (see [newNonce]): it is
     *   what makes two identical commands in the same second sign to
     *   different bytes, so a legitimate repeat is never refused as a replay.
     * @param sign Ed25519-signs the payload's canonical JSON, returning hex.
     */
    fun envelope(
        command: JsonObject,
        to: String,
        timestamp: Long,
        nonce: String,
        from: String,
        sign: (payload: JsonObject) -> String
    ): JsonObject {
        val type = (command["type"] as? JsonPrimitive)?.content
        require(type != null && type !in EXEMPT_TYPES) { "not a signed command type: $type" }
        val payload = JsonObject(
            command + mapOf(
                "to" to JsonPrimitive(to),
                "timestamp" to JsonPrimitive(timestamp),
                "nonce" to JsonPrimitive(nonce)
            )
        )
        return JsonObject(
            command + mapOf(
                "payload" to payload,
                "from" to JsonPrimitive(from),
                "signature" to JsonPrimitive(sign(payload))
            )
        )
    }

    /** A random UUID: SecureRandom-backed, so the next one cannot be predicted. */
    fun newNonce(): String = java.util.UUID.randomUUID().toString()
}
