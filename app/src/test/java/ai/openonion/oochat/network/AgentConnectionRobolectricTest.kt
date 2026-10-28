package ai.openonion.oochat.network
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import ai.openonion.oochat.data.protocol.OipProtocol
import ai.openonion.oochat.data.protocol.ProtocolDescriptor
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ServerSessionStatus
import ai.openonion.oochat.knownIssue
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * Unit tests for [AgentConnection] against a fake [WebSocketFactory] (the
 * seam it already exposes for testing) plus a real, Robolectric-backed
 * [KeyManager] pre-seeded with a saved identity ([TestIdentity], derived
 * off-device) so [AgentConnection.connect] can reach [KeyManager.loadOrGenerate]'s
 * existing-key path.
 *
 * Deliberately never invokes [WebSocketListener.onOpen]: `AgentConnection`'s
 * real `onOpen` handler calls `sendConnectMessage()`, which — like
 * `respondToOnboard()` — signs through the injected `signer` seam rather than
 * `keyManager.sign()` directly, so it no longer needs the native libsodium
 * binary itself; `onOpen` is still left untriggered here simply because
 * nothing about it is under test (see KeyManagerTest's doc comment for the
 * confirmed UnsatisfiedLinkError a *real* signer would hit). Every
 * path tested here is reachable from [WebSocketListener.onMessage]/
 * `onFailure`/`onClosed` instead — a real server's "CONNECTED" frame is
 * simulated directly via `onMessage`, standing in for what would normally
 * follow a successful `onOpen` + CONNECT handshake, and `sendConnectMessage`
 * called mid-session (the ONBOARD_SUCCESS re-auth path below) goes through
 * the same fake signer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentConnectionRobolectricTest {

    private class FakeWebSocket : WebSocket {
        // Copy-on-write: a replacement socket is opened from the reconnect
        // scope, so its writes and the assertions reading them are on
        // different threads.
        val sentMessages = CopyOnWriteArrayList<String>()
        var closeCode: Int? = null
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sentMessages += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() {}
    }

    private class FakeWebSocketFactory : WebSocketFactory {
        private val _createCount = MutableStateFlow(0)

        /** A flow, not a counter, so a test can await socket #n instead of polling for it. */
        val createCount: StateFlow<Int> = _createCount
        val socketCount: Int get() = _createCount.value

        @Volatile var lastListener: WebSocketListener? = null
        @Volatile var lastWebSocket: FakeWebSocket? = null

        /** Every request a socket was opened for, in order — the URL the app actually dialled. */
        val requests = CopyOnWriteArrayList<Request>()

        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            requests += request
            val ws = FakeWebSocket()
            lastWebSocket = ws
            lastListener = listener
            // Published last, so a waiter that sees the new count is also
            // guaranteed to see the socket and listener that belong to it.
            _createCount.update { it + 1 }
            return ws
        }
    }

    private lateinit var keyManager: KeyManager
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var connection: AgentConnection

    /** Owns every [collectEvents] subscription, so none outlives its test. */
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        // A saved identity — see class doc — so loadOrGenerate() takes the
        // existing-key path instead of falling through to the blocked
        // generate() path.
        keyManager.save(TestIdentity.keys())
        factory = FakeWebSocketFactory()
        // respondToOnboard() signs its payload via keyManager.sign(), which
        // (like sendConnectMessage's CONNECT signing — see class doc) needs
        // the native libsodium binary and throws UnsatisfiedLinkError on a
        // host JVM/Robolectric. Inject a fake signer so the respondToOnboard
        // tests below can exercise the real wire-format/queuing logic.
        connection = AgentConnection(keyManager, webSocketFactory = factory, signer = { _, _ -> "fake-signature" })
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        connection.teardown()
    }

    /**
     * The command inside a v2 signed frame, after checking the envelope around
     * it: signed by this connection's identity, for [to], same type inside and
     * out, with a nonce and timestamp. The command is returned without those
     * three signing fields, so a test can compare it to the bare wire shape.
     */
    private fun signedCommand(frame: String, to: String = "0xAGENT"): JsonObject {
        val envelope = Json.parseToJsonElement(frame).jsonObject
        val payload = envelope["payload"]?.jsonObject ?: error("not a signed command: $frame")
        assertEquals("fake-signature", envelope["signature"]?.jsonPrimitive?.content)
        assertEquals(TestIdentity.keys().address, envelope["from"]?.jsonPrimitive?.content)
        assertEquals("signed type must match the frame's", envelope["type"], payload["type"])
        assertEquals(to, payload["to"]?.jsonPrimitive?.content)
        assertTrue("nonce required: $frame", !payload["nonce"]?.jsonPrimitive?.content.isNullOrEmpty())
        assertNotNull("timestamp required: $frame", payload["timestamp"]?.jsonPrimitive?.longOrNull)
        return JsonObject(payload - setOf("to", "timestamp", "nonce"))
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun simulateConnected(sessionId: String = "sess-1") {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"CONNECTED","session_id":"$sessionId"}""")
    }

    /** Minimal real [Response] for the one test that needs to trigger [WebSocketListener.onOpen] directly. */
    private fun fakeResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("wss://fake.example.com/ws").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    // ── deterministic waiting ─────────────────────────────────────────
    //
    // Nothing below sleeps. `connection.events` is a replay=0 SharedFlow, so a
    // frame emitted before a collector attaches is dropped rather than delayed
    // — every wait here is on a flow the code under test writes, and the
    // subscription itself is awaited rather than guessed at.

    /**
     * Suspend until [flow] carries a value matching [condition].
     *
     * The timeout is a deadlock guard, not a budget the assertions lean on: it
     * can only expire when the behaviour under test is genuinely broken.
     */
    private suspend fun <T> awaitOn(what: String, flow: Flow<T>, condition: (T) -> Boolean) {
        if (withTimeoutOrNull(AWAIT_TIMEOUT_MS) { flow.first(condition) } == null) {
            fail("timed out after ${AWAIT_TIMEOUT_MS}ms waiting for: $what")
        }
    }

    /** Waits out whatever opens a replacement socket — a launch onto IO, or a real backoff. */
    private suspend fun awaitSockets(expected: Int) {
        awaitOn("socket #$expected to open", factory.createCount) { it >= expected }
        assertEquals("expected $expected socket(s)", expected, factory.socketCount)
    }

    /** Everything one subscriber saw on [AgentConnection.events], as a flow so waits on it are event-driven. */
    private class EventLog {
        private val _recorded = MutableStateFlow<List<ConnectionEvent>>(emptyList())
        val recorded: StateFlow<List<ConnectionEvent>> = _recorded

        /** The events under test: the barrier markers [awaitDrained] feeds in are not among them. */
        val seen: List<ConnectionEvent>
            get() = _recorded.value.filterNot { it is ConnectionEvent.AgentProfileReceived && it.profile.name == MARKER }

        fun add(event: ConnectionEvent) = _recorded.update { it + event }
    }

    /**
     * Subscribe to [AgentConnection.events], returning only once the
     * subscription is live so nothing sent afterwards can be missed. The old
     * `Thread.sleep(100)` here was a bet that the collector had got that far.
     */
    private suspend fun collectEvents(): EventLog {
        val log = EventLog()
        val subscribed = CompletableDeferred<Unit>()
        collectorScope.launch {
            connection.events.onSubscription { subscribed.complete(Unit) }.collect { log.add(it) }
        }.invokeOnCompletion {
            // A job that ends before subscribing must still release the
            // waiter; completing an already-complete deferred is a no-op.
            subscribed.complete(Unit)
        }
        subscribed.await()
        return log
    }

    private suspend fun EventLog.await(what: String, condition: (List<ConnectionEvent>) -> Boolean) =
        awaitOn(what, recorded, condition)

    /**
     * Feed a side-effect-free AGENT_PROFILE down the live socket and wait for
     * it to arrive. The bus preserves order, so every frame sent before the
     * marker has been delivered by the time this returns — which is what makes
     * a negative assertion proof of a refused frame rather than of one still
     * in flight, or of a subscription that never attached at all.
     */
    private suspend fun EventLog.awaitDrained() {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"AGENT_PROFILE","name":"$MARKER","model":"test"}"""
        )
        await("the drain marker to be processed") { events ->
            events.any { it is ConnectionEvent.AgentProfileReceived && it.profile.name == MARKER }
        }
    }

    @Test
    fun `connect opens exactly one websocket via the injected factory`() {
        connection.connect("0xAGENT")

        assertEquals(1, factory.socketCount)
    }

    @Test
    fun `sendMessage before the connection is ready queues instead of sending`() {
        connection.connect("0xAGENT")

        connection.sendMessage("hello", "0xAGENT")

        assertFalse(connection.isConnected())
        assertTrue("queued message must not be sent over the (not-ready) socket", factory.lastWebSocket!!.sentMessages.isEmpty())
    }

    @Test
    fun `sendMessage with images includes them in the wire INPUT payload`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.sendMessage("look at this", "0xAGENT", images = listOf("data:image/jpeg;base64,AAAA"))

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertTrue(sent.contains("\"images\":[\"data:image/jpeg;base64,AAAA\"]"))
    }

    @Test
    fun `a CONNECTED frame flips isConnected and flushes queued messages`() {
        connection.connect("0xAGENT")
        connection.sendMessage("queued while connecting", "0xAGENT")

        simulateConnected()

        assertTrue(connection.isConnected())
        assertTrue(factory.lastWebSocket!!.sentMessages.any { it.contains("queued while connecting") })
    }

    @Test
    fun `the flush sends queued messages in order, one at a time`() {
        // Order is the contract; "one at a time" is why the sink hands them
        // over through a callback instead of returning the whole outbox, whose
        // attachments would all be decoded before the first one was sent.
        connection.connect("0xAGENT")
        repeat(3) { i -> connection.sendMessage("queued-$i", "0xAGENT") }

        simulateConnected()

        val prompts = factory.lastWebSocket!!.sentMessages.filter { it.contains("queued-") }
        assertEquals(3, prompts.size)
        assertTrue("flushed out of order: $prompts", prompts[0].contains("queued-0"))
        assertTrue("flushed out of order: $prompts", prompts[2].contains("queued-2"))
    }

    @Test
    fun `a queued attachment survives the flush and goes out on the wire`() {
        // The queue-then-flush path is the ordinary one for the first
        // attachment-bearing message in a conversation, not an edge case:
        // switching onto a conversation's session drops the ready flag before
        // the INPUT is sent. Losing the attachment there would be silent.
        connection.connect("0xAGENT")
        connection.sendMessage(
            "see attached",
            "0xAGENT",
            images = listOf("data:image/jpeg;base64,AAAA"),
            files = listOf(ai.openonion.oochat.data.protocol.FileAttachment("report.pdf", "data:application/pdf;base64,BBBB"))
        )

        simulateConnected()

        val sent = factory.lastWebSocket!!.sentMessages.single { it.contains("see attached") }
        assertTrue("queued images were dropped by the flush: $sent", sent.contains("data:image/jpeg;base64,AAAA"))
        assertTrue("queued files were dropped by the flush: $sent", sent.contains("report.pdf"))
    }

    @Test
    fun `respondToPlanReview sends the exact PLAN_REVIEW_RESPONSE wire shape`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToPlanReview("Plan approved. Implement now. Do NOT re-enter plan mode.")

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertEquals(
            json("""{"type":"PLAN_REVIEW_RESPONSE","message":"Plan approved. Implement now. Do NOT re-enter plan mode."}"""),
            signedCommand(sent)
        )
    }

    @Test
    fun `respondToUlwTurnsReached sends continue with turns and no mode`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToUlwTurnsReached(action = "continue", turns = 100)

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertEquals(json("""{"type":"ULW_RESPONSE","action":"continue","turns":100}"""), signedCommand(sent))
    }

    @Test
    fun `respondToUlwTurnsReached sends switch_mode with mode and no turns`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToUlwTurnsReached(action = "switch_mode", mode = "safe")

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertEquals(json("""{"type":"ULW_RESPONSE","action":"switch_mode","mode":"safe"}"""), signedCommand(sent))
    }

    /**
     * The three base modes, one frame each. `mode_change` is lower-case (a
     * plugin-level message, not one of the router's SCREAMING_CASE types) and
     * carries no `turns` key at all — the server's
     * `tool_approval/approval.py::poll_mode_changes` only reads `mode` on this
     * branch, and an unexpected key would be dead weight on every switch.
     */
    @Test
    fun `setMode sends the exact mode_change wire shape for each base mode`() {
        connection.connect("0xAGENT")
        simulateConnected()

        val expected = mapOf(
            "safe" to """{"type":"mode_change","mode":"safe"}""",
            "plan" to """{"type":"mode_change","mode":"plan"}""",
            "accept_edits" to """{"type":"mode_change","mode":"accept_edits"}""",
        )
        expected.forEach { (mode, frame) ->
            factory.lastWebSocket!!.sentMessages.clear()
            connection.setMode(mode)
            assertEquals(json(frame), signedCommand(factory.lastWebSocket!!.sentMessages.single()))
        }
    }

    /**
     * ULW is the one branch that reads `turns` (approval.py:901 hands
     * `msg.get('turns')` to ulw.py's `handle_ulw_mode_change`), and the number
     * has to survive as a bare JSON integer.
     */
    @Test
    fun `setMode sends ulw with its turn budget`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.setMode("ulw", turns = 25)

        assertEquals(
            json("""{"type":"mode_change","mode":"ulw","turns":25}"""),
            signedCommand(factory.lastWebSocket!!.sentMessages.single())
        )
    }

    @Test
    fun `querySessionStatus sends the exact SESSION_STATUS wire shape`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.querySessionStatus("sess-1")

        val sent = factory.lastWebSocket!!.sentMessages.single()
        // session_id lives under a nested "session" object — see
        // SessionStatusQuery's own doc for the server-code citation.
        assertEquals("""{"type":"SESSION_STATUS","session":{"session_id":"sess-1"}}""", sent)
    }

    @Test
    fun `a SESSION_STATUS reply is forwarded as ConnectionEvent SessionStatusReceived`() = runBlocking {
        connection.connect("0xAGENT")
        simulateConnected()

        val log = collectEvents()

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"SESSION_STATUS","session_id":"sess-1","status":"running"}"""
        )

        log.await("the SESSION_STATUS reply") { events ->
            events.any { it is ConnectionEvent.SessionStatusReceived }
        }

        val event = log.seen.filterIsInstance<ConnectionEvent.SessionStatusReceived>().firstOrNull()
        assertNotNull("SESSION_STATUS reply must surface on the events flow", event)
        assertEquals("sess-1", event!!.sessionId)
        assertEquals("running", event.status)
    }

    @Test
    fun `respondToOnboard sends immediately when the connection is already ready`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToOnboard(method = "invite_code", inviteCode = "ABC123")

        val sent = factory.lastWebSocket!!.sentMessages.single()
        // Wire shape must match the real protocol (docs/features/trust.md):
        // signed ONBOARD_SUBMIT with the code nested under "payload", not
        // a bare/unsigned ONBOARD_RESPONSE — the server only recognizes
        // the former and silently drops anything else.
        assertTrue("must be type ONBOARD_SUBMIT, not the old ONBOARD_RESPONSE", sent.contains("\"type\":\"ONBOARD_SUBMIT\""))
        assertTrue("invite code must be nested under payload", sent.contains("\"payload\":{") && sent.contains("\"invite_code\":\"ABC123\""))
        assertTrue("must carry a signature", sent.contains("\"signature\":\"fake-signature\""))
        assertTrue("must carry the signer's address", sent.contains("\"from\":\""))
        // Python reference SDK (network/connect.py::_build_onboard_submit)
        // emits no top-level timestamp on ONBOARD_SUBMIT — neither do we.
        assertFalse(
            "no envelope-level timestamp on ONBOARD_SUBMIT (matches Python reference and server's read set)",
            Regex("\"timestamp\":").containsMatchIn(sent.substringBefore("\"payload\""))
        )
        assertEquals("no reconnect needed when already ready", 1, factory.socketCount)
    }

    @Test
    fun `respondToOnboard sends on a live-but-not-CONNECTED socket (deep-link flow)`() {
        // The exact failure mode of the previous revision: on a deep-link
        // /agent/{address} connect the server emits ONBOARD_REQUIRED
        // without ever sending CONNECTED (handle_connect returns early on
        // forbidden+onboardable identities — ws_router/connect.py:26-31).
        // The socket is live, isConnectionReady is false; respondToOnboard
        // MUST send on this socket rather than dropping or trying to
        // reconnect.
        connection.connect("0xAGENT")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )

        connection.respondToOnboard(method = "invite_code", inviteCode = "XYZ999")

        assertEquals(
            "must reuse the existing live socket — no reconnect on demand anymore",
            1,
            factory.socketCount
        )
        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertTrue(sent.contains("\"type\":\"ONBOARD_SUBMIT\""))
        assertTrue(sent.contains("\"invite_code\":\"XYZ999\""))
    }

    @Test
    fun `respondToOnboard drops with no live socket rather than reconnecting`() {
        // Guard against the orphan-socket foot-gun the previous
        // reconnect-on-demand branch created. If the socket is fully gone,
        // the user must reconnect explicitly — silently opening a fresh
        // socket here would create an orphaned listener on the old one.
        connection.connect("0xAGENT")
        connection.disconnect()

        connection.respondToOnboard(method = "invite_code", inviteCode = "ABC123")

        assertEquals(
            "must NOT open a fresh socket from respondToOnboard itself",
            1,
            factory.socketCount
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `respondToOnboard rejects mismatched method and inviteCode`() {
        connection.connect("0xAGENT")
        simulateConnected()
        connection.respondToOnboard(method = "invite_code", payment = 5.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `respondToOnboard rejects unknown method`() {
        connection.connect("0xAGENT")
        simulateConnected()
        connection.respondToOnboard(method = "magic", inviteCode = "ABC")
    }

    @Test
    fun `respondToOnboard for a payment method omits invite_code and includes payment`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToOnboard(method = "payment", payment = 12.5)

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertTrue(sent.contains("\"payment\":12.5"))
        assertFalse("no invite_code field when paying instead", sent.contains("invite_code"))
    }

    @Test
    fun `ONBOARD_SUCCESS resends CONNECT on the same socket so the connection actually becomes ready`() {
        // The deep-link scenario the fix addresses: live socket, no preceding CONNECTED, ONBOARD_REQUIRED, then ONBOARD_SUCCESS.
        connection.connect("0xAGENT")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )
        // Before submit, no CONNECTED has been delivered — isConnectionReady
        // is false (matches production on the deep-link path).
        assertFalse(connection.isConnected())

        connection.respondToOnboard(method = "invite_code", inviteCode = "ABC123")

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"Invite code verified."}"""
        )

        assertEquals(
            "must reuse the existing socket, not open a new one — auto-reconnect is suppressed during onboarding",
            1,
            factory.socketCount
        )
        val resentConnect = factory.lastWebSocket!!.sentMessages.singleOrNull {
            it.contains("\"type\":\"CONNECT\"")
        }
        assertTrue("a fresh CONNECT must be sent on the same socket once trust is promoted", resentConnect != null)
        assertTrue("the resent CONNECT must carry a valid signature through the injected signer", resentConnect!!.contains("\"signature\":\"fake-signature\""))

        // After ONBOARD_SUCCESS, reauthenticate() flips isConnectionReady
        // back to false until the reply CONNECTED arrives — that's exactly
        // what gates sendMessage() during the round-trip.
        assertFalse(
            "isConnectionReady must be false between ONBOARD_SUCCESS and the response CONNECTED",
            connection.isConnected()
        )

        // The retried CONNECT actually completes the handshake this time.
        simulateConnected("sess-after-onboard")
        assertTrue(connection.isConnected())
    }

    @Test
    fun `the resent CONNECT uses one timestamp for both payload and envelope`() {
        // Guards the seam introduced by extracting signPayload: sendConnectMessage
        // must read System.currentTimeMillis() / 1000 exactly once per CONNECT
        // and reuse the value for both the signed payload timestamp AND the
        // wire envelope timestamp. A split-second skew between the two
        // (payload = T, envelope = T+1) makes the server's signature check
        // silently fail — server reads expiry from payload and verifies
        // against payload bytes, but the wire envelope timestamp a future
        // server change might surface could mismatch.
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"ok"}"""
        )

        val resentConnect = factory.lastWebSocket!!.sentMessages.singleOrNull {
            it.contains("\"type\":\"CONNECT\"")
        } ?: error("a resent CONNECT must be sent")
        val payloadTs = Regex("\"payload\":\\{\"timestamp\":(\\d+)").find(resentConnect)
            ?.groupValues?.get(1)?.toLongOrNull() ?: error("payload timestamp not found")
        val envelopeTs = Regex("\"timestamp\":(\\d+),\"to\":").find(resentConnect)
            ?.groupValues?.get(1)?.toLongOrNull() ?: error("envelope timestamp not found")
        assertEquals(
            "payload.timestamp and envelope.timestamp must come from the same clock read",
            payloadTs,
            envelopeTs
        )
    }

    // ── W16AAPPLE-11: OIP protocol descriptor ─────────────────────────

    private fun sentConnect(): JsonObject {
        val frame = factory.lastWebSocket!!.sentMessages.lastOrNull { it.contains("\"type\":\"CONNECT\"") }
            ?: error("no CONNECT was sent")
        return Json.parseToJsonElement(frame).jsonObject
    }

    private fun assertDeclaresOip(connect: JsonObject) {
        val protocol = connect["protocol"]?.jsonObject ?: error("CONNECT has no protocol descriptor: $connect")
        assertEquals(
            Json.decodeFromJsonElement(ProtocolDescriptor.serializer(), protocol),
            OipProtocol.DESCRIPTOR
        )
        assertEquals("oip", protocol["name"]?.jsonPrimitive?.content)
        assertEquals("0.1", protocol["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the CONNECT sent when the socket opens declares the OIP protocol descriptor`() {
        connection.connect("0xAGENT")
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())

        assertDeclaresOip(sentConnect())
    }

    @Test
    fun `the CONNECT resent after onboarding still declares the OIP protocol descriptor`() {
        connection.connect("0xAGENT")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"ok"}"""
        )

        assertDeclaresOip(sentConnect())
    }

    @Test
    fun `the OIP descriptor is not part of the signed CONNECT payload`() {
        // The Host verifies the signature over `payload` alone and reads
        // `protocol` off the envelope, so the descriptor must not change the
        // bytes the client signs.
        val signed = CopyOnWriteArrayList<String>()
        val conn = AgentConnection(
            keyManager,
            webSocketFactory = factory,
            signer = { _, canonical -> signed += canonical; "fake-signature" }
        )
        try {
            conn.connect("0xAGENT")
            factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())

            val canonical = signed.singleOrNull() ?: error("expected one signed CONNECT payload, saw $signed")
            assertFalse("protocol must stay on the envelope: $canonical", canonical.contains("protocol"))
            assertEquals(
                "the envelope's payload must be exactly what was signed",
                Json.parseToJsonElement(canonical),
                sentConnect()["payload"]
            )
        } finally {
            conn.teardown()
        }
    }

    // ── W16AAPPLE-16: v2 signed commands ──────────────────────────────

    @Test
    fun `CONNECT signs the signed_commands capability and a fresh nonce each time`() {
        val signed = CopyOnWriteArrayList<String>()
        val conn = AgentConnection(
            keyManager,
            webSocketFactory = factory,
            signer = { _, canonical -> signed += canonical; "fake-signature" }
        )
        try {
            conn.connect("0xAGENT")
            factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
            // ONBOARD_SUCCESS re-sends CONNECT on the same socket.
            factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}""")
            factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"ok"}""")

            val payloads = factory.lastWebSocket!!.sentMessages
                .filter { it.contains("\"type\":\"CONNECT\"") }
                .map { Json.parseToJsonElement(it).jsonObject["payload"]!!.jsonObject }
            assertEquals(2, payloads.size)
            payloads.forEachIndexed { i, payload ->
                // Inside the signature: a relay must not be able to strip it
                // and downgrade the socket to unsigned commands.
                assertEquals(1, payload["signed_commands"]?.jsonPrimitive?.intOrNull)
                assertEquals("the envelope's payload must be exactly what was signed", json(signed[i]), payload)
            }
            val nonces = payloads.map { it["nonce"]?.jsonPrimitive?.content }
            assertTrue("CONNECT needs a nonce: $nonces", nonces.none { it.isNullOrEmpty() })
            assertNotEquals(nonces[0], nonces[1])
        } finally {
            conn.teardown()
        }
    }

    @Test
    fun `every application command is signed for the agent CONNECT named`() {
        connection.connect("0xAGENT")
        simulateConnected()
        val ws = factory.lastWebSocket!!
        ws.sentMessages.clear()

        connection.sendMessage("hello", "0xAGENT", images = listOf("data:image/jpeg;base64,AAAA"))
        connection.respond("Python 3")
        connection.respondToApproval(approved = false, mode = "reject_explain", feedback = "not that file")
        connection.interrupt()

        val commands = ws.sentMessages.map { signedCommand(it) }
        // The Host executes the payload alone, so the INPUT's fields must be in it.
        val input = commands[0]
        assertEquals("INPUT", input["type"]?.jsonPrimitive?.content)
        assertEquals("hello", input["prompt"]?.jsonPrimitive?.content)
        assertEquals(json("""["data:image/jpeg;base64,AAAA"]"""), input["images"])
        assertNotNull(input["input_id"])
        assertEquals(json("""{"type":"ASK_USER_RESPONSE","answer":"Python 3"}"""), commands[1])
        assertEquals(
            json("""{"type":"APPROVAL_RESPONSE","approved":false,"scope":"once","mode":"reject_explain","feedback":"not that file"}"""),
            commands[2]
        )
        assertEquals(json("""{"type":"INTERRUPT"}"""), commands[3])
        val nonces = ws.sentMessages.map { Json.parseToJsonElement(it).jsonObject["payload"]!!.jsonObject["nonce"] }
        assertEquals("each command needs its own nonce", nonces.size, nonces.toSet().size)
    }

    @Test
    fun `a direct connection still signs the recipient into each command`() {
        // The relay needs `to` for routing; the Host needs it signed so a
        // command captured for one agent cannot be delivered to another.
        connection.connect("0xAGENT", directUrl = "http://10.0.2.2:8000")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respond("yes")

        signedCommand(factory.lastWebSocket!!.sentMessages.single(), to = "0xAGENT")
    }

    @Test
    fun `a signed command's payload is exactly what was signed`() {
        val signed = CopyOnWriteArrayList<String>()
        val conn = AgentConnection(
            keyManager,
            webSocketFactory = factory,
            signer = { _, canonical -> signed += canonical; "fake-signature" }
        )
        try {
            conn.connect("0xAGENT")
            factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"CONNECTED","session_id":"sess-1"}""")
            signed.clear()

            conn.respondToApproval(approved = true)

            val payload = Json.parseToJsonElement(factory.lastWebSocket!!.sentMessages.last()).jsonObject["payload"]
            assertEquals(json(signed.single()), payload)
            assertEquals(keyManager.canonicalJson(payload!!.jsonObject), signed.single())
        } finally {
            conn.teardown()
        }
    }

    @Test
    fun `transport frames the gate exempts stay unsigned`() {
        connection.connect("0xAGENT")
        simulateConnected()
        val ws = factory.lastWebSocket!!
        ws.sentMessages.clear()

        factory.lastListener?.onMessage(ws, """{"type":"PING"}""")
        connection.querySessionStatus("sess-1")

        assertEquals(
            listOf("""{"type":"PONG"}""", """{"type":"SESSION_STATUS","session":{"session_id":"sess-1"}}"""),
            ws.sentMessages.toList()
        )
    }

    @Test
    fun `onboarding pending suppresses auto-reconnect after the socket drops`() {
        connection.connect("0xAGENT")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )

        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")

        assertEquals(
            "onboardingPending must suppress the normal auto-reconnect attemptReconnect() would otherwise start",
            1,
            factory.socketCount
        )
    }

    @Test
    fun `rejected ONBOARD_SUBMIT auto-reconnects to re-prompt ONBOARD_REQUIRED`() = runBlocking {
        // User typed an invite code, server returned ERROR (e.g. "Invalid
        // invite code"). The fix path is synchronous from handleMessage
        // (this server keeps the WS open after ERROR — verified against
        // production oo.openonion.ai, PING/PONG continued for minutes
        // after rejection — so a close-driven reconnect would never fire).
        // The synthetic test setup mirrors that: send ERROR, do NOT send
        // onClosed, and assert a fresh WS opens.
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )

        connection.respondToOnboard(method = "invite_code", inviteCode = "WRONG_CODE")

        // Server rejects — the connection-error path fires, which must
        // schedule the re-CONNECT synchronously from handleMessage.
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"Invalid invite code"}"""
        )

        // schedulePostRejectionReconnect runs on reconnectScope (not the test
        // dispatcher), so wait for the factory to publish the fresh socket.
        awaitSockets(2)

        assertEquals(
            "must issue a fresh CONNECT after a submission rejection so the OnboardingFailedCard can swap back to an OnboardRequiredCard",
            2,
            factory.socketCount
        )
    }

    @Test
    fun `rejected ONBOARD_SUBMIT emits OnboardingFailed + Reconnecting, never ConnectionError`() = runBlocking {
        // Companion to the auto-reconnect test above: the banner fix means
        // the ERROR-handling path must surface a contextual chat card
        // (OnboardingFailed) and a Reconnecting state — *not* a
        // user-visible ConnectionError, which would flip the banner to
        // "Connection failed" and overwrite the Reconnecting state we
        // emit right after.
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )

        // AgentConnection._events is a replay=0 SharedFlow: events emitted
        // before a subscriber attaches are dropped, not delayed, so the
        // subscription is awaited rather than slept for.
        val log = collectEvents()

        connection.respondToOnboard(method = "invite_code", inviteCode = "WRONG_CODE")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"Invalid invite code"}"""
        )

        // Both emissions land in the same handleMessage pass, Reconnecting
        // last. Waiting for it is also what stops the negative assertion below
        // from passing on a frame that was never processed at all.
        log.await("the Reconnecting that closes the rejection pass") { events ->
            events.any { it is ConnectionEvent.Reconnecting }
        }
        val collected = log.seen

        // The two emissions we expect, in any order: a ChatItemReceived
        // carrying the OnboardingFailed card with the existing
        // OnboardRequired's id, and a Reconnecting event so the banner
        // state machine leaves Error for Reconnecting. Critically NO
        // ConnectionError — that's what would surface the misleading
        // "Connection failed" banner.
        val failed = collected.filterIsInstance<ConnectionEvent.ChatItemReceived>()
            .map { it.item }
            .filterIsInstance<ChatItem.OnboardingFailed>()
            .firstOrNull()
        assertNotNull("must emit ChatItemReceived(OnboardingFailed) so the card swaps in place", failed)
        assertEquals("OnboardingFailed must share the OnboardRequired's id for in-place swap", "ob1", failed!!.id)
        assertEquals("OnboardingFailed reason must be the server's ERROR message", "Invalid invite code", failed.reason)

        assertTrue(
            "must emit Reconnecting so the banner state stays in the connecting family after the new WS opens",
            collected.any { it is ConnectionEvent.Reconnecting }
        )
        assertEquals(
            "must NOT emit ConnectionError — that would flip the banner to 'Connection failed' for a non-fatal rejection",
            false,
            collected.any { it is ConnectionEvent.ConnectionError }
        )
    }

    @Test
    fun `post-rejection reconnect suppresses the old WS's onClosed Disconnected event`() = runBlocking {
        // After the explicit close in schedulePostRejectionReconnect, the
        // OkHttp listener's onClosed fires for the OLD socket with code
        // 1000. Without suppression, that fires a Disconnected event which
        // would overwrite the Reconnecting state we just set in
        // handleMessage and collapse the banner — the new WS never
        // receives CONNECTED for an onboardable agent, so nothing else
        // would lift it back.
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )

        connection.respondToOnboard(method = "invite_code", inviteCode = "WRONG_CODE")
        val oldWebSocket = factory.lastWebSocket!!
        val oldListener = factory.lastListener
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"Invalid invite code"}"""
        )

        // The replacement socket has to exist before the marker below can be
        // fed down it — and it is the socket whose arrival makes the old one
        // stale in the first place.
        awaitSockets(2)
        val log = collectEvents()

        oldListener?.onClosed(oldWebSocket, 1000, "Onboarding rejected, reconnecting")

        // The marker travels the live socket and the same bus. Its arrival is
        // what makes an empty Disconnected list mean "suppressed" rather than
        // "the collector had not attached yet".
        log.awaitDrained()

        assertEquals(
            "must not surface the old WS's post-rejection close as a Disconnected event",
            emptyList<ConnectionEvent.Disconnected>(),
            log.seen.filterIsInstance<ConnectionEvent.Disconnected>()
        )
    }

    @Test
    fun `lastOnboardRequiredId is cleared on OnboardSuccess`() = runBlocking {
        // Guards the gating of the post-rejection synthesis: a stale id
        // from a previous session must not be reused to synthesize an
        // OnboardingFailed card after a successful onboarding followed by
        // a new ERROR frame (e.g. unrelated server crash post-onboarding).
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"full","message":"ok"}"""
        )

        // Subscribe before triggering so we don't miss events.
        val log = collectEvents()

        // Post-onboarding ERROR (server crash mid-conversation) — the
        // post-rejection branch in handleMessage must skip the
        // OnboardingFailed synthesis because lastOnboardRequiredId is
        // null after success AND onboardingPending is false anyway. The
        // observable consequence: no ChatItemReceived(OnboardingFailed)
        // is emitted.
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"some unrelated server crash"}"""
        )

        // The plain-error branch is the one that must run, and its own
        // ConnectionError is the barrier: the synthesis branch emits
        // OnboardingFailed instead, so this wait cannot pass a broken build.
        log.await("the unrelated ERROR to surface as a plain ConnectionError") { events ->
            events.any { it is ConnectionEvent.ConnectionError }
        }

        assertEquals(
            "must not synthesize an OnboardingFailed card from a stale id after OnboardSuccess",
            emptyList<ConnectionEvent>(),
            log.seen.filterIsInstance<ConnectionEvent.ChatItemReceived>()
                .filter { it.item is ChatItem.OnboardingFailed }
        )
    }

    @Test
    fun `lastOnboardRequiredId is reset by a fresh connect()`() = runBlocking {
        // Belt-and-braces: a manual reconnect via setup/teardown also
        // needs to clear the field so the next session doesn't inherit a
        // stale id. Mirrors the OnboardSuccess-clears-id contract above:
        // if connect() didn't reset the field, a previously-rejected
        // agent's gate id would leak into the new session's OnboardingFailed
        // card on the next rejection.
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob-old","methods":["invite_code"]}"""
        )

        // Reset by a fresh connect() — mimics a switch-agent or full reconnect.
        connection.connect("0xAGENT2")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob-new","methods":["invite_code"]}"""
        )

        val log = collectEvents()

        connection.respondToOnboard(method = "invite_code", inviteCode = "WRONG_CODE")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"Invalid invite code"}"""
        )
        log.await("the Reconnecting that closes the rejection pass") { events ->
            events.any { it is ConnectionEvent.Reconnecting }
        }

        val failed = log.seen.filterIsInstance<ConnectionEvent.ChatItemReceived>()
            .map { it.item }
            .filterIsInstance<ChatItem.OnboardingFailed>()
            .firstOrNull()
        assertNotNull("must emit OnboardingFailed with the NEW session's id, not the leaked previous one", failed)
        assertEquals(
            "stale id from the previous session must not leak into the new session's error path",
            "ob-new",
            failed!!.id
        )
    }

    /**
     * Drives one full rejection round trip and returns every event the second
     * socket produced: ONBOARD_REQUIRED(ob1) → wrong code → ERROR →
     * post-rejection reconnect → the server's re-prompt under [rePromptFrame].
     */
    private suspend fun rejectThenRePrompt(rePromptFrame: String): List<ConnectionEvent> {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
        )
        connection.respondToOnboard(method = "invite_code", inviteCode = "WRONG_CODE")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"Invalid invite code"}"""
        )

        // schedulePostRejectionReconnect runs on reconnectScope, so wait for
        // the replacement socket before driving the re-prompt down it.
        awaitSockets(2)
        assertEquals("the post-rejection reconnect must have opened a fresh socket", 2, factory.socketCount)

        val log = collectEvents()

        factory.lastListener?.onMessage(factory.lastWebSocket!!, rePromptFrame)
        log.await("the re-prompt to reach the chat pipeline") { events ->
            events.any { it is ConnectionEvent.ChatItemReceived }
        }
        return log.seen
    }

    private fun List<ConnectionEvent>.onboardRequired(): ChatItem.OnboardRequired? =
        filterIsInstance<ConnectionEvent.ChatItemReceived>()
            .map { it.item }
            .filterIsInstance<ChatItem.OnboardRequired>()
            .firstOrNull()

    @Test
    fun `a re-prompt after a rejection keeps the gate card's id`() = runBlocking {
        // One gate cycle owns one card. The server re-prompts with a brand-new
        // id, and adopting it changes the LazyColumn key, which rebuilds the
        // row and tears the soft keyboard away from a user already retyping.
        val collected = rejectThenRePrompt(
            """{"type":"ONBOARD_REQUIRED","id":"ob2","methods":["invite_code"]}"""
        )

        val reprompt = collected.onboardRequired()
        assertNotNull("the re-prompt must reach the chat pipeline", reprompt)
        assertEquals(
            "the re-prompt must reuse the card id already on screen so the row is never rebuilt",
            "ob1",
            reprompt!!.id
        )
    }

    @Test
    fun `a re-prompt keeps its own content under the reused id`() = runBlocking {
        // Only the id is carried over — a changed method list or payment
        // option is the server's new instruction and must reach the card.
        val collected = rejectThenRePrompt(
            """{"type":"ONBOARD_REQUIRED","id":"ob2","methods":["payment"],"payment_amount":12.5}"""
        )

        val reprompt = collected.onboardRequired()
        assertNotNull(reprompt)
        assertEquals("ob1", reprompt!!.id)
        assertEquals("the new frame's methods must not be lost to the id reuse", listOf("payment"), reprompt.methods)
        assertEquals(12.5, reprompt.paymentAmount!!, 0.0)
    }

    @Test
    fun `OnboardSuccess frees the carried id so the next gate gets a fresh card`() = runBlocking {
        // The invariant on lastOnboardRequiredId: carrying the id across a
        // re-prompt must not let it outlive the gate it belongs to.
        rejectThenRePrompt("""{"type":"ONBOARD_REQUIRED","id":"ob2","methods":["invite_code"]}""")
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"full","message":"ok"}"""
        )

        val log = collectEvents()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob3","methods":["invite_code"]}"""
        )
        log.await("the next gate's card") { events ->
            events.any { it is ConnectionEvent.ChatItemReceived && it.item is ChatItem.OnboardRequired }
        }

        assertEquals(
            "a gate opened after the previous one resolved must get its own card",
            "ob3",
            log.seen.onboardRequired()?.id
        )
    }

    @Test
    fun `a fresh connect frees the carried id so the next gate gets a fresh card`() = runBlocking {
        // Same invariant from the other direction: an explicit reconnect or
        // agent switch starts a new cycle, so nothing is carried into it.
        rejectThenRePrompt("""{"type":"ONBOARD_REQUIRED","id":"ob2","methods":["invite_code"]}""")

        connection.connect("0xAGENT2")
        simulateConnected("sess-2")

        val log = collectEvents()
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ONBOARD_REQUIRED","id":"ob3","methods":["invite_code"]}"""
        )
        log.await("the next gate's card") { events ->
            events.any { it is ConnectionEvent.ChatItemReceived && it.item is ChatItem.OnboardRequired }
        }

        assertEquals(
            "a fresh connect() must not carry the previous cycle's card id",
            "ob3",
            log.seen.onboardRequired()?.id
        )
    }

    @Test
    fun `session_sync replay emits assistant turns oldest-first, not newest-first`() = runBlocking {
        // parseSessionSync (ProtocolParser.kt) returns the newest turn as
        // ParseResult.event and all earlier turns (oldest-first among
        // themselves) as extraEvents. handleMessage used to emit `event`
        // before `extraEvents`, so a full-history replay (e.g. the first
        // session_sync after a fresh connect, before any turn is already
        // in chatItems) rendered as [newest, oldest, ..., 2nd-newest] —
        // and since persistMessage stamps Room's timestamp column at
        // processing time, that reversed order got permanently baked into
        // local storage too. Fixed by emitting extraEvents before event.
        connection.connect("0xAGENT")
        simulateConnected()

        val log = collectEvents()

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"session_sync","session":{"session_id":"sess-1","messages":[
                {"role":"user","content":"Hi"},
                {"role":"assistant","content":"reply1"},
                {"role":"user","content":"Who?"},
                {"role":"assistant","content":"reply2"},
                {"role":"user","content":"Chinese?"},
                {"role":"assistant","content":"reply3"}
            ]}}""".trimIndent()
        )

        log.await("all three replayed turns") { events ->
            events.count { it is ConnectionEvent.ChatItemReceived && it.item is ChatItem.Turn } >= 3
        }

        val replayedContents = log.seen.filterIsInstance<ConnectionEvent.ChatItemReceived>()
            .mapNotNull { (it.item as? ChatItem.Turn)?.agent?.content }

        assertEquals(
            "a fresh session_sync replay must surface turns oldest-first; newest-first " +
                "would show the latest reply above its own conversation history",
            listOf("reply1", "reply2", "reply3"),
            replayedContents
        )
    }

    @Test
    fun `a closed socket without onboarding pending does attempt to reconnect`() = runBlocking {
        connection.connect("0xAGENT")
        // No ONBOARD_REQUIRED this time — onboardingPending stays false.

        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")

        // A real wait, not a scheduler bet: attemptReconnect() reopens after
        // an honest reconnectBackoffMs(1) = 2000ms delay() on its own scope.
        // Waiting on the factory ends the moment that socket exists.
        awaitSockets(2)
        assertTrue("attemptReconnect() should have opened a new socket", factory.socketCount >= 2)
    }

    @Test
    fun `a normal 1000 close (matching disconnect's own close code) never triggers a reconnect`() {
        connection.connect("0xAGENT")
        connection.disconnect()
        val countAfterDisconnect = factory.socketCount

        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1000, "closed by disconnect()")

        assertEquals(
            "code 1000 is treated as a deliberate disconnect, not a drop to recover from",
            countAfterDisconnect,
            factory.socketCount
        )
    }

    @Test
    fun `a second reconnect socket failure keeps climbing the ladder instead of stalling`() = runBlocking {
        // Regression test: `isReconnecting` is set true by the first
        // attemptReconnect() call and only reset by onOpen, so the socket
        // *that call opens* finds it already true when it fails in turn —
        // before the fix that stalled the ladder after exactly one hop.
        connection.connect("0xAGENT")
        assertEquals(1, factory.socketCount)

        // Socket #1 drops -> attemptReconnect() schedules socket #2 after
        // reconnectBackoffMs(1) = 2000ms.
        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")

        awaitSockets(2)
        assertEquals("first backoff must open a second socket", 2, factory.socketCount)

        // Socket #2 (the reconnect attempt itself) also fails. This is the
        // exact bug scenario — must still schedule socket #3 after
        // reconnectBackoffMs(2) = 4000ms rather than stalling.
        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")

        awaitSockets(3)
        assertEquals(
            "socket #2 failing must schedule socket #3 within the second backoff window — " +
                "the ladder must not stall after exactly one hop",
            3,
            factory.socketCount
        )
    }

    @Test
    fun `reconnect attempts exhausted emits Disconnected and opens no further sockets`() = runBlocking {
        connection.connect("0xAGENT")

        // Fast-forward past the exponential backoff instead of burning
        // through the real 2s+4s+8s+16s+32s of delay() calls needed to
        // organically climb to the cap: set reconnectAttempts straight to
        // it, so the very next failure hits attemptReconnect()'s
        // "attempts >= max" guard immediately.
        val attemptsField = AgentConnection::class.java.getDeclaredField("reconnectAttempts")
        attemptsField.isAccessible = true
        attemptsField.set(connection, AgentConnection.MAX_RECONNECT_ATTEMPTS)

        val log = collectEvents()

        factory.lastListener?.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")

        log.await("the give-up Disconnected") { events ->
            events.any { it is ConnectionEvent.Disconnected }
        }

        assertTrue(
            "must emit Disconnected once MAX_RECONNECT_ATTEMPTS is exhausted",
            log.seen.any { it is ConnectionEvent.Disconnected }
        )
        assertEquals(
            "must not open another socket once attempts are exhausted",
            1,
            factory.socketCount
        )
    }

    @Test
    fun `a stale-session ERROR after CONNECT drops the persisted session and retries with sessionId=null`() = runBlocking {
        // Mirrors the real-device bug this fix addresses: the auto-reconnect
        // ladder (attemptReconnect/openWebSocket) re-establishes the socket
        // entirely on its own and resends CONNECT with the old (still
        // server-side-attached) session_id — outside of any
        // ConnectToAgentUseCase call, so that use case's own first-connect
        // recovery never sees it. Before this fix nothing handled the
        // rejection here: the server keeps the socket open after ERROR, so
        // no onClosed/onFailure fires either, and the user is stuck with a
        // socket that looks open but will never receive CONNECTED.
        connection.connect("0xAGENT")
        simulateConnected("sess-old")
        assertEquals("sess-old", connection.currentSession?.sessionId)

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","error":"Session is already attached to another connection"}"""
        )

        awaitSockets(2)
        assertEquals("must open a fresh socket to retry after dropping the stale session", 2, factory.socketCount)
        assertNull("the stale session must be dropped before retrying", connection.currentSession)

        // The retried CONNECT on the new socket must carry no session_id.
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        val resentConnect = factory.lastWebSocket!!.sentMessages.singleOrNull { it.contains("\"type\":\"CONNECT\"") }
        assertNotNull("a fresh CONNECT must be sent on the new socket", resentConnect)
        assertFalse(
            "the retried CONNECT must not carry the stale session_id",
            resentConnect!!.contains("\"session_id\"")
        )

        // Completing the handshake this time recovers the connection.
        simulateConnected("sess-new")
        assertTrue(connection.isConnected())
    }

    @Test
    fun `a second stale-session ERROR in the same cycle does not loop forever`() = runBlocking {
        // Anti-loop bound: recoverFromStaleSession runs at most once per
        // connect() cycle (staleSessionRecoveryAttempted). If the retried
        // sessionId=null CONNECT is rejected again — e.g. some other
        // condition that happens to produce the same wording — a third
        // socket must not be opened; the rejection instead falls through to
        // the normal (non-fatal, since we're already past Connected)
        // ConnectionError path.
        connection.connect("0xAGENT")
        simulateConnected("sess-old")

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","error":"Session is already attached to another connection"}"""
        )
        awaitSockets(2)

        val log = collectEvents()

        // The retried (sessionId=null) CONNECT gets rejected again.
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","error":"Session is already attached to another connection"}"""
        )

        // The fall-through ConnectionError is the barrier: a second recovery
        // would reopen the socket instead of emitting it, so the socket-count
        // assertion below cannot pass on a frame still in flight.
        log.await("the second rejection to fall through to ConnectionError") { events ->
            events.any { it is ConnectionEvent.ConnectionError }
        }

        assertEquals(
            "must not attempt a third socket — recovery is bounded to once per connect() cycle",
            2,
            factory.socketCount
        )
        assertTrue(
            "the second rejection must surface as a normal ConnectionError instead of recovering again",
            log.seen.any { it is ConnectionEvent.ConnectionError }
        )
    }

    @Test
    fun `a non-stale-session ERROR after CONNECT is unaffected by the stale-session recovery`() = runBlocking {
        // Regression guard for hasConnectedThisAttempt's validated design
        // (ConnectionRepositoryImpl treats a post-Connected ERROR as
        // non-fatal, socket stays open): an unrelated business-logic
        // rejection like "Insufficient ConnectOnion Credits" must keep
        // surfacing as a plain ConnectionError, not trigger the new
        // recovery path or touch the current session.
        connection.connect("0xAGENT")
        simulateConnected("sess-1")

        val log = collectEvents()

        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","error":"Insufficient ConnectOnion Credits"}"""
        )

        log.await("the unrelated error to surface as ConnectionError") { events ->
            events.any { it is ConnectionEvent.ConnectionError }
        }

        assertTrue(
            "an unrelated error must still surface as ConnectionError",
            log.seen.any { it is ConnectionEvent.ConnectionError }
        )
        assertEquals("an unrelated error must not open a second socket", 1, factory.socketCount)
        assertEquals(
            "an unrelated error must not touch the current session",
            "sess-1",
            connection.currentSession?.sessionId
        )
    }

    // ── per-conversation sessions ────────────────────────────────────

    private fun connectFrames(ws: FakeWebSocket) =
        ws.sentMessages.filter { it.contains("\"type\":\"CONNECT\"") }

    private fun sessionIdOf(connectFrame: String): String? =
        Regex("\"session_id\":\"([^\"]+)\"").find(connectFrame)?.groupValues?.get(1)

    @Test
    fun `switching conversations opens a new socket carrying the target session id`() = runBlocking {
        // Not a second CONNECT on the live socket: the relay pins one session
        // per socket and echoed the previous conversation's id back at us when
        // we tried (observed on the device against wss://oo.openonion.ai).
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-A")

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)

        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        val sent = connectFrames(factory.lastWebSocket!!).single()
        assertEquals("sess-B", sessionIdOf(sent))
    }

    @Test
    fun `a CONNECTED still in flight on the replaced socket is ignored`() = runBlocking {
        // The switch window. The old socket's reply arrives after the new
        // socket exists; processing it would put the connection back on the
        // conversation the user just left.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        val oldWs = factory.lastWebSocket!!
        val oldListener = factory.lastListener!!
        oldListener.onOpen(oldWs, fakeResponse())

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)

        oldListener.onMessage(oldWs, """{"type":"CONNECTED","session_id":"sess-A"}""")

        assertEquals(
            "a frame from the replaced socket reverted the switch",
            "sess-B",
            connection.currentSession?.sessionId
        )
        assertFalse("a replaced socket must not mark the connection ready", connection.isConnected())
    }

    @Test
    fun `any frame from a replaced socket is ignored, not just CONNECTED`() = runBlocking {
        // The session-id check only inspects CONNECTED. Everything else a
        // dead socket may still deliver — a reply, a tool call — would be
        // folded into the conversation the user has already left.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        val oldWs = factory.lastWebSocket!!
        val oldListener = factory.lastListener!!
        oldListener.onOpen(oldWs, fakeResponse())
        simulateConnected("sess-A")

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)

        val log = collectEvents()
        oldListener.onMessage(oldWs, """{"type":"OUTPUT","result":"a reply for the chat we left"}""")
        // The marker goes down the live socket, so its arrival proves the
        // collector was attached and drained — without it an empty list would
        // also be what a subscription that never landed looks like.
        log.awaitDrained()

        assertTrue(
            "a frame from the replaced socket was processed: ${log.seen}",
            log.seen.none { it is ConnectionEvent.OutputReceived }
        )
    }

    @Test
    fun `a CONNECTED naming a session we did not ask for is refused, not persisted`() = runBlocking {
        // The device bug: we asked for conversation B's id and the host
        // answered with A's, which we then saved under B. The row would name
        // a session it never requested.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener?.onOpen(ws, fakeResponse())
        simulateConnected("sess-A")

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)
        val newWs = factory.lastWebSocket!!
        factory.lastListener?.onOpen(newWs, fakeResponse())

        // The host ignores our session_id and keeps its own.
        factory.lastListener?.onMessage(newWs, """{"type":"CONNECTED","session_id":"sess-A"}""")

        assertEquals(
            "the session we asked for must not be replaced by the one we were handed",
            "sess-B",
            connection.currentSession?.sessionId
        )
        assertFalse("a refused CONNECTED must not establish the connection", connection.isConnected())
    }

    @Test
    fun `a CONNECTED naming an id we have never been on is adopted, not refused`() {
        // The reassignment the protocol documents: the id we asked for is
        // owned by another caller, so the host allocates a new one and reports
        // it. Refusing that would leave us on a session we cannot name.
        connection.setSession(SessionState(sessionId = "sess-mine"))
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener?.onOpen(ws, fakeResponse())

        factory.lastListener?.onMessage(ws, """{"type":"CONNECTED","session_id":"server-reassigned"}""")

        assertEquals("server-reassigned", connection.currentSession?.sessionId)
        assertTrue(connection.isConnected())
    }

    @Test
    fun `two CONNECTs in the same second never sign the same payload`() = runBlocking {
        // The server refuses a CONNECT whose signature it has already seen
        // ("one signature opens one connection", auth.py). The payload is
        // second-resolution and otherwise deterministic, so without the
        // monotonic step in sendConnectMessage a switch inside one second
        // would sign identical bytes and be rejected as a replay.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        val first = connectFrames(factory.lastWebSocket!!).single()

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        val second = connectFrames(factory.lastWebSocket!!).single()

        val timestamps = listOf(first, second).map {
            Regex("\"payload\":\\{\"timestamp\":(\\d+)").find(it)!!.groupValues[1].toLong()
        }
        assertEquals(
            "every CONNECT must sign a distinct timestamp: $timestamps",
            2,
            timestamps.toSet().size
        )
        assertTrue("timestamps must not go backwards", timestamps[1] > timestamps[0])
    }

    @Test
    fun `a rebuilt connection does not reuse the previous one's CONNECT second`() {
        // ConnectionRepositoryImpl builds a fresh AgentConnection for every
        // connect(), and its stale-session retry re-connects milliseconds after
        // the rejection. A per-instance monotonic counter resets exactly there,
        // so the retry would sign the first attempt's bytes again and the
        // server would refuse it as a replay.
        connection.connect("0xAGENT")
        val firstSocket = factory.lastWebSocket!!
        factory.lastListener?.onOpen(firstSocket, fakeResponse())
        val first = connectFrames(firstSocket).single()

        val rebuilt = AgentConnection(keyManager, webSocketFactory = factory, signer = { _, _ -> "fake-signature" })
        rebuilt.connect("0xAGENT")
        val secondSocket = factory.lastWebSocket!!
        factory.lastListener?.onOpen(secondSocket, fakeResponse())
        val second = connectFrames(secondSocket).single()

        val timestamps = listOf(first, second).map {
            Regex("\"payload\":\\{\"timestamp\":(\\d+)").find(it)!!.groupValues[1].toLong()
        }
        assertTrue(
            "a rebuilt connection must sign a later second: $timestamps",
            timestamps[1] > timestamps[0]
        )
    }

    @Test
    fun `an INPUT sent during a switch waits for that session's CONNECTED`() = runBlocking {
        // The ordering the send path depends on. switchSession drops the
        // ready flag before it returns, so a message sent immediately after
        // is queued rather than written to a socket that is still CONNECTed
        // as the previous conversation — which is how a turn ends up filed
        // under the wrong session server-side.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-A")
        assertTrue("test setup: must start ready", connection.isConnected())

        connection.switchSession(SessionState(sessionId = "sess-B"))
        awaitSockets(2)
        val newWs = factory.lastWebSocket!!
        factory.lastListener?.onOpen(newWs, fakeResponse())
        // Sent once the replacement socket is open but before its CONNECTED:
        // the socket is non-null, so only the ready flag stands between this
        // INPUT and a session the server has not agreed to yet.
        connection.sendMessage("Hi", "0xAGENT")

        assertTrue(
            "the INPUT went out before the new session was acknowledged",
            newWs.sentMessages.none { it.contains("\"type\":\"INPUT\"") }
        )

        factory.lastListener?.onMessage(newWs, """{"type":"CONNECTED","session_id":"sess-B"}""")

        val input = newWs.sentMessages.singleOrNull { it.contains("\"type\":\"INPUT\"") }
        assertNotNull("the queued INPUT must be flushed once CONNECTED lands", input)
        assertTrue("the flushed INPUT must carry the original prompt", input!!.contains("Hi"))
    }

    @Test
    fun `a message queued for one conversation is not flushed into another`() = runBlocking {
        // The outbox is keyed by agent, and the flush happens on whatever
        // session the socket has when CONNECTED lands. Queue in B, switch to
        // C before B is acknowledged, and B's message would be sent as C.
        connection.setSession(SessionState(sessionId = "sess-A"))
        connection.connect("0xAGENT")
        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-A")

        connection.switchSession(SessionState(sessionId = "sess-B"))
        connection.sendMessage("meant for B", "0xAGENT")
        awaitSockets(2)

        connection.switchSession(SessionState(sessionId = "sess-C"))
        awaitSockets(3)
        val cWs = factory.lastWebSocket!!
        factory.lastListener?.onOpen(cWs, fakeResponse())
        factory.lastListener?.onMessage(cWs, """{"type":"CONNECTED","session_id":"sess-C"}""")

        assertTrue(
            "B's message was sent on C's session: " + cWs.sentMessages,
            cWs.sentMessages.none { it.contains("meant for B") }
        )
    }

    @Test
    fun `switchSession while not connected only stages the session for the next CONNECT`() {
        connection.switchSession(SessionState(sessionId = "sess-B"))

        assertEquals("staging a session must not open a socket", 0, factory.socketCount)
        assertEquals("sess-B", connection.currentSession?.sessionId)
    }

    // ── the reconnect ladder vs. a socket the app replaces on purpose ──
    //
    // Both tests below need the ladder parked mid-backoff, which is a state a
    // wall clock can only be waited into. The dispatcher seam puts delay() on
    // the virtual clock instead, so "the rung is still counting down" and "the
    // rung has now woken" are both things the test decides rather than races.

    /** An [AgentConnection] whose reconnect ladder runs on this scope's virtual clock. */
    private fun TestScope.connectionOnVirtualClock(isAppVisible: () -> Boolean = { true }) = AgentConnection(
        keyManager,
        webSocketFactory = factory,
        signer = { _, _ -> "fake-signature" },
        reconnectDispatcher = StandardTestDispatcher(testScheduler),
        isAppVisible = isAppVisible
    )

    /** Everything [conn] emits, collected eagerly on the virtual clock — no cross-thread wait. */
    private fun TestScope.recordEvents(conn: AgentConnection): List<ConnectionEvent> {
        val seen = mutableListOf<ConnectionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            conn.events.collect { seen += it }
        }
        return seen
    }

    @Test
    fun `switching conversations disarms the reconnect rung already counting down`() = runTest {
        // connect(), teardown() and retryNow() all cancel the pending rung
        // before replacing the socket; reopenSocket did not. A rung armed by an
        // earlier drop therefore survived the switch, woke inside its own
        // backoff, still saw isReconnecting (only CONNECTED/connect()/teardown()
        // clear it), and closed the socket the switch had just opened.
        val conn = connectionOnVirtualClock()
        conn.setSession(SessionState(sessionId = "sess-A"))
        conn.connect("0xAGENT")
        val dropped = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(dropped, fakeResponse())

        // The socket drops, arming rung #1 for reconnectBackoffMs(1) = 2000ms.
        factory.lastListener!!.onClosed(dropped, 1006, "abnormal closure")
        advanceTimeBy(500)
        assertEquals("test setup: the rung must still be counting down", 1, factory.socketCount)

        conn.switchSession(SessionState(sessionId = "sess-B"))
        runCurrent()
        val switched = factory.lastWebSocket!!
        // Past the rung's wake-up time: if it was never disarmed, this is where
        // it fires.
        advanceUntilIdle()

        assertEquals(
            "the armed rung woke after the switch and raced a third socket in",
            2,
            factory.socketCount
        )
        assertNull(
            "the stale rung closed the socket the switch had just opened",
            switched.closeCode
        )
        conn.teardown()
    }

    @Test
    fun `the CONNECT sent on reconnect carries the session id and the last message id seen`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        val first = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(first, fakeResponse())
        simulateConnected("sess-1")
        factory.lastListener!!.onMessage(first, """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}""")
        factory.lastListener!!.onMessage(first, """{"type":"thinking","id":"ev-2","content":"working on it"}""")

        // The socket drops; rung #1 (2 s) wakes on the virtual clock and opens a second socket.
        factory.lastListener!!.onClosed(first, 1006, "abnormal closure")
        advanceUntilIdle()
        assertEquals("test setup: the ladder should have opened a replacement socket", 2, factory.socketCount)
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())

        val connect = sentConnect()
        assertEquals("sess-1", connect["session_id"]?.jsonPrimitive?.content)
        assertEquals("ev-2", connect["last_msg_id"]?.jsonPrimitive?.content)
        conn.teardown()
    }

        /**
     * Opens [conn], delivers [before] on the first socket, drops it, and returns
     * the socket the ladder opens next, already CONNECTED again.
     */
    private fun TestScope.reconnectAfter(conn: AgentConnection, before: List<String>): WebSocket {
        conn.connect("0xAGENT")
        val first = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(first, fakeResponse())
        simulateConnected("sess-1")
        before.forEach { factory.lastListener!!.onMessage(first, it) }

        factory.lastListener!!.onClosed(first, 1006, "abnormal closure")
        advanceUntilIdle()
        val second = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(second, fakeResponse())
        simulateConnected("sess-1")
        return second
    }

    @Test
    fun `frames replayed after a reconnect are delivered once and in order`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        val a = """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}"""
        val b = """{"type":"tool_call","id":"ev-2","tool_id":"t2","name":"bash"}"""
        val c = """{"type":"tool_call","id":"ev-3","tool_id":"t3","name":"bash"}"""
        val second = reconnectAfter(conn, listOf(a, b))

        // The Host replays the turn from the top: a and b again, then c, which is new.
        listOf(a, b, c).forEach { factory.lastListener!!.onMessage(second, it) }

        val ids = seen.filterIsInstance<ConnectionEvent.ChatItemReceived>().map { it.item.id }
        assertEquals(listOf("t1", "t2", "t3"), ids)
        conn.teardown()
    }

    @Test
    fun `a replayed approval_needed does not put the chat back into waiting`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        val approval = """{"type":"approval_needed","id":"ap-1","tool":"bash","arguments":{"command":"ls"}}"""
        val second = reconnectAfter(conn, listOf(approval))

        factory.lastListener!!.onMessage(second, approval)

        assertEquals(1, seen.count { it is ConnectionEvent.Waiting })
        conn.teardown()
    }

    @Test
    fun `frames that share an id but not a type or status are not mistaken for repeats`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, fakeResponse())
        simulateConnected("sess-1")

        // The Host reuses one id for a call and its result, and for every status of a compaction.
        listOf(
            """{"type":"llm_call","id":"L1","model":"m"}""",
            """{"type":"llm_result","id":"L1","model":"m","status":"done"}""",
            """{"type":"compact","id":"C1","status":"compacting"}""",
            """{"type":"compact","id":"C1","status":"done"}"""
        ).forEach { factory.lastListener!!.onMessage(ws, it) }

        val delivered = seen.count { it is ConnectionEvent.ChatItemReceived || it is ConnectionEvent.ChatItemUpdated }
        assertEquals("every one of the four frames must reach the chat", 4, delivered)
        conn.teardown()
    }

    @Test
    fun `a burst of frames bigger than the event buffer is delivered whole`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, fakeResponse())
        simulateConnected("sess-1")

        // A consumer stuck on its first event while the burst arrives, like a slow Room write.
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            conn.events.collect { event ->
                release.await()
                if (event is ConnectionEvent.ChatItemReceived) delivered += event.item.id
            }
        }

        val burst = 200
        repeat(burst) { i ->
            factory.lastListener!!.onMessage(ws, """{"type":"tool_call","id":"ev-$i","tool_id":"t$i","name":"bash"}""")
        }
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals((0 until burst).map { "t$it" }, delivered)
        conn.teardown()
    }
    @Test
    fun `a conversation switch does not carry the previous last message id`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        val first = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(first, fakeResponse())
        simulateConnected("sess-A")
        factory.lastListener!!.onMessage(first, """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}""")

        conn.switchSession(SessionState(sessionId = "sess-B"))
        runCurrent()
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())

        val connect = sentConnect()
        assertEquals("sess-B", connect["session_id"]?.jsonPrimitive?.content)
        assertNull("ev-1 belongs to sess-A's log, not sess-B's", connect["last_msg_id"])
        conn.teardown()
    }

    @Test
    fun `an id on a frame written straight to the socket is never sent as last_msg_id`() = runTest {
        val conn = connectionOnVirtualClock()
        val frames = listOf(
            """{"type":"AGENT_PROFILE","id":"host-1","name":"agent","model":"m"}""",
            """{"type":"DASHBOARD_SNAPSHOT","id":"host-2","html":"<p>hi</p>"}"""
        )

        reconnectAfter(conn, frames)

        assertNull(sentConnect()["last_msg_id"])
        conn.teardown()
    }

    @Test
    fun `a snapshot re-sent on every CONNECT is not mistaken for a replay`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        val profile = """{"type":"AGENT_PROFILE","name":"agent","model":"m"}"""
        val second = reconnectAfter(conn, listOf(profile))

        factory.lastListener!!.onMessage(second, profile)

        assertEquals(2, seen.count { it is ConnectionEvent.AgentProfileReceived })
        conn.teardown()
    }

    @Test
    fun `the last message id is not part of the signed CONNECT payload`() = runTest {
        val signed = CopyOnWriteArrayList<String>()
        val conn = AgentConnection(
            keyManager,
            webSocketFactory = factory,
            signer = { _, canonical -> signed += canonical; "fake-signature" },
            reconnectDispatcher = StandardTestDispatcher(testScheduler)
        )
        conn.connect("0xAGENT")
        val first = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(first, fakeResponse())
        simulateConnected("sess-1")
        factory.lastListener!!.onMessage(first, """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}""")
        factory.lastListener!!.onClosed(first, 1006, "abnormal closure")
        advanceUntilIdle()
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())

        val reconnect = sentConnect()
        assertEquals("ev-1", reconnect["last_msg_id"]?.jsonPrimitive?.content)
        val canonical = signed.last()
        assertFalse("last_msg_id must stay on the envelope: $canonical", canonical.contains("last_msg_id"))
        assertEquals(
            "the envelope's payload must be exactly what was signed",
            Json.parseToJsonElement(canonical),
            reconnect["payload"]
        )
        conn.teardown()
    }

    @Test
    fun `repeats are dropped when the relay stamps a different conn_id on the new socket`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        fun relayed(toolId: String, connId: String) =
            """{"session_id":"sess-1","type":"tool_call","id":"ev-$toolId","tool_id":"$toolId","name":"bash","conn_id":"$connId"}"""
        val second = reconnectAfter(conn, listOf(relayed("t1", "conn-a"), relayed("t2", "conn-a")))

        // The same logged events on a new socket, which the relay names differently.
        listOf(relayed("t1", "conn-b"), relayed("t2", "conn-b"), relayed("t3", "conn-b"))
            .forEach { factory.lastListener!!.onMessage(second, it) }

        val ids = seen.filterIsInstance<ConnectionEvent.ChatItemReceived>().map { it.item.id }
        assertEquals(listOf("t1", "t2", "t3"), ids)
        conn.teardown()
    }

    @Test
    fun `a replayed session_sync is processed, not dropped`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        val sync = """{"type":"session_sync","id":"ss-1","session":{"session_id":"sess-1","messages":[{"role":"assistant","content":"hello"}]}}"""
        val second = reconnectAfter(conn, listOf(sync))

        factory.lastListener!!.onMessage(second, sync)

        // A replayed snapshot carries the session as it is now, so it is never a repeat. The reply's id comes
        // from its content, so receiving it twice is safe.
        assertEquals(2, seen.count { it is ConnectionEvent.ChatItemReceived && it.fromSessionSnapshot })
        conn.teardown()
    }

    @Test
    fun `reset forgets the last message id and the frames already seen`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, fakeResponse())
        simulateConnected("sess-1")
        val call = """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}"""
        factory.lastListener!!.onMessage(ws, call)

        conn.reset()
        conn.switchSession(SessionState(sessionId = "sess-1"))
        runCurrent()
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        factory.lastListener!!.onMessage(factory.lastWebSocket!!, call)

        assertNull(sentConnect()["last_msg_id"])
        assertEquals("after a reset the same frame is news again", 2, seen.count { it is ConnectionEvent.ChatItemReceived })
        conn.teardown()
    }

    @Test
    fun `returning to a session whose last message id is still held sends it again`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        val a = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(a, fakeResponse())
        simulateConnected("sess-A")
        factory.lastListener!!.onMessage(a, """{"type":"tool_call","id":"ev-a1","tool_id":"ta1","name":"bash"}""")

        // Away to B, which says nothing, and back to A.
        conn.switchSession(SessionState(sessionId = "sess-B"))
        runCurrent()
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        conn.switchSession(SessionState(sessionId = "sess-A"))
        runCurrent()
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())

        val connect = sentConnect()
        assertEquals("sess-A", connect["session_id"]?.jsonPrimitive?.content)
        assertEquals("ev-a1", connect["last_msg_id"]?.jsonPrimitive?.content)
        conn.teardown()
    }

    @Test
    fun `an acknowledgement of runtime input does not become the replay position`() = runTest {
        val conn = connectionOnVirtualClock()
        val logged = """{"type":"tool_call","id":"ev-1","tool_id":"t1","name":"bash"}"""
        // The Host mints this id for the queued input and sends the frame directly; it is not in its log.
        val ack = """{"type":"RUNTIME_INPUT_ACK","session_id":"sess-1","id":"host-made-up"}"""

        reconnectAfter(conn, listOf(logged, ack))

        assertEquals("ev-1", sentConnect()["last_msg_id"]?.jsonPrimitive?.content)
        conn.teardown()
    }

    @Test
    fun `the CONNECTED event carries the status and server_newer the Host sent`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """{"type":"CONNECTED","session_id":"sess-1","status":"running","server_newer":true}"""
        )

        val connected = seen.filterIsInstance<ConnectionEvent.Connected>().single()
        assertEquals(ServerSessionStatus.RUNNING, connected.status)
        assertTrue(connected.serverNewer)
        conn.teardown()
    }

    @Test
    fun `the socket a switch replaces cannot report its own close as a disconnect`() = runTest {
        // reopenSocket closes synchronously but opens from a launch, and the
        // generation the stale-callback guard compares against was only bumped
        // inside openWebSocket — so suppressing the old socket's onClosed was a
        // bet that the dispatch hop beat the peer's close round-trip. It does
        // not have to: a close answered inside this window surfaced as
        // Disconnected and collapsed the banner mid-switch.
        val conn = connectionOnVirtualClock()
        conn.setSession(SessionState(sessionId = "sess-A"))
        conn.connect("0xAGENT")
        val oldWs = factory.lastWebSocket!!
        val oldListener = factory.lastListener!!
        oldListener.onOpen(oldWs, fakeResponse())
        oldListener.onMessage(oldWs, """{"type":"CONNECTED","session_id":"sess-A"}""")

        val seen = recordEvents(conn)

        conn.switchSession(SessionState(sessionId = "sess-B"))
        // Deliberately before runCurrent(): the replacement is still queued,
        // which is exactly the window a real peer's close answer lands in.
        oldListener.onClosed(oldWs, 1000, "Switching conversation")
        advanceUntilIdle()

        assertEquals(
            "the replaced socket's own close surfaced as a Disconnected",
            emptyList<ConnectionEvent>(),
            seen.filterIsInstance<ConnectionEvent.Disconnected>()
        )
        conn.teardown()
    }

    // ── Offline agent on the relay path ──
    // The relay endpoint is shared, so the socket opens whether or not the
    // target agent is there and onOpen always fires. Zeroing the reconnect
    // counter there pinned the backoff at its 2s floor and made the attempt
    // cap unreachable — an unbounded retry storm against an offline agent.

    /** Current value of the private reconnect ladder position. */
    private fun currentReconnectAttempts(): Int =
        AgentConnection::class.java.getDeclaredField("reconnectAttempts")
            .apply { isAccessible = true }
            .getInt(connection)

    /**
     * One relay cycle against an offline agent: the shared socket opens, the
     * relay answers our CONNECT with ERROR, then drops us. Driven directly on
     * the live listener so no backoff has to be waited out.
     */
    private fun simulateOfflineCycle() {
        val ws = factory.lastWebSocket!!
        factory.lastListener?.onOpen(ws, fakeResponse())
        factory.lastListener?.onMessage(ws, """{"type":"ERROR","error":"Agent not connected: 0xAGENT"}""")
        factory.lastListener?.onFailure(ws, java.io.IOException("relay dropped us"), null)
    }

    @Test
    fun `each agent-offline cycle waits longer than the one before it`() {
        connection.connect("0xAGENT")

        val delays = (1..3).map {
            simulateOfflineCycle()
            AgentConnection.reconnectBackoffMs(currentReconnectAttempts())
        }
        connection.teardown()

        assertEquals(
            "every failed cycle must advance the ladder — a socket opening on the shared " +
                "relay endpoint says nothing about whether the agent is reachable",
            listOf(2000L, 4000L, 8000L),
            delays
        )
    }

    @Test
    fun `an offline agent's fast ladder stops at the attempt cap instead of climbing forever`() = runBlocking {
        // What follows the cap is a quiet try once a minute (W16AAPPLE-79,
        // covered below on the virtual clock), so no further Reconnecting.
        connection.connect("0xAGENT")
        val maxAttempts = AgentConnection.MAX_RECONNECT_ATTEMPTS

        val log = collectEvents()

        // Two cycles past the cap: whatever the ladder does, it must not
        // schedule a retry for either of them.
        repeat(maxAttempts + 2) { simulateOfflineCycle() }
        // Every emission above is synchronous on this thread; the marker is
        // only here to prove the collector drained them before we count, so a
        // retry scheduled past the cap cannot hide in a dispatch hop.
        log.awaitDrained()
        val attemptsAtEnd = currentReconnectAttempts()
        val scheduled = log.seen.count { it is ConnectionEvent.Reconnecting }
        connection.teardown()

        assertEquals("the ladder must stop climbing at the cap", maxAttempts, attemptsAtEnd)
        assertEquals(
            "one Reconnecting per scheduled retry and no more — past the cap the loop must end",
            maxAttempts,
            scheduled
        )
    }

    @Test
    fun `only a CONNECTED resets the ladder, and a later drop starts short again`() {
        connection.connect("0xAGENT")

        simulateOfflineCycle()
        simulateOfflineCycle()
        assertEquals("two failed cycles must leave the ladder at rung 2", 2, currentReconnectAttempts())

        factory.lastListener?.onOpen(factory.lastWebSocket!!, fakeResponse())
        assertEquals(
            "the socket opening is not proof we reached the agent",
            2,
            currentReconnectAttempts()
        )

        simulateConnected()
        assertEquals("reaching the agent must reset the ladder", 0, currentReconnectAttempts())

        // A genuine transient drop on the now-healthy connection retries fast.
        factory.lastListener?.onFailure(factory.lastWebSocket!!, java.io.IOException("wifi blip"), null)
        val delay = AgentConnection.reconnectBackoffMs(currentReconnectAttempts())
        connection.teardown()

        assertEquals("an unrelated later drop must start from the short delay again", 2000L, delay)
    }

    @Test
    fun `giving up on an unreachable agent reports the server's reason, not a bare disconnect`() = runBlocking {
        connection.connect("0xAGENT")
        val maxAttempts = AgentConnection.MAX_RECONNECT_ATTEMPTS

        val log = collectEvents()

        repeat(maxAttempts + 1) { simulateOfflineCycle() }
        // The marker rides in behind the ladder's own emissions and is then
        // filtered back out, so `last` really is the ladder's last word.
        log.awaitDrained()
        // The last thing said before the ladder went quiet is what the user is
        // left looking at. Still Reconnecting there means it never gave up.
        val terminal = log.seen.lastOrNull()
        connection.teardown()

        assertTrue(
            "the ladder's final word must name the relay's own reason, not leave the user " +
                "on Reconnecting (or on a bare Disconnected, which reads as a network drop); got $terminal",
            terminal is ConnectionEvent.ConnectionError &&
                terminal.message.contains("Agent not connected")
        )
    }

    // ── direct (/ws) vs relay (/ws/input) ─────────────────────────────
    //
    // Two ways to reach an agent. Through the relay, the socket is the relay's
    // shared /ws/input and every frame has to say which agent it is for; direct,
    // the socket *is* the agent's /ws and naming it is meaningless. A frame
    // routed the wrong way is not an error anyone sees — the relay just never
    // delivers it — so the endpoint and the `to` field are pinned here.
    //
    // OkHttp stores ws/wss schemes as http/https on the Request, so a dialled
    // `wss://host/ws` reads back as `https://host/ws`.

    private val agent = "0x" + "c5".repeat(32)

    /** A connection whose signer records the canonical payloads it was asked to sign. */
    private fun recordingConnection(
        relayUrl: String = AgentConnection.DEFAULT_RELAY,
        signed: MutableList<String>
    ) = AgentConnection(
        keyManager,
        relayUrl = relayUrl,
        webSocketFactory = factory,
        signer = { _, canonical -> signed += canonical; "fake-signature" }
    )

    private fun dialled(): String = factory.requests.last().url.toString()

    /** Opens the socket, which is what sends CONNECT, and returns that frame. */
    private fun openAndReadConnect(): JsonObject {
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        val frame = factory.lastWebSocket!!.sentMessages.single { it.contains("\"type\":\"CONNECT\"") }
        return Json.parseToJsonElement(frame).jsonObject
    }

    private fun sendAndReadInput(conn: AgentConnection, address: String): JsonObject {
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()
        conn.sendMessage("hello", address)
        return Json.parseToJsonElement(factory.lastWebSocket!!.sentMessages.single()).jsonObject
    }

    @Test
    fun `a relay connection dials the default relay's shared input socket over TLS`() {
        connection.connect(agent)

        assertEquals("https://oo.openonion.ai/ws/input", dialled())
    }

    @Test
    fun `a relay configured over plain http is dialled without TLS`() {
        val conn = AgentConnection(keyManager, relayUrl = "http://relay.test:8080", webSocketFactory = factory, signer = { _, _ -> "s" })
        conn.connect(agent)

        assertEquals("http://relay.test:8080/ws/input", dialled())
        conn.teardown()
    }

    @Test
    fun `a direct https agent is dialled at its own ws endpoint over TLS`() {
        connection.connect(agent, directUrl = "https://agent.example.com")

        assertEquals("https://agent.example.com/ws", dialled())
    }

    @Test
    fun `a direct http agent is dialled at its own ws endpoint without TLS`() {
        connection.connect(agent, directUrl = "http://192.168.1.20:8000")

        assertEquals("http://192.168.1.20:8000/ws", dialled())
    }

    @Test
    fun `a relay CONNECT names its agent in the signed payload and on the envelope`() {
        val signed = mutableListOf<String>()
        val conn = recordingConnection(signed = signed)
        conn.connect(agent)

        val connect = openAndReadConnect()
        val payload = connect["payload"]!!.jsonObject
        val timestamp = payload["timestamp"]!!.jsonPrimitive.content
        val nonce = payload["nonce"]!!.jsonPrimitive.content

        assertEquals(agent, payload["to"]?.jsonPrimitive?.content)
        assertEquals(agent, connect["to"]?.jsonPrimitive?.content)
        assertEquals(timestamp, connect["timestamp"]?.jsonPrimitive?.content)
        assertEquals(keyManager.load()!!.address, connect["from"]?.jsonPrimitive?.content)
        assertEquals(1, payload["signed_commands"]?.jsonPrimitive?.intOrNull)
        assertTrue("CONNECT needs a nonce", !payload["nonce"]?.jsonPrimitive?.content.isNullOrEmpty())
        assertEquals(
            "the relay verifies the signature over exactly these bytes",
            keyManager.canonicalJson(payload),
            signed.single()
        )
        conn.teardown()
    }

    @Test
    fun `a direct CONNECT names no agent anywhere, signed or not`() {
        val signed = mutableListOf<String>()
        val conn = recordingConnection(signed = signed)
        conn.connect(agent, directUrl = "https://agent.example.com")

        val connect = openAndReadConnect()
        val payload = connect["payload"]!!.jsonObject

        assertFalse("payload: $payload", payload.containsKey("to"))
        assertFalse("envelope: $connect", connect.containsKey("to"))
        assertEquals(1, payload["signed_commands"]?.jsonPrimitive?.intOrNull)
        assertTrue("CONNECT needs a nonce", !payload["nonce"]?.jsonPrimitive?.content.isNullOrEmpty())
        assertEquals(keyManager.canonicalJson(payload), signed.single())
        conn.teardown()
    }

    @Test
    fun `a relay INPUT is addressed to its agent`() {
        connection.connect(agent)

        val input = sendAndReadInput(connection, agent)

        assertEquals("INPUT", input["type"]?.jsonPrimitive?.content)
        assertEquals(agent, input["to"]?.jsonPrimitive?.content)
        assertEquals("hello", input["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a direct INPUT carries no address`() {
        connection.connect(agent, directUrl = "https://agent.example.com")

        val input = sendAndReadInput(connection, agent)

        assertFalse("input: $input", input.containsKey("to"))
    }

    @Test
    fun `switching between relay and direct on one connection re-decides the routing each time`() {
        connection.connect(agent)
        assertTrue(sendAndReadInput(connection, agent).containsKey("to"))

        connection.connect(agent, directUrl = "https://agent.example.com")
        assertEquals("https://agent.example.com/ws", dialled())
        assertFalse("direct after relay kept the relay's routing", sendAndReadInput(connection, agent).containsKey("to"))

        connection.connect(agent)
        assertEquals("https://oo.openonion.ai/ws/input", dialled())
        assertTrue("relay after direct kept the direct routing", sendAndReadInput(connection, agent).containsKey("to"))
    }

    // Known issues in direct-URL handling: openWebSocket strips only an
    // http(s) scheme and picks TLS by startsWith("https"), and appends "/ws"
    // to whatever is left. Both validators accept ws:// and wss:// server URLs.

    @Test
    fun `known issue - a direct wss URL is dialled at a host called wss`() {
        connection.connect(agent, directUrl = "wss://agent.example.com")

        knownIssue("a direct wss://host URL becomes ws://wss://host/ws, so the app dials the host \"wss\"") {
            assertEquals("https://agent.example.com/ws", dialled())
        }
    }

    @Test
    fun `known issue - a direct URL typed with a trailing slash dials a double-slash path`() {
        // Onboarding trims the slash; the Add/Edit Agent form does not.
        connection.connect(agent, directUrl = "https://agent.example.com/")

        knownIssue("a direct URL ending in / is dialled at //ws") {
            assertEquals("https://agent.example.com/ws", dialled())
        }
    }

    // ── the invite-code gate holds reconnects, on every way a socket ends ──
    //
    // While ONBOARD_REQUIRED is unanswered the next move is the user's; a
    // reconnect behind their back would open a new session and bury the gate
    // they are typing into. The ladder's first rung waits 2s, so these run on
    // the virtual clock and advance past every rung — asserting "still one
    // socket" immediately would pass whether or not anything was suppressed.

    private val gateFrame = """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""

    @Test
    fun `a socket that fails while the gate is open is never retried`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onMessage(factory.lastWebSocket!!, gateFrame)

        factory.lastListener!!.onFailure(factory.lastWebSocket!!, java.io.IOException("connection reset"), null)
        advanceTimeBy(10 * 60_000L)

        assertEquals("a reconnect was opened while the gate waited on the user", 1, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `a socket that closes abnormally while the gate is open is never retried`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onMessage(factory.lastWebSocket!!, gateFrame)

        factory.lastListener!!.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")
        advanceTimeBy(10 * 60_000L)

        assertEquals(1, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `the same failure with no gate open is retried - the control for the two above`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")

        factory.lastListener!!.onFailure(factory.lastWebSocket!!, java.io.IOException("connection reset"), null)
        advanceTimeBy(10 * 60_000L)

        assertTrue("expected the ladder to reopen, saw ${factory.socketCount} socket(s)", factory.socketCount >= 2)
        conn.teardown()
    }

    // ── W16AAPPLE-12: a refused OIP version fails once ────────────────
    //
    // The Host answers an unsupported descriptor with this ERROR and then
    // keeps reading the socket (ws_router/session.py), so whatever ends that
    // socket later must not start the ladder.

    private val versionRejection =
        """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,""" +
            """"protocol":{"name":"oip","version":"0.2","min_version":"0.2","max_version":"0.2"}}"""

    @Test
    fun `a refused OIP version closes the socket, reports once, and never reconnects`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        val listener = factory.lastListener!!
        listener.onOpen(ws, fakeResponse())

        listener.onMessage(ws, versionRejection)

        assertEquals("the client must close the socket the Host left open", 1000, ws.closeCode)
        assertEquals(listOf(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/0.2")), seen.filterIsInstance<ConnectionEvent.ProtocolRejected>())

        // Everything that would normally re-dial: the socket ending badly,
        // the network coming back (ChatViewModel), the app resuming.
        listener.onClosed(ws, 1006, "abnormal closure")
        listener.onFailure(ws, java.io.IOException("connection reset"), null)
        conn.retryNow()
        advanceTimeBy(10 * 60_000L)

        assertEquals("no automatic reconnect after the rejection", 1, factory.socketCount)
        assertTrue(
            "nothing may follow that reads as a retry or replaces the reason: $seen",
            seen.none { it is ConnectionEvent.Reconnecting || it is ConnectionEvent.Disconnected || it is ConnectionEvent.ConnectionError }
        )
        assertFalse(conn.isConnected())
        conn.teardown()
    }

    @Test
    fun `a refusal on a reconnect rung ends the ladder there`() = runTest {
        // A Host upgraded while the app was connected: the socket drops, and
        // the ladder's own CONNECT is the one refused.
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-1")
        factory.lastListener!!.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")
        advanceTimeBy(AgentConnection.reconnectBackoffMs(1) + 1)
        assertEquals("test setup: rung #1 must have opened a socket", 2, factory.socketCount)

        val rung = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(rung, fakeResponse())
        factory.lastListener!!.onMessage(rung, versionRejection)
        factory.lastListener!!.onFailure(rung, java.io.IOException("connection reset"), null)
        advanceTimeBy(10 * 60_000L)

        assertEquals(2, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `a refusal of the CONNECT re-sent after onboarding is final too`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onMessage(ws, """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}""")
        factory.lastListener!!.onMessage(ws, """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"ok"}""")

        factory.lastListener!!.onMessage(ws, versionRejection)
        advanceTimeBy(10 * 60_000L)

        assertEquals(1000, ws.closeCode)
        assertEquals(1, factory.socketCount)
        assertTrue(
            "the onboarding path must not dress the refusal up as a rejected invite code",
            seen.none { it is ConnectionEvent.ChatItemReceived && it.item is ChatItem.OnboardingFailed }
        )
        conn.teardown()
    }

    // -- W16AAPPLE-73: a CONNECTED naming another OIP version fails the same way --

    private val connectedOnAnotherVersion =
        """{"type":"CONNECTED","session_id":"sess-1",""" +
            """"protocol":{"name":"oip","version":"0.2","min_version":"0.2","max_version":"0.2","websocket_path":"/ws"}}"""

    @Test
    fun `a CONNECTED naming another OIP version closes the socket, reports once, and never reconnects`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        val listener = factory.lastListener!!
        listener.onOpen(ws, fakeResponse())

        listener.onMessage(ws, connectedOnAnotherVersion)

        assertEquals("the client must close the socket the Host would keep open", 1000, ws.closeCode)
        assertEquals(listOf(ConnectionEvent.ProtocolRejected(hostProtocol = "oip/0.2")), seen.filterIsInstance<ConnectionEvent.ProtocolRejected>())
        assertTrue("it must never read as connected: $seen", seen.none { it is ConnectionEvent.Connected })
        assertFalse(conn.isConnected())

        listener.onClosed(ws, 1006, "abnormal closure")
        conn.retryNow()
        advanceTimeBy(10 * 60_000L)

        assertEquals("no automatic reconnect after the rejection", 1, factory.socketCount)
        assertTrue(
            "nothing may follow that reads as a retry or replaces the reason: $seen",
            seen.none { it is ConnectionEvent.Reconnecting || it is ConnectionEvent.Disconnected || it is ConnectionEvent.ConnectionError }
        )
        conn.teardown()
    }

    @Test
    fun `messages queued while connecting are not sent to a Host on another OIP version`() {
        // It would misread them, and the user could not tell what it did with them.
        connection.connect("0xAGENT")
        connection.sendMessage("queued while connecting", "0xAGENT")

        factory.lastListener!!.onMessage(factory.lastWebSocket!!, connectedOnAnotherVersion)

        assertTrue(
            "a queued message reached an incompatible Host: ${factory.lastWebSocket!!.sentMessages}",
            factory.lastWebSocket!!.sentMessages.none { it.contains("queued while connecting") }
        )
    }

    /**
     * Forwards to [inner], but first runs [onNextDispatch] once from inside
     * `dispatch` — i.e. inside `launch`, before it has returned the Job its
     * caller is about to store. A frame on OkHttp's thread can land there.
     */
    private class InterposingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var onNextDispatch: (() -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            onNextDispatch?.also { onNextDispatch = null }?.invoke()
            inner.dispatch(context, block)
        }
    }

    @Test
    fun `a refusal landing while retryNow is launching its reopen does not get a second CONNECT`() = runTest {
        // The app resumes (retryNow, main thread) just as the rung's CONNECT
        // is refused (OkHttp thread): retryNow has passed its guard, and the
        // refusal's cancel of reconnectJob still sees the previous job.
        val dispatcher = InterposingDispatcher(StandardTestDispatcher(testScheduler))
        val conn = AgentConnection(
            keyManager,
            webSocketFactory = factory,
            signer = { _, _ -> "fake-signature" },
            reconnectDispatcher = dispatcher
        )
        conn.connect("0xAGENT")
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-1")
        factory.lastListener!!.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")
        conn.retryNow()
        runCurrent()
        assertEquals("test setup: retryNow must have opened the rung", 2, factory.socketCount)
        val rung = factory.lastWebSocket!!
        val rungListener = factory.lastListener!!
        rungListener.onOpen(rung, fakeResponse())

        dispatcher.onNextDispatch = { rungListener.onMessage(rung, versionRejection) }
        conn.retryNow()
        runCurrent()

        assertEquals(1000, rung.closeCode)
        assertEquals("no CONNECT may follow the refusal", 2, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `a refusal landing while a conversation switch reopens does not get a second CONNECT`() = runTest {
        // switchSession runs on the repository's IO thread and checks for a
        // live socket before reopening; the refusal can land on OkHttp's
        // thread in between. Modelled by delivering it from switchSession's
        // own log line, which Robolectric writes synchronously.
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        val listener = factory.lastListener!!
        listener.onOpen(ws, fakeResponse())

        var delivered = false
        val logged = StringBuilder()
        val previousStream = ShadowLog.stream
        ShadowLog.stream = PrintStream(object : java.io.OutputStream() {
            override fun write(b: Int) {
                logged.append(b.toChar())
                if (!delivered && logged.contains("Switching session")) {
                    delivered = true
                    listener.onMessage(ws, versionRejection)
                }
            }
        })
        try {
            conn.switchSession(SessionState(sessionId = "sess-2"))
        } finally {
            ShadowLog.stream = previousStream
        }
        advanceTimeBy(10 * 60_000L)

        assertTrue("test setup: the refusal must land inside switchSession", delivered)
        assertEquals("no CONNECT may follow the refusal", 1, factory.socketCount)
        assertTrue(
            "nothing may replace the refusal as the last word: $seen",
            seen.last() is ConnectionEvent.ProtocolRejected
        )
        conn.teardown()
    }

    @Test
    fun `the user connecting again after a refusal gets a fresh attempt`() = runTest {
        // "No automatic reconnect" — a deliberate retry is still the user's to make.
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onMessage(factory.lastWebSocket!!, versionRejection)

        conn.connect("0xAGENT")
        runCurrent()

        assertEquals(2, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `a refusal does not outlive the cycle it ended - the next connect gets its ladder back`() = runTest {
        // The stand-down is per cycle. connect() opens a socket whether or not
        // it clears the flag, so the test above cannot tell; if it stayed set,
        // the user's retry would connect once and never recover from a drop.
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onMessage(factory.lastWebSocket!!, versionRejection)
        conn.connect("0xAGENT")
        runCurrent()
        assertEquals("test setup: the retry opened a socket", 2, factory.socketCount)

        factory.lastListener!!.onFailure(factory.lastWebSocket!!, java.io.IOException("connection reset"), null)
        advanceTimeBy(AgentConnection.reconnectBackoffMs(1) + 1)

        assertEquals("the new cycle's ladder must run", 3, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `executeTool does not send when connection is not ready`() = runTest {
        connection.connect("0xAGENT")

        val outcome = connection.executeTool(
            execId = "exec-1",
            tool = "shell",
            args = Json.parseToJsonElement("""{"command":"pwd"}""").jsonObject,
            recipient = "0xAGENT"
        )

        assertEquals(
            ExecOutcome.NotSent("The agent connection is not ready"),
            outcome
        )
        assertTrue(factory.lastWebSocket!!.sentMessages.isEmpty())
    }

    @Test
    fun `executeTool sends signed EXEC and returns matching success`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        val pendingOutcome = async {
            connection.executeTool(
                execId = "exec-success",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"pwd"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        val frame = Json.parseToJsonElement(
            factory.lastWebSocket!!.sentMessages.single()
        ).jsonObject
        val payload = frame.getValue("payload").jsonObject

        assertEquals(
            json("""{"type":"EXEC","exec_id":"exec-success","tool":"shell","args":{"command":"pwd"}}"""),
            signedCommand(factory.lastWebSocket!!.sentMessages.single())
        )
        assertEquals("EXEC", frame.getValue("type").jsonPrimitive.content)
        assertEquals("exec-success", frame.getValue("exec_id").jsonPrimitive.content)
        assertEquals("shell", frame.getValue("tool").jsonPrimitive.content)
        assertEquals("pwd", frame.getValue("args").jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("0xAGENT", frame.getValue("to").jsonPrimitive.content)
        assertEquals("exec-success", payload.getValue("exec_id").jsonPrimitive.content)
        assertTrue("EXEC needs a nonce", !payload.getValue("nonce").jsonPrimitive.content.isNullOrEmpty())
        assertEquals("fake-signature", frame.getValue("signature").jsonPrimitive.content)

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """
            {
              "type": "EXEC_RESULT",
              "exec_id": "exec-success",
              "status": "success",
              "result": "/workspace",
              "duration_ms": 12
            }
            """.trimIndent()
        )

        assertEquals(
            ExecOutcome.Success(result = "/workspace", durationMs = 12.0),
            pendingOutcome.await()
        )
    }

    @Test
    fun `executeTool returns matching error`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        val pendingOutcome = async {
            connection.executeTool(
                execId = "exec-error",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"missing-command"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """
            {
              "type": "EXEC_RESULT",
              "exec_id": "exec-error",
              "status": "error",
              "error": "Command not found",
              "duration_ms": 4
            }
            """.trimIndent()
        )

        assertEquals(
            ExecOutcome.Error(message = "Command not found", durationMs = 4.0),
            pendingOutcome.await()
        )
    }

    @Test
    fun `pending EXEC is settled when the connection is torn down`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()

        val pendingOutcome = async {
            connection.executeTool(
                execId = "exec-closed",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"pwd"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        connection.teardown()

        assertEquals(
            ExecOutcome.Unknown("The agent connection was closed"),
            withTimeoutOrNull(1_000) { pendingOutcome.await() }
        )
    }

    @Test
    fun `provider station refusal completes pending EXEC as an error`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()
        val events = collectEvents()

        val pendingOutcome = async {
            connection.executeTool(
                execId = "exec-work-room",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"pwd"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","code":"provider_station_only","message":"Use the Claude Work Room"}"""
        )

        assertEquals(
            ExecOutcome.Error("Use the Claude Work Room"),
            withTimeoutOrNull(1_000) { pendingOutcome.await() }
        )
        // The collector runs on a real IO thread, so its wait must use real
        // time: on runTest's virtual clock the timeout fired as soon as this
        // thread went idle, before the collector had caught up.
        withContext(Dispatchers.Default) { events.awaitDrained() }
        assertTrue(events.seen.none { it is ConnectionEvent.ConnectionError })
    }

    @Test
    fun `authentication refusal completes pending EXEC as an error`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()
        val events = collectEvents()

        val pendingOutcome = async {
            connection.executeTool(
                execId = "exec-auth",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"pwd"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","message":"authenticate first"}"""
        )

        assertEquals(
            ExecOutcome.Error("authenticate first"),
            withTimeoutOrNull(1_000) { pendingOutcome.await() }
        )
        // Unlike the Work Room refusal, this one refuses every command on the
        // socket, so the chat still reports it: a message refused alongside the
        // EXEC must not go unexplained.
        withContext(Dispatchers.Default) { events.awaitDrained() }
        assertTrue(
            "the chat error must still be reported: ${events.seen}",
            events.seen.any { it is ConnectionEvent.ConnectionError && it.message == "authenticate first" }
        )
    }

    @Test
    fun `EXEC results are matched when commands finish out of order`() = runTest {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        val first = async {
            connection.executeTool(
                execId = "exec-1",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"first"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        val second = async {
            connection.executeTool(
                execId = "exec-2",
                tool = "shell",
                args = Json.parseToJsonElement("""{"command":"second"}""").jsonObject,
                recipient = "0xAGENT"
            )
        }
        runCurrent()

        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """{"type":"EXEC_RESULT","exec_id":"exec-2","status":"success","result":"second result"}"""
        )
        factory.lastListener!!.onMessage(
            factory.lastWebSocket!!,
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"success","result":"first result"}"""
        )

        assertEquals(ExecOutcome.Success("first result"), first.await())
        assertEquals(ExecOutcome.Success("second result"), second.await())
    }

    // ── W16AAPPLE-90: signing with the server's time ───────────────────

    /** A 101 whose `Date` reads [serverMillis], as the Host (uvicorn) and the relay both send. */
    private fun upgradeWithDate(serverMillis: Long): Response =
        fakeResponse().newBuilder()
            .header("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(serverMillis).atOffset(ZoneOffset.UTC)))
            .build()

    /** `payload.timestamp` of a signed frame: CONNECT, a v2 command, or ONBOARD_SUBMIT. */
    private fun signedTimestamp(frame: String): Long =
        json(frame).jsonObject["payload"]?.jsonObject?.get("timestamp")?.jsonPrimitive?.longOrNull
            ?: error("no payload.timestamp: $frame")

    /** The last frame of [type] this socket sent. */
    private fun FakeWebSocket.lastSent(type: String): String =
        sentMessages.lastOrNull { json(it).jsonObject["type"]?.jsonPrimitive?.content == type }
            ?: error("no $type was sent: $sentMessages")

    /** Within a few seconds: `Date` has whole seconds, and the test's own clock moves while it runs. */
    private fun assertAbout(what: String, expectedSeconds: Long, actualSeconds: Long) {
        assertTrue(
            "$what: expected about $expectedSeconds, got $actualSeconds",
            kotlin.math.abs(actualSeconds - expectedSeconds) <= 5
        )
    }

    private val tenMinutesMs = 10 * 60_000L

    /**
     * Start the process-wide CONNECT guard afresh. Earlier tests' bursts of
     * CONNECTs can leave it seconds ahead of the clock, which is correct but
     * would blur a test of what the clock says. A reading of 0 is a clock gone
     * back, which the guard follows; see AgentConnection.nextConnectTimestamp.
     */
    private fun freshConnectGuard() {
        AgentConnection.nextConnectTimestamp(0)
    }

    @Test
    fun `a device clock ten minutes slow signs CONNECT and commands with the server's time`() {
        val serverNow = System.currentTimeMillis() + tenMinutesMs
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!

        factory.lastListener!!.onOpen(ws, upgradeWithDate(serverNow))
        simulateConnected()
        connection.respond("yes")
        connection.sendMessage("hello", "0xAGENT")

        assertAbout("CONNECT", serverNow / 1000, signedTimestamp(ws.lastSent("CONNECT")))
        assertAbout("ASK_USER_RESPONSE", serverNow / 1000, signedTimestamp(ws.lastSent("ASK_USER_RESPONSE")))
        assertAbout("INPUT", serverNow / 1000, signedTimestamp(ws.lastSent("INPUT")))
    }

    @Test
    fun `an invite code is signed with the server's time too`() {
        val serverNow = System.currentTimeMillis() + tenMinutesMs
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, upgradeWithDate(serverNow))
        factory.lastListener!!.onMessage(ws, """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}""")

        connection.respondToOnboard("invite_code", inviteCode = "CODE-1")

        assertAbout("ONBOARD_SUBMIT", serverNow / 1000, signedTimestamp(ws.lastSent("ONBOARD_SUBMIT")))
    }

    @Test
    fun `with no Date header the device clock signs, as before`() {
        freshConnectGuard()
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!

        factory.lastListener!!.onOpen(ws, fakeResponse())
        simulateConnected()
        connection.respond("yes")

        val deviceNow = System.currentTimeMillis() / 1000
        assertAbout("CONNECT", deviceNow, signedTimestamp(ws.lastSent("CONNECT")))
        assertAbout("ASK_USER_RESPONSE", deviceNow, signedTimestamp(ws.lastSent("ASK_USER_RESPONSE")))
    }

    @Test
    fun `a Date past the 30-minute bound is ignored`() {
        freshConnectGuard()
        connection.connect("0xAGENT")
        val ws = factory.lastWebSocket!!

        factory.lastListener!!.onOpen(ws, upgradeWithDate(System.currentTimeMillis() + 2 * 60 * 60_000L))

        assertAbout("CONNECT", System.currentTimeMillis() / 1000, signedTimestamp(ws.lastSent("CONNECT")))
    }

    @Test
    fun `each new socket takes its own reading`() = runBlocking {
        connection.connect("0xAGENT")
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, upgradeWithDate(System.currentTimeMillis() + tenMinutesMs))
        simulateConnected("sess-1")

        connection.switchSession(SessionState(sessionId = "sess-2"))
        awaitSockets(2)
        val second = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(second, fakeResponse())

        assertAbout(
            "the second socket's CONNECT, whose upgrade had no Date",
            System.currentTimeMillis() / 1000,
            signedTimestamp(second.lastSent("CONNECT"))
        )
    }

    @Test
    fun `a burst of CONNECTs never shares a second, but a clock put right is followed`() {
        val base = 1_000_000L
        val burst = List(3) { AgentConnection.nextConnectTimestamp(base) }
        assertEquals("a burst is spread over distinct seconds", listOf(base, base + 1, base + 2), burst)

        // A long burst runs well ahead of the clock and still never goes back.
        val longBurst = List(20) { AgentConnection.nextConnectTimestamp(base) }
        assertEquals((base + 3..base + 22).toList(), longBurst)

        // Signed 40 minutes ahead (a fast clock, past the bound), then the user
        // fixes the clock: the old second must not hold every later CONNECT
        // in the future, where the Host keeps refusing them.
        AgentConnection.nextConnectTimestamp(base + 40 * 60)
        assertEquals(base, AgentConnection.nextConnectTimestamp(base))
    }

    // ── W16AAPPLE-82: a reconnect the Host refuses or ignores ──────────

    /** Connect on [conn], reach the agent, then lose the socket so the ladder opens rung #1. */
    private fun TestScope.dropAfterConnected() {
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-1")
        factory.lastListener!!.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")
        advanceTimeBy(AgentConnection.reconnectBackoffMs(1) + 1)
        assertEquals("test setup: rung #1 must have opened a socket", 2, factory.socketCount)
    }

    @Test
    fun `a reconnect whose CONNECT is answered with ERROR ends there and says why`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        dropAfterConnected()
        val rung = factory.lastWebSocket!!

        factory.lastListener!!.onOpen(rung, fakeResponse())
        factory.lastListener!!.onMessage(rung, """{"type":"ERROR","message":"unauthorized: signature expired"}""")
        // The Host keeps the socket open after an ERROR, so nothing else arrives.
        advanceTimeBy(10 * 60_000L)

        assertEquals(ConnectionEvent.ReconnectRefused("unauthorized: signature expired"), seen.last())
        assertEquals("the refused socket must be closed, not left open", 1000, rung.closeCode)
        assertEquals("no further rung after a refusal", 2, factory.socketCount)
        assertFalse(conn.isConnected())
        conn.teardown()
    }

    @Test
    fun `a reconnect the Host never answers is given up on and the ladder climbs`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        dropAfterConnected()
        val rung = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(rung, fakeResponse())

        advanceTimeBy(AgentConnection.CONNECTED_TIMEOUT_MS - 1)
        assertNull("still within the wait", rung.closeCode)
        advanceTimeBy(2)
        assertEquals("the unanswered socket must be closed", 1000, rung.closeCode)

        advanceTimeBy(AgentConnection.reconnectBackoffMs(2) + 1)
        assertEquals("the next rung must open", 3, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `a reconnect answered in time keeps its socket`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        dropAfterConnected()
        val rung = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(rung, fakeResponse())

        simulateConnected("sess-1")
        advanceTimeBy(10 * AgentConnection.CONNECTED_TIMEOUT_MS)

        assertNull(rung.closeCode)
        assertTrue(conn.isConnected())
        conn.teardown()
    }

    @Test
    fun `the relay's agent-offline answer to a reconnect is a failed rung, not a refusal`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        dropAfterConnected()
        val rung = factory.lastWebSocket!!

        factory.lastListener!!.onOpen(rung, fakeResponse())
        factory.lastListener!!.onMessage(rung, """{"type":"ERROR","error":"Agent not connected: 0xAGENT"}""")
        runCurrent()

        assertTrue("not a refusal: $seen", seen.none { it is ConnectionEvent.ReconnectRefused })
        assertTrue("nor a chat error on every rung: $seen", seen.none { it is ConnectionEvent.ConnectionError })
        advanceTimeBy(AgentConnection.reconnectBackoffMs(2) + 1)
        assertEquals("the ladder carries on", 3, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `the CONNECT re-sent after onboarding on the first socket is not treated as a reconnect`() = runTest {
        // W16AAPPLE-83's sequence: the Host finishes the original CONNECT
        // itself, then refuses the app's re-CONNECT. That ERROR is noise on a
        // working connection, not a refused reconnect.
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, fakeResponse())
        factory.lastListener!!.onMessage(ws, """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}""")
        factory.lastListener!!.onMessage(ws, """{"type":"ONBOARD_SUCCESS","id":"ob1","level":"contact","message":"ok"}""")
        simulateConnected("sess-new")

        factory.lastListener!!.onMessage(ws, """{"type":"ERROR","message":"already authenticated: open a new connection"}""")
        advanceTimeBy(10 * 60_000L)

        assertNull(ws.closeCode)
        assertTrue(conn.isConnected())
        assertTrue(seen.none { it is ConnectionEvent.ReconnectRefused })
        conn.teardown()
    }

    // ── W16AAPPLE-79: waiting for an offline agent to come back ─────────

    /** One try against an offline agent: the relay answers CONNECT with its verdict and drops us. */
    private fun relayAnswersAgentOffline() {
        val ws = factory.lastWebSocket!!
        factory.lastListener!!.onOpen(ws, fakeResponse())
        factory.lastListener!!.onMessage(ws, """{"type":"ERROR","error":"Agent not connected: 0xAGENT"}""")
        factory.lastListener!!.onFailure(ws, java.io.IOException("relay dropped us"), null)
    }

    /** Run every rung of the ladder against an offline agent, each socket opened on the virtual clock. */
    private fun TestScope.runLadderAgainstOfflineAgent() {
        for (attempt in 1..AgentConnection.MAX_RECONNECT_ATTEMPTS) {
            advanceTimeBy(AgentConnection.reconnectBackoffMs(attempt) + 1)
            relayAnswersAgentOffline()
        }
    }

    @Test
    fun `once the ladder runs out against an offline agent, it tries every minute until the agent is back`() = runTest {
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        relayAnswersAgentOffline()
        runLadderAgainstOfflineAgent()
        val afterLadder = factory.socketCount
        assertEquals("test setup: the first connect plus every rung", AgentConnection.MAX_RECONNECT_ATTEMPTS + 1, afterLadder)

        advanceTimeBy(AgentConnection.OFFLINE_POLL_INTERVAL_MS - 1)
        assertEquals("not before the minute is up", afterLadder, factory.socketCount)
        advanceTimeBy(2)
        assertEquals("a try once the minute is up", afterLadder + 1, factory.socketCount)

        relayAnswersAgentOffline()
        advanceTimeBy(AgentConnection.OFFLINE_POLL_INTERVAL_MS + 1)
        assertEquals("and another a minute later", afterLadder + 2, factory.socketCount)

        // The agent is back.
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-1")
        advanceTimeBy(10 * AgentConnection.OFFLINE_POLL_INTERVAL_MS)

        assertTrue(conn.isConnected())
        assertEquals("no more tries once connected", afterLadder + 2, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `an agent that goes offline mid-session is reported once, then waited for`() = runTest {
        val conn = connectionOnVirtualClock()
        val seen = recordEvents(conn)
        conn.connect("0xAGENT")
        factory.lastListener!!.onOpen(factory.lastWebSocket!!, fakeResponse())
        simulateConnected("sess-1")
        // The Host restarts: the socket drops and the relay no longer knows the agent.
        factory.lastListener!!.onClosed(factory.lastWebSocket!!, 1006, "abnormal closure")
        runLadderAgainstOfflineAgent()

        repeat(3) {
            advanceTimeBy(AgentConnection.OFFLINE_POLL_INTERVAL_MS + 1)
            relayAnswersAgentOffline()
        }

        assertEquals(
            "the verdict, once, in the relay's words: $seen",
            listOf(ConnectionEvent.ReconnectRefused("Agent not connected: 0xAGENT")),
            seen.filterIsInstance<ConnectionEvent.ReconnectRefused>()
        )
        assertTrue("never as a chat error: $seen", seen.none { it is ConnectionEvent.ConnectionError })
        assertTrue(
            "the minute's tries are quiet: $seen",
            seen.count { it is ConnectionEvent.Reconnecting } == AgentConnection.MAX_RECONNECT_ATTEMPTS
        )
        conn.teardown()
    }

    @Test
    fun `in the background the minute's try waits, and coming back tries at once`() = runTest {
        var visible = true
        val conn = connectionOnVirtualClock(isAppVisible = { visible })
        conn.connect("0xAGENT")
        relayAnswersAgentOffline()
        runLadderAgainstOfflineAgent()
        val afterLadder = factory.socketCount

        visible = false
        advanceTimeBy(5 * AgentConnection.OFFLINE_POLL_INTERVAL_MS)
        assertEquals("nothing while the app is not on screen", afterLadder, factory.socketCount)

        visible = true
        conn.retryNow()
        runCurrent()
        assertEquals("returning to the app tries straight away", afterLadder + 1, factory.socketCount)
        conn.teardown()
    }

    @Test
    fun `an agent that is merely unreachable still stops at the cap`() = runTest {
        // Only the relay's "agent offline" verdict earns the slow retry; a
        // plain network failure ends where it always did.
        val conn = connectionOnVirtualClock()
        conn.connect("0xAGENT")
        factory.lastListener!!.onFailure(factory.lastWebSocket!!, java.io.IOException("no route"), null)
        for (attempt in 1..AgentConnection.MAX_RECONNECT_ATTEMPTS) {
            advanceTimeBy(AgentConnection.reconnectBackoffMs(attempt) + 1)
            factory.lastListener!!.onFailure(factory.lastWebSocket!!, java.io.IOException("no route"), null)
        }
        val afterLadder = factory.socketCount

        advanceTimeBy(10 * AgentConnection.OFFLINE_POLL_INTERVAL_MS)

        assertEquals(afterLadder, factory.socketCount)
        conn.teardown()
    }

    private companion object {
        /** Deadlock guard for [awaitOn]; never reached while the code is correct. */
        const val AWAIT_TIMEOUT_MS = 15_000L

        /** Name on the AGENT_PROFILE frame [EventLog.awaitDrained] uses as its ordering barrier. */
        const val MARKER = "drain-marker"
    }
}
