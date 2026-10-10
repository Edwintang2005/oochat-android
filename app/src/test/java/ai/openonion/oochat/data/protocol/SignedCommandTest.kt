package ai.openonion.oochat.data.protocol

import ai.openonion.oochat.crypto.BouncyCastleEd25519
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * W16AAPPLE-16: the v2 signed-command envelope, checked end to end against a
 * model of the Host's gate rather than against its own output — a signature
 * the client can produce but the Host cannot verify is worth nothing.
 *
 * Signing uses real Ed25519 ([BouncyCastleEd25519], held to libsodium's own
 * vectors by IdentityVectorsTest), so these are real signatures.
 */
class SignedCommandTest {

    private val keyManager = KeyManager(Application(), BouncyCastleEd25519())
    private val me = TestIdentity.keys(0x01)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * The Host's gate, transcribed from ConnectOnion's network/host/auth.py
     * `authenticated_command_payload` and `_authenticate_signed`, checks in the
     * same order, error strings verbatim. Holds the one-use ledger
     * (`signature_already_used`) a real Host keeps per agent.
     */
    private class HostCommandGate(
        private val owner: String,
        private val recipient: String?,
        private val keyManager: KeyManager,
        private val now: Long = NOW
    ) {
        private val usedSignatures = mutableSetOf<String>()

        /** The payload the Host would dispatch, or the ERROR message it would send. */
        fun accept(wire: String): Pair<JsonObject?, String?> {
            val frame = Json.parseToJsonElement(wire).jsonObject
            val payload = frame["payload"] as? JsonObject
                ?: return null to "unauthorized: signed command required"
            val from = (frame["from"] as? JsonPrimitive)?.content
                ?: return null to "unauthorized: 'from' field required"
            val signature = (frame["signature"] as? JsonPrimitive)?.content
                ?: return null to "unauthorized: signature required"
            val timestamp = (payload["timestamp"] as? JsonPrimitive)?.long
                ?: return null to "unauthorized: timestamp required in payload"
            if (kotlin.math.abs(now - timestamp) > SIGNATURE_EXPIRY_SECONDS) {
                return null to "unauthorized: signature expired"
            }
            if (recipient != null && (payload["to"] as? JsonPrimitive)?.content != recipient) {
                return null to "unauthorized: wrong recipient"
            }
            if (!verifies(payload, signature, from)) return null to "unauthorized: invalid signature"
            if (from != owner) return null to "unauthorized: command signer does not own this connection"
            if (payload["type"] != frame["type"]) return null to "unauthorized: signed command type mismatch"
            val nonce = (payload["nonce"] as? JsonPrimitive)?.content
            if (nonce.isNullOrEmpty()) return null to "unauthorized: signed command nonce required"
            if (!usedSignatures.add(signature)) return null to "unauthorized: signed command already used"
            return payload to null
        }

        private fun verifies(payload: JsonObject, signatureHex: String, address: String): Boolean {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(address.removePrefix("0x").hexToBytes(), 0))
            val bytes = keyManager.canonicalJson(payload).toByteArray(Charsets.UTF_8)
            signer.update(bytes, 0, bytes.size)
            return signer.verifySignature(signatureHex.removePrefix("0x").hexToBytes())
        }

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun gate(owner: KeyManager.AddressData = me, recipient: String? = AGENT, now: Long = NOW) =
        HostCommandGate(owner.address, recipient, keyManager, now)

    private inline fun <reified T> command(message: T): JsonObject = json.encodeToJsonElement(message).jsonObject

    /** What AgentConnection puts on the wire for [command]. */
    private fun sign(
        command: JsonObject,
        keys: KeyManager.AddressData = me,
        nonce: String = SignedCommand.newNonce(),
        timestamp: Long = NOW,
        to: String = AGENT
    ): String {
        val frame = SignedCommand.envelope(command, to, timestamp, nonce, keys.address) { payload ->
            keyManager.sign(keys, keyManager.canonicalJson(payload))
        }
        return json.encodeToString(JsonObject.serializer(), frame)
    }

    private val input = InputMsg(
        inputId = "in-1",
        prompt = "translate \"héllo\" → 你好\n",
        images = listOf("data:image/png;base64,AAAA"),
        files = listOf(FileAttachment("r.pdf", "data:application/pdf;base64,BBBB"))
    )

    // ── signing ───────────────────────────────────────────────────────

    @Test
    fun `a signed command passes the Host gate and carries every field it executes`() {
        val (payload, error) = gate().accept(sign(command(input), nonce = "n-1"))

        assertNull(error)
        // The Host dispatches this payload and nothing else, so the command's
        // own fields have to be in it, not just at the top level.
        assertEquals(
            command(input) + mapOf(
                "to" to JsonPrimitive(AGENT),
                "timestamp" to JsonPrimitive(NOW),
                "nonce" to JsonPrimitive("n-1")
            ),
            payload
        )
    }

    @Test
    fun `the top level is the unsigned command a pre-v2 Host has always read`() {
        val frame = Json.parseToJsonElement(sign(command(input))).jsonObject

        assertEquals(command(input), JsonObject(frame - setOf("payload", "from", "signature")))
        assertEquals(me.address, frame["from"]?.jsonPrimitive?.content)
    }

    /**
     * The bytes the Host verifies against, from Python itself:
     * `json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`
     * — one of the two encodings auth.py `verify_signature` accepts. Non-ASCII,
     * escapes and nested objects are where two JSON encoders part ways.
     */
    @Test
    fun `the signed payload canonicalises byte for byte as the Host does`() {
        val payload = Json.parseToJsonElement(sign(command(input), nonce = "n-1")).jsonObject["payload"]!!.jsonObject

        assertEquals(
            """{"files":[{"data":"data:application/pdf;base64,BBBB","name":"r.pdf"}],""" +
                """"images":["data:image/png;base64,AAAA"],"input_id":"in-1","nonce":"n-1",""" +
                """"prompt":"translate \"héllo\" → 你好\n","timestamp":1760000000,"to":"0xagent","type":"INPUT"}""",
            keyManager.canonicalJson(payload)
        )
    }

    @Test
    fun `every command type the gate covers signs and verifies`() {
        val commands = listOf(
            command(input),
            command(AskUserResponse(answer = "Python 3")),
            command(ApprovalResponse(approved = false, mode = "reject_explain", feedback = "not that file")),
            command(InterruptMessage()),
            command(PlanReviewResponse(message = "go")),
            command(UlwResponse(action = "continue", turns = 100)),
            command(ModeChangeMessage(mode = "ulw", turns = 25))
        )
        val host = gate()
        commands.forEach { c ->
            assertNull("${c["type"]} was refused", host.accept(sign(c)).second)
        }
    }

    @Test
    fun `frames the gate exempts are not wrapped`() {
        listOf(command(PongMessage()), command(SessionStatusQuery(session = SessionStatusQuerySession("s")))).forEach { c ->
            assertThrows(IllegalArgumentException::class.java) { sign(c) }
        }
    }

    // ── replay ────────────────────────────────────────────────────────

    @Test
    fun `a replayed command is rejected`() {
        val host = gate()
        val captured = sign(command(ApprovalResponse(approved = true)))

        assertNull(host.accept(captured).second)
        assertEquals("unauthorized: signed command already used", host.accept(captured).second)
    }

    @Test
    fun `the same command sent twice is not mistaken for a replay`() {
        // Ed25519 is deterministic and the timestamp is in seconds: without a
        // fresh nonce, approving twice within a second would sign identical
        // bytes and the second approval would be refused as a replay.
        val host = gate()
        val first = sign(command(ApprovalResponse(approved = true)))
        val second = sign(command(ApprovalResponse(approved = true)))

        assertNotEquals(first, second)
        assertNull(host.accept(first).second)
        assertNull(host.accept(second).second)
    }

    // ── forgery ───────────────────────────────────────────────────────

    @Test
    fun `a command edited after signing is rejected`() {
        val frame = Json.parseToJsonElement(sign(command(input))).jsonObject
        val payload = frame["payload"]!!.jsonObject
        val edited = JsonObject(frame + ("payload" to JsonObject(payload + ("prompt" to JsonPrimitive("rm -rf /")))))

        assertEquals("unauthorized: invalid signature", gate().accept(edited.toString()).second)
    }

    @Test
    fun `a signed command cannot be relabelled as another type`() {
        val frame = Json.parseToJsonElement(sign(command(ApprovalResponse(approved = true)))).jsonObject
        val relabelled = JsonObject(frame + ("type" to JsonPrimitive("INPUT")))

        assertEquals("unauthorized: signed command type mismatch", gate().accept(relabelled.toString()).second)
    }

    @Test
    fun `a command signed by another identity is rejected`() {
        val other = TestIdentity.keys(0x02)

        assertEquals(
            "unauthorized: command signer does not own this connection",
            gate(owner = me).accept(sign(command(input), keys = other)).second
        )
    }

    @Test
    fun `a command captured for one agent is rejected by another`() {
        assertEquals(
            "unauthorized: wrong recipient",
            gate(recipient = "0xother").accept(sign(command(input), to = AGENT)).second
        )
    }

    @Test
    fun `a command older than the Host's window is rejected`() {
        assertEquals(
            "unauthorized: signature expired",
            gate(now = NOW + SIGNATURE_EXPIRY_SECONDS + 1).accept(sign(command(input))).second
        )
    }

    private companion object {
        const val AGENT = "0xagent"
        const val NOW = 1_760_000_000L

        /** network/host/replay.py `SIGNATURE_EXPIRY_SECONDS`. */
        const val SIGNATURE_EXPIRY_SECONDS = 300L
    }
}
