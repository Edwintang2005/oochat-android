package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ConnectionRepositoryImpl]'s [ConnectionRepositoryImpl.connect]
 * and onward — the paths [ConnectionRepositoryImplObserveEventsTest] couldn't
 * reach yet ("future tests will assert on concrete ChatEvent values once an
 * AgentConnection mock seam lands", per that file's own comment).
 *
 * The seam: [ConnectionRepositoryImpl]'s `agentConnectionFactory` constructor
 * param builds a *real* [AgentConnection] wired to a fake [WebSocketFactory]
 * (same technique as [ai.openonion.oochat.network.AgentConnectionRobolectricTest]),
 * rather than substituting a different fake type — AgentConnection is a
 * concrete class with no interface, so this is what makes it substitutable
 * without a larger interface-extraction refactor.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRepositoryImplTest {

    private class FakeWebSocket : WebSocket {
        val sentMessages = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sentMessages += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class FakeWebSocketFactory : WebSocketFactory {
        var lastListener: WebSocketListener? = null
        var lastWebSocket: FakeWebSocket? = null
        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            lastListener = listener
            return FakeWebSocket().also { lastWebSocket = it }
        }
    }

    private lateinit var keyManager: KeyManager
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var repository: ConnectionRepositoryImpl
    private val sink = RecordingSink()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()

        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url ->
                AgentConnection(km, url, factory, pendingMessageSink = sink)
            }
        )
    }

    /** Lets a test assert whether the outbox was touched. */
    private class RecordingSink : PendingMessageSink {
        var clearCount = 0
        override fun enqueue(
            agentAddress: String,
            sessionId: String?,
            prompt: String,
            images: List<String>?,
            files: List<FileAttachment>?
        ) = Unit
        override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) = Unit
        override fun clear(agentAddress: String) { clearCount++ }
    }

    private fun simulateConnected(sessionId: String = "sess-1") {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"CONNECTED","session_id":"$sessionId"}""")
    }

    private fun simulateError(message: String) {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, """{"type":"ERROR","message":"$message"}""")
    }

    private fun simulateAgentProfile(model: String = "gemini-2.5-pro") {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"AGENT_PROFILE","name":"research-assistant","model":"$model","tools":["bash"],
              |"skills":[{"name":"web-scraping","description":"Read a web page and extract structured text from it"}]}"""
                .trimMargin()
        )
    }

    private fun simulateDashboard(html: String = "<h1>Home</h1>") {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"DASHBOARD_SNAPSHOT","html":"$html"}"""
        )
    }

    /**
     * startCollectingEvents() forwards AgentConnection.events on
     * ConnectionRepositoryImpl's own `scope` (real SupervisorJob() +
     * Dispatchers.IO — not the test dispatcher), so a synchronous
     * simulate*() call above races the collector actually subscribing to
     * `conn.events` on that real background thread. `conn.events` is a
     * `replay = 0` SharedFlow: an emission that lands before the collector
     * subscribes is lost forever, not just delayed — a fixed delay only
     * narrows that window, it doesn't close it. `retry`, if given, is
     * re-invoked on every poll tick so the triggering event keeps getting
     * re-emitted until some emission is guaranteed to land after
     * subscription, or the timeout gives up.
     */
    private fun awaitUntil(timeoutMs: Long = 5000L, retry: () -> Unit = {}, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            retry()
            Thread.sleep(20)
        }
        // Returning quietly at the deadline left the three "value is still at
        // its default" tests below green on a connection that never happened.
        if (!condition()) fail("timed out after ${timeoutMs}ms waiting for the awaited condition")
    }

    @Test
    fun `connect sets state to Connecting immediately`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        assertEquals(ConnectionState.Connecting, repository.connectionState.value)
    }

    @Test
    fun `a CONNECTED frame from the underlying AgentConnection updates connectionState to Connected`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        assertTrue(repository.connectionState.value is ConnectionState.Connected)
        assertTrue(repository.isConnected())
    }

    @Test
    fun `an OnboardRequired ChatItemReceived is forwarded through observeEvents`() = runBlocking {
        // Uses real dispatchers (not runTest's virtual scheduler) because the event has to cross two real background thread-hops that virtual time can't reliably pump.
        repository.connect("0xAGENT")

        val received = withTimeout(3000L) {
            val deferred = async {
                repository.observeEvents().first { it is ChatEvent.ChatItemReceived }
            }
            // Retry until the collector subscribes (same subscribe-before-emit race as awaitUntil, explained above) — safe because ONBOARD_REQUIRED handling is idempotent.
            while (deferred.isActive) {
                factory.lastListener?.onMessage(
                    factory.lastWebSocket!!,
                    """{"type":"ONBOARD_REQUIRED","id":"ob1","methods":["invite_code"]}"""
                )
                delay(20)
            }
            deferred.await()
        } as ChatEvent.ChatItemReceived

        assertTrue(received.item is ChatItem.OnboardRequired)
    }

    @Test
    fun `sendMessage forwards to the underlying AgentConnection once connected`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()

        repository.sendMessage("hello", "0xAGENT")

        assertTrue(factory.lastWebSocket!!.sentMessages.any { it.contains("hello") })
    }

    @Test
    fun `interrupt sends an INTERRUPT frame on the underlying AgentConnection`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()

        repository.interrupt()

        assertTrue(factory.lastWebSocket!!.sentMessages.any { it.contains("INTERRUPT") })
    }

    @Test
    fun `disconnect resets connectionState to Disconnected`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()

        repository.disconnect()

        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        assertTrue(!repository.isConnected())
    }

    @Test
    fun `isConnected is false before connect is called`() {
        assertTrue(!repository.isConnected())
    }



    @Test
    fun `two concurrent connect calls do not corrupt state`() = runBlocking {
        // Regression test for the connectMutex added around the
        // teardown-then-rebuild section of connect() — without it, a
        // double-tapped Reconnect banner (two near-simultaneous connect()
        // calls on the same repository instance) could interleave reads/
        // writes of `connection`/`eventCollectionJob`. Real dispatchers
        // (runBlocking, not runTest) so the two calls' Dispatchers.IO hops
        // can genuinely race rather than being serialized by a virtual-time
        // scheduler.
        val first = async { repository.connect("0xAGENT") }
        val second = async { repository.connect("0xAGENT") }

        awaitAll(first, second)

        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        assertTrue(repository.isConnected())
    }

    // ── ConnectionError: genuine connect-time failure vs. post-connection
    //    business-logic rejection (regression test — see hasConnectedThisAttempt) ──

    @Test
    fun `an ERROR frame before any CONNECTED sets connectionState to Error`() = runTest(testDispatcher) {
        // A genuine connect-time failure must surface as ConnectionState.Error
        // so LoadingScreen can leave "Connecting…" instead of hanging forever
        // (the regression this guards against). Unmapped wording passes through
        // verbatim — see ServerErrorText.
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateError("forbidden: Denied by fast rules") }) {
            repository.connectionState.value is ConnectionState.Error
        }

        val state = repository.connectionState.value
        assertTrue(state is ConnectionState.Error)
        assertEquals("forbidden: Denied by fast rules", (state as ConnectionState.Error).message)
    }

    @Test
    fun `an offline agent is named as such, not left looking like a flaky network`() = runTest(testDispatcher) {
        // The relay's "Agent not connected: 0x…" is a fact about the agent, not
        // about the transport, and the address it appends is a log detail. The
        // user has to be able to tell this apart from a dropped connection.
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateError("Agent not connected: 0xc31dd64abc") }) {
            repository.connectionState.value is ConnectionState.Error
        }

        val state = repository.connectionState.value as ConnectionState.Error
        assertEquals("This agent is offline. Start it, then reconnect.", state.message)
    }

    @Test
    fun `an ERROR frame after CONNECTED does not flip connectionState and forwards as ConnectionErrorOccurred`() = runBlocking {
        // e.g. "Insufficient ConnectOnion Credits" on an already-established
        // session — a business-logic rejection, not proof the connection died
        // (this server keeps the socket open after it). Must NOT reset
        // connectionState away from Connected.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        val received = withTimeout(3000L) {
            val deferred = async {
                repository.observeEvents().first { it is ChatEvent.ConnectionErrorOccurred }
            }
            while (deferred.isActive) {
                simulateError("Insufficient ConnectOnion Credits")
                delay(20)
            }
            deferred.await()
        } as ChatEvent.ConnectionErrorOccurred

        assertEquals("Insufficient ConnectOnion Credits", received.message)
        assertTrue(
            "connectionState must stay Connected for a post-connection business error",
            repository.connectionState.value is ConnectionState.Connected
        )
    }

    // ── AGENT_PROFILE ────────────────────────────────────────────────

    @Test
    fun `an AGENT_PROFILE frame updates agentProfile with model, tools and skills`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = { simulateAgentProfile() }) { repository.agentProfile.value != null }

        val profile = repository.agentProfile.value
        assertEquals("gemini-2.5-pro", profile?.model)
        assertEquals(listOf("bash"), profile?.tools)
        assertEquals(
            listOf(AgentSkill("web-scraping", "Read a web page and extract structured text from it")),
            profile?.skills
        )
    }

    @Test
    fun `agentProfile is null before any AGENT_PROFILE frame arrives`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        assertEquals(null, repository.agentProfile.value)
    }

    // ── DASHBOARD_SNAPSHOT ───────────────────────────────────────────

    @Test
    fun `a DASHBOARD_SNAPSHOT frame updates dashboardHtml`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = { simulateDashboard() }) { repository.dashboardHtml.value != null }

        assertEquals("<h1>Home</h1>", repository.dashboardHtml.value)
    }

    @Test
    fun `dashboardHtml is null before any DASHBOARD_SNAPSHOT arrives`() = runTest(testDispatcher) {
        // What gates the chat top bar's entry point: nothing on the wire says
        // "this agent has no Home", the host simply never sends one.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        assertEquals(null, repository.dashboardHtml.value)
    }

    @Test
    fun `reconnecting clears the previous agent's dashboard`() = runTest(testDispatcher) {
        // The dashboard's buttons are validated against agentProfile's skills,
        // which connect() clears at the same moment — leaving one behind
        // without the other would point one agent's Home at another's
        // allowlist, and show the wrong agent's page under the right name.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateDashboard() }) { repository.dashboardHtml.value != null }
        assertTrue(repository.dashboardHtml.value != null)

        repository.connect("0xAGENT2")

        assertEquals(null, repository.dashboardHtml.value)
    }

    @Test
    fun `reconnecting to a new agent clears the previous agent's profile`() = runTest(testDispatcher) {
        // Regression test: a stale AGENT_PROFILE from the agent this
        // connection is leaving must not stay visible — the UI would show
        // the wrong agent's tools/skills/model until the new connection's
        // own AGENT_PROFILE (if any) arrives.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateAgentProfile() }) { repository.agentProfile.value != null }
        assertTrue(repository.agentProfile.value != null)

        repository.connect("0xAGENT2")

        assertEquals(null, repository.agentProfile.value)
    }

    // ── approvalMode ─────────────────────────────────────────────────

    private fun simulateModeChanged(mode: String, triggeredBy: String = "agent") {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"mode_changed","mode":"$mode","triggered_by":"$triggeredBy"}"""
        )
    }

    @Test
    fun `approvalMode starts at the server's default mode`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    @Test
    fun `a mode_changed frame moves approvalMode to the mode the agent reports`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = { simulateModeChanged("plan") }) {
            repository.approvalMode.value == ApprovalMode.PLAN
        }

        assertEquals(ApprovalMode.PLAN, repository.approvalMode.value)
    }

    @Test
    fun `a mode_changed frame overrides an optimistic setMode`() = runTest(testDispatcher) {
        // The agent is the authority: it may refuse, or a plugin may move the
        // mode again before our frame is even drained.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)

        awaitUntil(retry = { simulateModeChanged("safe", triggeredBy = "ulw_checkpoint") }) {
            repository.approvalMode.value == ApprovalMode.SAFE
        }

        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    @Test
    fun `setMode sends the frame and updates approvalMode optimistically`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()
        factory.lastWebSocket!!.sentMessages.clear()

        repository.setMode(ApprovalMode.ULW, turns = 12)

        assertEquals(ApprovalMode.ULW, repository.approvalMode.value)
        assertTrue(
            factory.lastWebSocket!!.sentMessages.any {
                it == """{"type":"mode_change","mode":"ulw","turns":12}"""
            }
        )
    }

    @Test
    fun `reconnecting resets approvalMode to safe`() = runTest(testDispatcher) {
        // A trust control must never carry over: leaving an agent that was in
        // accept-edits (or ULW) and landing on a different one still showing
        // that chip would misreport what the *new* agent is allowed to do.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateModeChanged("accept_edits") }) {
            repository.approvalMode.value == ApprovalMode.ACCEPT_EDITS
        }
        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)

        repository.connect("0xAGENT2")

        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    // ── modePending ──────────────────────────────────────────────────
    // The chip goes optimistic on every setMode(), but the agent only
    // confirms it on its next before_iteration hook (see
    // ConnectionRepository.modePending's doc) — these pin the three ways
    // that window can end: confirmed, rejected, or torn down by a reconnect.

    @Test
    fun `setMode marks modePending true`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()

        repository.setMode(ApprovalMode.ACCEPT_EDITS)

        assertTrue(repository.modePending.value)
    }

    @Test
    fun `a mode_changed frame clears modePending`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertTrue(repository.modePending.value)

        awaitUntil(retry = { simulateModeChanged("accept_edits") }) { !repository.modePending.value }

        assertTrue(!repository.modePending.value)
    }

    @Test
    fun `an ERROR naming mode_change rolls approvalMode back to the last confirmed mode and clears modePending`() = runTest(testDispatcher) {
        // The two windows a mode_change frame can miss its mark (session.py's
        // active_io gate on a fresh connection, or a plugin that never
        // reaches poll_mode_changes) both surface this way on this server —
        // see the ConnectionError branch's own comment.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateModeChanged("plan") }) { repository.approvalMode.value == ApprovalMode.PLAN }

        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)
        assertTrue(repository.modePending.value)

        awaitUntil(retry = { simulateError("unknown message type: 'mode_change'") }) {
            repository.approvalMode.value == ApprovalMode.PLAN
        }

        assertEquals(ApprovalMode.PLAN, repository.approvalMode.value)
        assertTrue(!repository.modePending.value)
    }

    @Test
    fun `an unrelated ERROR while modePending does not roll the chip back`() = runBlocking {
        // Guards the message-matching itself: a business-logic ERROR that
        // happens to land while a mode change is in flight must not be
        // mistaken for a mode_change rejection.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertTrue(repository.modePending.value)

        val received = withTimeout(3000L) {
            val deferred = async {
                repository.observeEvents().first { it is ChatEvent.ConnectionErrorOccurred }
            }
            while (deferred.isActive) {
                simulateError("Insufficient ConnectOnion Credits")
                delay(20)
            }
            deferred.await()
        }
        assertTrue(received is ChatEvent.ConnectionErrorOccurred)

        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)
        assertTrue(
            "an unrelated ERROR must not clear modePending",
            repository.modePending.value
        )
    }

    @Test
    fun `reconnecting resets modePending to false`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()
        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertTrue(repository.modePending.value)

        repository.connect("0xAGENT2")

        assertTrue(!repository.modePending.value)
    }
}
