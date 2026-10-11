package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ExecRequest
import ai.openonion.oochat.domain.model.PlanStatus
import ai.openonion.oochat.domain.model.ServerSessionStatus
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.domain.usecase.ServerErrorText
import ai.openonion.oochat.knownIssue
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val sentMessages = CopyOnWriteArrayList<String>()
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
        keyManager.save(TestIdentity.keys())
        factory = FakeWebSocketFactory()

        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url ->
                AgentConnection(
                    keyManager = km,
                    relayUrl = url,
                    webSocketFactory = factory,
                    signer = { _, _ -> "fake-signature" },
                    pendingMessageSink = sink
                )
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

    private fun simulateConnectedWith(status: String, serverNewer: Boolean) {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"CONNECTED","session_id":"sess-1","status":"$status","server_newer":$serverNewer}"""
        )
    }

    private fun simulateToolCall(toolId: String) {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"tool_call","id":"ev-$toolId","tool_id":"$toolId","name":"bash"}"""
        )
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
    fun `the Connected state carries the status and server_newer the Host sent`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = true) }) {
            repository.connectionState.value is ConnectionState.Connected
        }

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals(ServerSessionStatus.RUNNING, connected.status)
        assertTrue(connected.serverNewer)
    }

    @Test
    fun `the Connected state is UNKNOWN and not newer when the Host sent neither`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals(ServerSessionStatus.UNKNOWN, connected.status)
        assertFalse(connected.serverNewer)
    }

    @Test
    fun `a later CONNECTED replaces the status of the earlier one`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = true) }) {
            (repository.connectionState.value as? ConnectionState.Connected)?.status == ServerSessionStatus.RUNNING
        }

        awaitUntil(retry = { simulateConnectedWith("connected", serverNewer = false) }) {
            (repository.connectionState.value as? ConnectionState.Connected)?.status == ServerSessionStatus.CONNECTED
        }

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertFalse("the earlier frame's server_newer must not linger", connected.serverNewer)
    }

    private fun awaitRunning() = awaitUntil(retry = { simulateConnectedWith("running", serverNewer = true) }) {
        (repository.connectionState.value as? ConnectionState.Connected)?.status == ServerSessionStatus.RUNNING
    }

    @Test
    fun `releasing the status clears what the Host said and keeps the connection`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitRunning()

        repository.releaseSessionStatus()

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals(ServerSessionStatus.UNKNOWN, connected.status)
        assertFalse(connected.serverNewer)
        assertTrue(repository.isConnected())
    }

    @Test
    fun `releasing the status leaves a connection that is not Connected alone`() = runTest(testDispatcher) {
        repository.releaseSessionStatus()
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)

        repository.connect("0xAGENT")
        repository.releaseSessionStatus()
        assertEquals(ConnectionState.Connecting, repository.connectionState.value)
    }

    @Test
    fun `moving to another conversation forgets the status of the one left`() = runTest(testDispatcher) {
        repository.connect("0xAGENT", conversationId = "conv-A")
        awaitRunning()

        repository.switchConversation("conv-B")

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals("the status was conv-A's, not conv-B's", ServerSessionStatus.UNKNOWN, connected.status)
    }

    @Test
    fun `staying on the same conversation keeps the status`() = runTest(testDispatcher) {
        repository.connect("0xAGENT", conversationId = "conv-A")
        awaitRunning()

        repository.switchConversation("conv-A")

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals(ServerSessionStatus.RUNNING, connected.status)
    }

    @Test
    fun `adopting the session the connection is already on keeps the status`() = runTest(testDispatcher) {
        // No conversation yet, so the Host mints the session (sess-1) and a conversation later takes its id.
        repository.connect("0xAGENT")
        awaitRunning()

        repository.switchConversation("sess-1")

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertEquals(ServerSessionStatus.RUNNING, connected.status)
    }

    @Test
    fun `a finished turn ends the running the Host reported`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitRunning()

        awaitUntil(retry = { simulateFrame("""{"type":"OUTPUT","result":"done"}""") }) {
            (repository.connectionState.value as? ConnectionState.Connected)?.status == ServerSessionStatus.UNKNOWN
        }

        val connected = repository.connectionState.value as ConnectionState.Connected
        assertFalse("the whole answer goes, not just the status", connected.serverNewer)
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
    fun `a burst of frames bigger than the chat event buffer reaches a slow collector whole`() = runBlocking {
        repository.connect("0xAGENT")
        simulateConnected()

        // A collector stuck on its first event while the burst arrives, like the chat's Room writes.
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val delivered = java.util.Collections.synchronizedList(mutableListOf<String>())
        val collector = launch(Dispatchers.Default) {
            repository.observeEvents().collect { event ->
                started.complete(Unit)
                release.await()
                if (event is ChatEvent.ChatItemReceived) delivered += event.item.id
            }
        }
        // Frames sent before the collector subscribes are lost, so send markers until it has one.
        var marker = 0
        withTimeout(5000L) {
            while (!started.isCompleted) {
                simulateToolCall("m${marker++}")
                delay(20)
            }
        }

        val burst = 200
        repeat(burst) { simulateToolCall("t$it") }
        delay(300) // lets the repository's own collector pass every frame on while the consumer is stuck
        release.complete(Unit)
        // The collector is still adding on another thread, and iterating a synchronizedList needs its lock held.
        fun arrived() = synchronized(delivered) { delivered.count { it.startsWith("t") } }
        withTimeoutOrNull(5000L) { while (arrived() < burst) delay(20) }
        collector.cancelAndJoin()

        assertEquals((0 until burst).map { "t$it" }, synchronized(delivered) { delivered.filter { it.startsWith("t") } })
    }

    @Test
    fun `sendMessage forwards to the underlying AgentConnection once connected`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        simulateConnected()

        repository.sendMessage("hello", "0xAGENT")

        assertTrue(factory.lastWebSocket!!.sentMessages.any { it.contains("hello") })
    }

    @Test
    fun `executeTool converts arguments and returns matching result`() =
        runBlocking {
            repository.connect(
                agentAddress = "0xAGENT",
                conversationId = "conversation-1"
            )
            awaitUntil(retry = { simulateConnected("conversation-1") }) {
                repository.isConnected()
            }

            val pendingOutcome = async(start = CoroutineStart.UNDISPATCHED) {
                repository.executeTool(
                    ExecRequest(
                        execId = "exec-1",
                        tool = "shell",
                        argsJson = """{"command":"pwd"}""",
                        agentAddress = "0xAGENT"
                    )
                )
            }

            awaitUntil { factory.lastWebSocket!!.sentMessages.isNotEmpty() }
            assertTrue(
                factory.lastWebSocket!!.sentMessages.any {
                    it.contains("\"exec_id\":\"exec-1\"")
                }
            )

            factory.lastListener!!.onMessage(
                factory.lastWebSocket!!,
                """
                {
                  "type": "EXEC_RESULT",
                  "exec_id": "exec-1",
                  "status": "success",
                  "result": "/workspace"
                }
                """.trimIndent()
            )

            assertEquals(
                ExecOutcome.Success("/workspace"),
                pendingOutcome.await()
            )
        }

    @Test
    fun `executeTool does not require the local conversation to match`() =
        runBlocking {
            repository.connect(
                agentAddress = "0xAGENT",
                conversationId = "conversation-1"
            )
            awaitUntil(retry = { simulateConnected("conversation-1") }) {
                repository.isConnected()
            }
            val pendingOutcome = async(start = CoroutineStart.UNDISPATCHED) {
                repository.executeTool(
                    ExecRequest(
                        execId = "exec-2",
                        tool = "shell",
                        argsJson = """{"command":"pwd"}""",
                        agentAddress = "0xAGENT"
                    )
                )
            }

            awaitUntil { factory.lastWebSocket!!.sentMessages.any { it.contains("\"exec_id\":\"exec-2\"") } }

            factory.lastListener!!.onMessage(
                factory.lastWebSocket!!,
                """
                {
                  "type": "EXEC_RESULT",
                  "exec_id": "exec-2",
                  "status": "success",
                  "result": "/workspace"
                }
                """.trimIndent()
            )

            assertEquals(
                ExecOutcome.Success("/workspace"),
                pendingOutcome.await()
            )
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

    // ── W16AAPPLE-12: a refused OIP version ──────────────────────────

    private fun simulateVersionRejection() {
        factory.lastListener?.onMessage(
            factory.lastWebSocket!!,
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,""" +
                """"protocol":{"name":"oip","version":"0.2","min_version":"0.2","max_version":"0.2"}}"""
        )
    }

    @Test
    fun `a refused OIP version is one readable, non-retryable error`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateVersionRejection() }) {
            repository.connectionState.value is ConnectionState.Error
        }

        val state = repository.connectionState.value as ConnectionState.Error
        assertEquals(
            "This agent uses a different version of the chat protocol (oip/0.2) than this app (oip/0.1). " +
                "Update the app or the agent, then reconnect.",
            state.message
        )
        assertFalse("nothing may retry a version mismatch automatically", state.retryable)
    }

    @Test
    fun `a refused OIP version after an earlier CONNECTED is still fatal`() = runTest(testDispatcher) {
        // Unlike a business-logic ERROR on a live session, which stays a
        // non-fatal chat event: the refusal means the connection is over.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) {
            repository.connectionState.value is ConnectionState.Connected
        }

        simulateVersionRejection()
        awaitUntil { repository.connectionState.value is ConnectionState.Error }

        assertFalse((repository.connectionState.value as ConnectionState.Error).retryable)
    }

    @Test
    fun `an ordinary connect-time ERROR stays retryable`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")

        awaitUntil(retry = { simulateError("forbidden: Denied by fast rules") }) {
            repository.connectionState.value is ConnectionState.Error
        }

        assertTrue((repository.connectionState.value as ConnectionState.Error).retryable)
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

    // ── W16AAPPLE-82, -90: a refused reconnect ends in Error ────────

    @Test
    fun `a reconnect refused for the clock ends in Error naming the date and time, though the attempt had connected`() = runBlocking {
        // Unlike the business error above: the ERROR answers the reconnect's
        // own CONNECT, so the session is not coming back. Before, this left
        // the state on Reconnecting for good.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        val first = factory.lastWebSocket

        // A conversation switch opens the new socket at once, with no backoff to wait out.
        repository.switchConversation("conv-2")
        awaitUntil { factory.lastWebSocket !== first }
        factory.lastListener!!.onOpen(
            factory.lastWebSocket!!,
            Response.Builder()
                .request(Request.Builder().url("wss://fake.example.com/ws").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
        )
        simulateError("unauthorized: signature expired")

        awaitUntil { repository.connectionState.value is ConnectionState.Error }
        val state = repository.connectionState.value as ConnectionState.Error
        assertEquals(ServerErrorText.CLOCK_WRONG, state.message)
        assertTrue("Reconnect is offered", state.retryable)
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

    // ── plan (W16AAPPLE-17) ──────────────────────────────────────────

    private fun planFrame(vararg statuses: String) =
        """{"type":"plan","id":"tr-plan","entries":[""" +
            statuses.mapIndexed { i, s -> """{"content":"step $i","priority":"high","status":"$s"}""" }
                .joinToString(",") + "]}"

    @Test
    fun `a plan frame replaces the whole plan`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = { simulateFrame(planFrame("in_progress", "pending")) }) { repository.plan.value.size == 2 }
        awaitUntil(retry = { simulateFrame(planFrame("completed")) }) { repository.plan.value.size == 1 }

        assertEquals(PlanStatus.COMPLETED, repository.plan.value.single().status)
    }

    @Test
    fun `an empty plan frame clears the plan`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateFrame(planFrame("pending")) }) { repository.plan.value.isNotEmpty() }

        awaitUntil(retry = { simulateFrame("""{"type":"plan","id":"tr-2","entries":[]}""") }) {
            repository.plan.value.isEmpty()
        }
    }

    @Test
    fun `a malformed plan frame leaves the last good plan showing`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateFrame(planFrame("in_progress")) }) { repository.plan.value.isNotEmpty() }

        // One bad entry rejects the frame whole, never a partial plan.
        simulateFrame(
            """{"type":"plan","id":"tr-3","entries":[""" +
                """{"content":"ok","priority":"low","status":"pending"},""" +
                """{"content":"bad","priority":"urgent","status":"pending"}]}"""
        )
        // The collector handles events in order, so once the dashboard that
        // followed it is visible, the bad plan has been handled too.
        awaitUntil(retry = { simulateDashboard() }) { repository.dashboardHtml.value != null }

        assertEquals(listOf("step 0"), repository.plan.value.map { it.content })
    }

    @Test
    fun `a reconnect gets the plan back from the CONNECTED snapshot`() = runTest(testDispatcher) {
        // The Host carries its plan on the session it sends with CONNECTED,
        // so a client coming back does not have to wait for the next update.
        repository.connect("0xAGENT")
        awaitUntil(retry = {
            simulateFrame(
                """{"type":"CONNECTED","session_id":"sess-1","session":{"session_id":"sess-1",""" +
                    """"plan":[{"content":"Run tests","priority":"high","status":"in_progress"}]}}"""
            )
        }) { repository.plan.value.isNotEmpty() }

        assertEquals("Run tests", repository.plan.value.single().content)
        assertEquals(PlanStatus.IN_PROGRESS, repository.plan.value.single().status)
    }

    @Test
    fun `connecting again clears the previous plan`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        awaitUntil(retry = { simulateFrame(planFrame("pending")) }) { repository.plan.value.isNotEmpty() }

        repository.connect("0xAGENT2")

        assertTrue(repository.plan.value.isEmpty())
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

    // -- the published profile: slash-palette skills and agent balance --
    //
    // No agent actually sends AGENT_PROFILE; the palette is filled from the
    // relay's directory on CONNECTED (fetchPublishedProfile). These run the
    // real AgentDiscoveryService against a local directory.

    private val publishedAgent = "0x" + "c5".repeat(32)

    /** Every directory a test started, shut down in [shutDownDirectories] even when the test fails. */
    private val directories = mutableListOf<MockWebServer>()

    @After
    fun shutDownDirectories() = directories.forEach { it.shutdown() }

    /** A directory that answers every lookup with [body], recording the paths asked for. */
    private fun directory(body: String): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body)
        }
        start()
        directories += this
    }

    private fun simulateFrame(json: String) {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, json)
    }

    private fun repositoryWithDirectory(server: MockWebServer) = ConnectionRepositoryImpl(
        keyManager = keyManager,
        relayUrl = server.url("/").toString(),
        pendingMessageSink = sink,
        agentConnectionFactory = { km, url -> AgentConnection(km, url, factory, signer = { _, _ -> "fake-signature" }, pendingMessageSink = sink) },
        agentDiscovery = AgentDiscoveryService()
    )

    @Test
    fun `on connect the agent's published skills come from the directory`() = runTest(testDispatcher) {
        val server = directory(
            """{"profile":{"alias":"palette-agent","model":"gemini-2.5-flash","tools":["echo"],
              |"skills":[{"name":"explain","description":"Explain a snippet of code"},{"name":"translate"}]}}"""
                .trimMargin()
        )
        val repo = repositoryWithDirectory(server)

        repo.connect(publishedAgent)
        awaitUntil(retry = { simulateConnected() }) { repo.agentProfile.value?.skills?.isNotEmpty() == true }

        assertEquals(
            listOf(AgentSkill("explain", "Explain a snippet of code"), AgentSkill("translate")),
            repo.agentProfile.value?.skills
        )
        // The agent's row, not this device's: CONNECTED carries the identity
        // the relay authenticated, and looking that up returns our own empty row.
        assertEquals("/api/agents/$publishedAgent", server.takeRequest().path)
    }

    @Test
    fun `known issue - the directory path keeps duplicate skill names and placeholder descriptions`() = runTest(testDispatcher) {
        // An AGENT_PROFILE frame is cleaned by ProtocolParser.toAgentSkills
        // (one entry per name, "No description" -> none). The directory path -
        // the one that actually fills the palette - skips both, and the
        // palette keys its rows on the name.
        val server = directory(
            """{"profile":{"skills":[{"name":"explain","description":"No description"},
              |{"name":"explain","description":"Explain a snippet of code"}]}}""".trimMargin()
        )
        val repo = repositoryWithDirectory(server)

        repo.connect(publishedAgent)
        awaitUntil(retry = { simulateConnected() }) { repo.agentProfile.value?.skills?.isNotEmpty() == true }
        val skills = repo.agentProfile.value!!.skills

        knownIssue("directory skills are not de-duplicated or normalised the way AGENT_PROFILE skills are") {
            assertEquals("one entry per name", listOf("explain"), skills.map { it.name })
            assertTrue("placeholder description kept: $skills", skills.none { it.description == "No description" })
        }
    }

    @Test
    fun `a balance the agent published survives the directory filling in its skills`() = runTest(testDispatcher) {
        val server = directory("""{"profile":{"skills":[{"name":"explain"}]}}""")
        val repo = repositoryWithDirectory(server)

        repo.connect(publishedAgent)
        awaitUntil(retry = { simulateFrame("""{"type":"AGENT_PROFILE","name":"low-funds","skills":[],"balance_usd":0.42}""") }) {
            repo.agentProfile.value != null
        }
        awaitUntil(retry = { simulateConnected() }) { repo.agentProfile.value?.skills?.isNotEmpty() == true }

        assertEquals(0.42, repo.agentProfile.value?.balanceUsd)
    }

    @Test
    fun `an agent's published balance reaches the live profile the low-balance bar reads`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = { simulateFrame("""{"type":"AGENT_PROFILE","name":"low-funds","balance_usd":0.42}""") }) {
            repository.agentProfile.value != null
        }

        assertEquals(0.42, repository.agentProfile.value?.balanceUsd)
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
        // A v2 signed command: the Host executes the signed payload.
        val expected = Json.parseToJsonElement("""{"type":"mode_change","mode":"ulw","turns":12}""")
         assertTrue(
             factory.lastWebSocket!!.sentMessages.any {
                val payload = Json.parseToJsonElement(it).jsonObject["payload"]?.jsonObject
                payload != null &&
                    JsonObject(payload - setOf("to", "timestamp", "nonce")) == expected &&
                    it.contains("\"signature\"")
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

    // ── Host-managed modes (W16AAPPLE-18) ────────────────────────────
    // A ConnectOnion 1.7+ Host advertises `session_modes` on CONNECTED and
    // answers mode_change itself (ws_router/mode.py). Fixtures follow
    // websocket-protocol.md's CONNECTED example and handle_mode_change's frames.

    private fun simulateHostModes(current: String = "auto", turnsLeft: String = "null") {
        simulateFrame(
            """{"type":"CONNECTED","session_id":"sess-1","status":"connected",
              |"session_modes":{"currentModeId":"$current","turnsLeft":$turnsLeft,"availableModes":[
              |{"id":"read-only","name":"Read only"},{"id":"auto","name":"Auto"},
              |{"id":"full-access","name":"Full access"}]}}""".trimMargin()
        )
    }

    /** The signed payloads of every mode_change sent, without the envelope's to/timestamp/nonce. */
    private fun sentModeChanges(): List<JsonObject> =
        factory.lastWebSocket!!.sentMessages.mapNotNull { sent ->
            val payload = Json.parseToJsonElement(sent).jsonObject["payload"]?.jsonObject
            payload?.takeIf { it["type"]?.toString() == "\"mode_change\"" }
                ?.let { JsonObject(it - setOf("to", "timestamp", "nonce")) }
        }

    @Test
    fun `session_modes on CONNECTED makes the Host the authority for mode and catalogue`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }

        val modes = repository.sessionModes.value
        assertEquals(listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO, ApprovalMode.FULL_ACCESS), modes.available)
        assertEquals(listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO), modes.cycle)
        assertEquals(ApprovalMode.AUTO, repository.approvalMode.value)
        assertFalse(repository.modePending.value)
    }

    @Test
    fun `CONNECTED in full access carries the Host's turn budget`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes(current = "full-access", turnsLeft = "7") }) {
            repository.approvalMode.value == ApprovalMode.FULL_ACCESS
        }

        assertEquals(7, repository.sessionModes.value.turnsLeft)
    }

    @Test
    fun `a CONNECTED without session_modes keeps the legacy catalogue`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }

        assertEquals(SessionModes.LEGACY, repository.sessionModes.value)
        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    @Test
    fun `setMode on a Host-managed connection sends the exact mode and never a turn budget`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }
        factory.lastWebSocket!!.sentMessages.clear()

        repository.setMode(ApprovalMode.FULL_ACCESS, turns = 50)

        assertEquals(
            listOf(Json.parseToJsonElement("""{"type":"mode_change","mode":"full-access"}""")),
            sentModeChanges()
        )
        assertEquals(ApprovalMode.FULL_ACCESS, repository.approvalMode.value)
        assertTrue(repository.modePending.value)
    }

    @Test
    fun `a mode the connection does not offer is never sent`() = runTest(testDispatcher) {
        // The Host has no alias reader: "safe" would only come back refused.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }
        factory.lastWebSocket!!.sentMessages.clear()

        repository.setMode(ApprovalMode.SAFE)

        assertTrue(sentModeChanges().isEmpty())
        assertEquals(ApprovalMode.AUTO, repository.approvalMode.value)
        assertFalse(repository.modePending.value)
    }

    @Test
    fun `a legacy connection does not send the Host's vocabulary either`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected() }) { repository.connectionState.value is ConnectionState.Connected }
        factory.lastWebSocket!!.sentMessages.clear()

        repository.setMode(ApprovalMode.READ_ONLY)

        assertTrue(sentModeChanges().isEmpty())
        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    @Test
    fun `the Host's mode_changed confirms the request and carries its budget`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }
        repository.setMode(ApprovalMode.FULL_ACCESS)

        awaitUntil(retry = {
            simulateFrame("""{"type":"mode_changed","mode":"full-access","turns_left":20,"session_id":"sess-1"}""")
        }) { !repository.modePending.value }

        assertEquals(ApprovalMode.FULL_ACCESS, repository.approvalMode.value)
        assertEquals(20, repository.sessionModes.value.turnsLeft)
    }

    @Test
    fun `mode_budget_changed counts down and expiry back to auto clears the budget`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes(current = "full-access", turnsLeft = "3") }) {
            repository.approvalMode.value == ApprovalMode.FULL_ACCESS
        }

        awaitUntil(retry = {
            simulateFrame("""{"type":"mode_budget_changed","id":"b1","mode":"full-access","turns_left":2,"previous_turns_left":3}""")
        }) { repository.sessionModes.value.turnsLeft == 2 }

        awaitUntil(retry = {
            simulateFrame("""{"type":"mode_changed","id":"m9","mode":"auto","turns_left":null,"triggered_by":"full_access_expired"}""")
        }) { repository.approvalMode.value == ApprovalMode.AUTO }

        assertNull(repository.sessionModes.value.turnsLeft)
    }

    @Test
    fun `the Host refusing a busy mode change rolls the chip back`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }

        repository.setMode(ApprovalMode.READ_ONLY)
        assertEquals(ApprovalMode.READ_ONLY, repository.approvalMode.value)

        awaitUntil(retry = {
            simulateFrame("""{"type":"ERROR","code":-32000,"message":"Session is busy","retryable":true}""")
        }) { !repository.modePending.value }

        assertEquals(ApprovalMode.AUTO, repository.approvalMode.value)
    }

    @Test
    fun `the Host refusing a mode change for a missing session rolls the chip back`() = runTest(testDispatcher) {
        // session/mode.py's -32002. Without it the chip sat on the new mode, pending, for good.
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes() }) { repository.sessionModes.value.hostManaged }

        repository.setMode(ApprovalMode.READ_ONLY)
        assertTrue(repository.modePending.value)

        awaitUntil(retry = {
            simulateFrame("""{"type":"ERROR","code":-32002,"message":"Session not found"}""")
        }) { !repository.modePending.value }

        assertEquals(ApprovalMode.AUTO, repository.approvalMode.value)
    }

    @Test
    fun `reconnecting forgets the Host's catalogue`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateHostModes(current = "read-only") }) { repository.sessionModes.value.hostManaged }

        repository.connect("0xAGENT2")

        assertEquals(SessionModes.LEGACY, repository.sessionModes.value)
        assertEquals(ApprovalMode.SAFE, repository.approvalMode.value)
    }

    // ── hostSessionStatus (W16AAPPLE-18) ─────────────────────────────

    @Test
    fun `hostSessionStatus follows CONNECTED and is not released with it`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.RUNNING
        }

        repository.releaseSessionStatus()

        assertEquals(ServerSessionStatus.RUNNING, repository.hostSessionStatus.value)
    }

    @Test
    fun `a SESSION_STATUS reply about this session is shown`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnected("sess-1") }) { repository.connectionState.value is ConnectionState.Connected }

        awaitUntil(retry = {
            simulateFrame("""{"type":"SESSION_STATUS","session_id":"sess-1","status":"not_found"}""")
        }) { repository.hostSessionStatus.value == ServerSessionStatus.NOT_FOUND }
    }

    @Test
    fun `a SESSION_STATUS reply about another session is not shown`() = runBlocking {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("connected", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.CONNECTED
        }

        // Wait for the reply itself to come through, so the assertion below is not vacuous.
        withTimeout(3000L) {
            val deferred = async {
                repository.observeEvents().first { it is ChatEvent.SessionStatusReceived }
            }
            while (deferred.isActive) {
                simulateFrame("""{"type":"SESSION_STATUS","session_id":"sess-OLD","status":"not_found"}""")
                delay(20)
            }
            deferred.await()
        }

        assertEquals(ServerSessionStatus.CONNECTED, repository.hostSessionStatus.value)
    }

    @Test
    fun `an OUTPUT returns hostSessionStatus to idle`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.RUNNING
        }

        awaitUntil(retry = { simulateFrame("""{"type":"OUTPUT","result":"done"}""") }) {
            repository.hostSessionStatus.value == ServerSessionStatus.CONNECTED
        }
    }

    private fun sentSessionStatusQueries(): List<String> =
        factory.lastWebSocket!!.sentMessages.filter { it.contains("\"SESSION_STATUS\"") }

    @Test
    fun `joined while running, a turn that fails with an ERROR goes back to idle and asks SESSION_STATUS`() =
        runTest(testDispatcher) {
            // ws_router/agent_io.py ends a failed run with this ERROR instead of an OUTPUT.
            repository.connect("0xAGENT")
            awaitUntil(retry = { simulateConnectedWith("running", serverNewer = false) }) {
                repository.hostSessionStatus.value == ServerSessionStatus.RUNNING
            }
            factory.lastWebSocket!!.sentMessages.clear()

            awaitUntil(retry = {
                simulateFrame("""{"type":"ERROR","code":-32603,"message":"Unable to run agent"}""")
            }) { repository.hostSessionStatus.value == ServerSessionStatus.CONNECTED }

            val query = Json.parseToJsonElement(sentSessionStatusQueries().first()).jsonObject
            assertEquals(Json.parseToJsonElement("""{"session_id":"sess-1"}"""), query["session"])
        }

    @Test
    fun `an ERROR in other wording while running is settled by the SESSION_STATUS reply`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.RUNNING
        }
        factory.lastWebSocket!!.sentMessages.clear()

        awaitUntil(retry = { simulateError("Insufficient ConnectOnion Credits") }) {
            sentSessionStatusQueries().isNotEmpty()
        }
        assertEquals("not settled until the Host says", ServerSessionStatus.RUNNING, repository.hostSessionStatus.value)

        awaitUntil(retry = {
            simulateFrame("""{"type":"SESSION_STATUS","session_id":"sess-1","status":"connected"}""")
        }) { repository.hostSessionStatus.value == ServerSessionStatus.CONNECTED }
    }

    @Test
    fun `an ERROR while idle asks nothing`() = runBlocking {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("connected", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.CONNECTED
        }
        factory.lastWebSocket!!.sentMessages.clear()

        withTimeout(3000L) {
            val deferred = async { repository.observeEvents().first { it is ChatEvent.ConnectionErrorOccurred } }
            while (deferred.isActive) {
                simulateError("Insufficient ConnectOnion Credits")
                delay(20)
            }
            deferred.await()
        }

        assertTrue(sentSessionStatusQueries().isEmpty())
        assertEquals(ServerSessionStatus.CONNECTED, repository.hostSessionStatus.value)
    }

    @Test
    fun `reconnecting resets hostSessionStatus`() = runTest(testDispatcher) {
        repository.connect("0xAGENT")
        awaitUntil(retry = { simulateConnectedWith("running", serverNewer = false) }) {
            repository.hostSessionStatus.value == ServerSessionStatus.RUNNING
        }

        repository.connect("0xAGENT2")

        assertEquals(ServerSessionStatus.UNKNOWN, repository.hostSessionStatus.value)
    }
}
