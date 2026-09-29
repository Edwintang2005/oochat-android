package ai.openonion.oochat.network

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.ChatItem
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [AgentConnection] against a fake [WebSocketFactory] (the
 * seam it already exposes for testing) plus a real, Robolectric-backed
 * [KeyManager] pre-seeded via [KeyManager.keysFromHex] (pure byte-slicing,
 * no native call) so [AgentConnection.connect] can reach [KeyManager.loadOrGenerate]'s
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

        override fun create(request: Request, listener: WebSocketListener): WebSocket {
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
        // keysFromHex is pure byte-slicing (no native libsodium call) — see
        // class doc — so loadOrGenerate() below takes the existing-key
        // path instead of falling through to the blocked generate() path.
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
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
            """{"type":"PLAN_REVIEW_RESPONSE","message":"Plan approved. Implement now. Do NOT re-enter plan mode."}""",
            sent
        )
    }

    @Test
    fun `respondToUlwTurnsReached sends continue with turns and no mode`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToUlwTurnsReached(action = "continue", turns = 100)

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertEquals("""{"type":"ULW_RESPONSE","action":"continue","turns":100}""", sent)
    }

    @Test
    fun `respondToUlwTurnsReached sends switch_mode with mode and no turns`() {
        connection.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        connection.respondToUlwTurnsReached(action = "switch_mode", mode = "safe")

        val sent = factory.lastWebSocket!!.sentMessages.single()
        assertEquals("""{"type":"ULW_RESPONSE","action":"switch_mode","mode":"safe"}""", sent)
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
            assertEquals(frame, factory.lastWebSocket!!.sentMessages.single())
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
            """{"type":"mode_change","mode":"ulw","turns":25}""",
            factory.lastWebSocket!!.sentMessages.single()
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
    private fun TestScope.connectionOnVirtualClock() = AgentConnection(
        keyManager,
        webSocketFactory = factory,
        signer = { _, _ -> "fake-signature" },
        reconnectDispatcher = StandardTestDispatcher(testScheduler)
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
    fun `an offline agent stops being retried at the attempt cap instead of forever`() = runBlocking {
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

    private companion object {
        /** Deadlock guard for [awaitOn]; never reached while the code is correct. */
        const val AWAIT_TIMEOUT_MS = 15_000L

        /** Name on the AGENT_PROFILE frame [EventLog.awaitDrained] uses as its ordering barrier. */
        const val MARKER = "drain-marker"
    }
}
