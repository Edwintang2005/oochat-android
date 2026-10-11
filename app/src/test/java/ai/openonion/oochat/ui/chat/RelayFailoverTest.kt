package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.ConnectOnionApplication
import ai.openonion.oochat.Constants.DEFAULT_SERVER_URL
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import ai.openonion.oochat.data.local.RoomPendingMessageSink
import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.data.repository.AgentRepositoryImpl
import ai.openonion.oochat.data.repository.AgentSecureConfigRepositoryImpl
import ai.openonion.oochat.data.repository.ConnectionRepositoryImpl
import ai.openonion.oochat.data.repository.MemoryGatedSessionStore
import ai.openonion.oochat.data.repository.MessageRepositoryImpl
import ai.openonion.oochat.data.repository.SessionRepositoryImpl
import ai.openonion.oochat.data.repository.SessionStoreImpl
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCase
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.domain.usecase.PersistenceTransaction
import ai.openonion.oochat.knownIssue
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.NetworkMonitor
import ai.openonion.oochat.testing.FakeOipHost
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import ai.openonion.oochat.testing.FakeOipSocket
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ProtocolException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The relay link at `wss://oo.openonion.ai/ws/input` fails mid-turn, and the
 * app must get back to the same running turn through it with nothing lost and
 * nothing doubled.
 *
 * What a relay failure leaves behind is a Host whose turn is still running
 * (`ws_router/session.py` cancels only the forwarder when a client socket
 * goes) and a client that must climb back onto the relay and CONNECT with
 * `session_id` and `last_msg_id`. So the test fails the relay in the ways a
 * real one fails, in front of [FakeOipHost], and checks what reaches Room and
 * the screen.
 *
 * The app is wired from the production classes, as in [ProcessDeathResumeTest];
 * only the socket is fake. The reconnect ladder runs on a virtual clock, so its
 * 2-32 s rungs cost no real time and their spacing can be checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayFailoverTest {

    /**
     * The relay in front of the Host. It forwards frames both ways and stamps
     * each client socket's frames with a `conn_id` of its own, as it does for
     * a Host that announced `relay_features: ["conn_id"]`. The relay's own
     * failures are switched on per socket.
     */
    private inner class FakeRelay : FakeOipHost() {
        val requests = CopyOnWriteArrayList<Request>()

        /** Virtual time each socket was dialled at, in order. */
        val dialledAt = CopyOnWriteArrayList<Long>()

        /** Upgrades still to refuse with a 502, as Cloudflare does while the relay is down. */
        @Volatile var refuseUpgrades = 0

        /** CONNECTs still to turn away: the relay is up, the Host not yet re-announced. */
        @Volatile var agentAway = 0

        private val connIds = java.util.concurrent.ConcurrentHashMap<FakeOipSocket, String>()
        private val sessionIds = java.util.concurrent.ConcurrentHashMap<FakeOipSocket, String>()

        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            requests += request
            dialledAt += scheduler.currentTime
            return super.create(request, listener)
        }

        override fun open(socket: FakeOipSocket) {
            if (refuseUpgrades > 0) {
                refuseUpgrades--
                socket.dead = true
                // OkHttp's RealWebSocket.checkUpgradeSuccess, for a non-101 answer.
                deliver {
                    socket.listener.onFailure(
                        socket,
                        ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'"),
                        badGateway(socket.request())
                    )
                }
                return
            }
            connIds[socket] = "conn-${connIds.size + 1}"
            super.open(socket)
        }

        /**
         * The Host has lost its announce socket. The relay does not tell the
         * client: it says nothing until the client next sends a frame, then
         * answers that frame for the missing Host (seen on oo.openonion.ai).
         */
        @Volatile var hostGone = false

        @Volatile var connectsTurnedAway = 0

        override fun receive(socket: FakeOipSocket, frame: JsonObject) {
            val type = frame.string("type")
            if (type == "CONNECT") {
                assertEquals("the relay routes on `to`, so every CONNECT through it must name the agent", agent, frame.string("to"))
            }
            if (hostGone || (type == "CONNECT" && agentAway > 0)) {
                if (type == "CONNECT") {
                    if (agentAway > 0) agentAway--
                    connectsTurnedAway++
                }
                turnAway(socket)
                return
            }
            if (type == "CONNECT") frame.string("session_id")?.let { sessionIds[socket] = it }
            super.receive(socket, frame)
        }

        /**
         * What oo.openonion.ai does with a frame for an agent it has no
         * announce socket for: an ERROR, then the connection is dropped with no
         * close frame, which OkHttp reports as a failure.
         */
        private fun turnAway(socket: FakeOipSocket) {
            fromRelay(socket, """{"type":"ERROR","error":"Agent not connected: $agent"}""")
            drop(socket)
        }

        /** The relay's own 30 s keepalive, which the app answers with PONG. */
        fun ping(socket: FakeOipSocket) = fromRelay(socket, """{"type":"PING"}""")

        /** A frame the relay sends itself, so not stamped as forwarded. */
        private fun fromRelay(socket: FakeOipSocket, frame: String) =
            deliver { socket.listener.onMessage(socket, frame) }

        override fun onTheWire(socket: FakeOipSocket, frame: String): String {
            val connId = connIds[socket] ?: return frame
            val fields = Json.parseToJsonElement(frame).jsonObject.toMutableMap()
            fields["conn_id"] = JsonPrimitive(connId)
            sessionIds[socket]?.let { sid -> fields.putIfAbsent("session_id", JsonPrimitive(sid)) }
            return JsonObject(fields).toString()
        }

        /** The relay restarts: the socket closes with [code] and a reason, as a real close frame does. */
        fun restart(socket: FakeOipSocket, code: Int, reason: String) {
            socket.dead = true
            deliver {
                socket.listener.onClosing(socket, code, reason)
                socket.listener.onClosed(socket, code, reason)
            }
        }

        val connIdsUsed: Set<String> get() = connIds.values.toSet()

        private fun badGateway(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(502)
            .message("Bad Gateway")
            .build()
    }

    /** One launch of the app, wired as DefaultAppContainer wires it. */
    private inner class App {
        val db: AppDatabase = Room.databaseBuilder(application, AppDatabase::class.java, DB_NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val agents = AgentRepositoryImpl(db.agentDao(), AgentSecureConfigRepositoryImpl(application))
        val sessions = SessionRepositoryImpl(db.sessionDao())
        val messages = MessageRepositoryImpl(db.messageDao())
        private val sessionStore = MemoryGatedSessionStore(
            delegate = SessionStoreImpl(db.sessionStateDao()),
            memoryEnabled = application.container.appSettings.memoryEnabled
        )
        private val outbox = RoomPendingMessageSink(
            dao = db.pendingMessageDao(),
            spillDir = File(application.filesDir, "pending-attachments")
        )
        val connection = ConnectToAgentUseCase(
            keyManager = keyManager,
            relayUrl = DIRECTORY,
            connectionTimeoutMs = 10_000L,
            sessionStore = sessionStore,
            pendingMessageSink = outbox,
            repositoryFactory = {
                ConnectionRepositoryImpl(
                    keyManager = keyManager,
                    relayUrl = DIRECTORY,
                    sessionStore = sessionStore,
                    pendingMessageSink = outbox,
                    // The socket is dialled at the production relay; the
                    // directory lookups above go to an address that cannot resolve.
                    agentConnectionFactory = { km, _ ->
                        AgentConnection(
                            km, AgentConnection.DEFAULT_RELAY, relay,
                            signer = { _, _ -> "fake-signature" },
                            reconnectDispatcher = StandardTestDispatcher(scheduler),
                            pendingMessageSink = outbox
                        )
                    }
                )
            }
        )
        val vm = ChatViewModel(
            application = application,
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connection,
            agentRepository = agents,
            messageRepository = messages,
            conversationHistory = ConversationHistoryUseCase(
                agents, sessions, messages, PersistenceTransaction(db),
                liveSessionId = { connection.liveSessionIdFor(it) }
            ),
            initialMyAddress = "0xTEST",
            networkMonitor = network,
            isAppInForeground = { true }
        )

        val items: List<ChatItem> get() = vm.uiState.value.chatItems
        val state: ConnectionState get() = vm.connectionState.value
    }

    private val online = MutableStateFlow(true)
    private val network = object : NetworkMonitor {
        override val isOnline = online
    }

    private lateinit var application: ConnectOnionApplication
    private lateinit var keyManager: KeyManager
    private lateinit var relay: FakeRelay
    private lateinit var main: ExecutorService
    private lateinit var scheduler: TestCoroutineScheduler
    private var app: App? = null

    private val agent = "0x" + "ab".repeat(32)
    private val question = "What's in the logs?"
    private val reply = "Nothing unusual: three warnings, no errors."

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.deleteDatabase(DB_NAME)
        main = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(main.asCoroutineDispatcher())
        scheduler = TestCoroutineScheduler()
        keyManager = KeyManager(application)
        keyManager.save(TestIdentity.keys())
        relay = FakeRelay()
    }

    @After
    fun tearDown() {
        relay.shutdown()
        app?.db?.takeIf { it.isOpen }?.close()
        Dispatchers.resetMain()
        main.shutdownNow()
        application.deleteDatabase(DB_NAME)
    }

    // ── the turn, as the Host streams it (the same turn as ProcessDeathResumeTest) ──

    private val llmCall1 = """{"type":"llm_call","id":"trace-1","model":"gemini-2.5-flash","status":"running"}"""
    private val llmResult1 =
        """{"type":"llm_result","id":"trace-1","status":"success","model":"gemini-2.5-flash",""" +
            """"duration_ms":300.0,"usage":{"input_tokens":10,"output_tokens":5,"cost":0.001}}"""
    private val toolCall =
        """{"type":"tool_call","id":"ev-3","tool_id":"tool-1","name":"bash","args":{"command":"tail app.log"}}"""
    private val toolResult =
        """{"type":"tool_result","id":"ev-4","tool_id":"tool-1","name":"bash","status":"success","result":"3 warnings"}"""
    private val llmCall2 = """{"type":"llm_call","id":"trace-2","model":"gemini-2.5-flash","status":"running"}"""
    private val llmResult2 =
        """{"type":"llm_result","id":"trace-2","status":"success","model":"gemini-2.5-flash",""" +
            """"duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071}}"""
    private val assistant = """{"type":"assistant","id":"ev-7","content":"$reply"}"""

    private val finishedSession =
        """{"session_id":"SID","turn":1,"messages":[""" +
            """{"role":"user","content":"$question"},{"role":"assistant","content":"$reply"}]}"""

    private fun output(sid: String) =
        """{"type":"OUTPUT","result":"$reply","session_id":"$sid","duration_ms":2400,""" +
            """"session":${finishedSession.replace("SID", sid)}}"""

    /** The rest of the turn, after the tool call the relay failed during. */
    private fun finishTurn(conversation: String) {
        relay.emit(toolResult)
        relay.emit(llmCall2)
        relay.emit(llmResult2)
        relay.emit(assistant)
        relay.finish(output(conversation), finishedSession.replace("SID", conversation))
    }

    // ── driving ─────────────────────────────────────────────────────────

    /**
     * Keep the relay answering, and the reconnect clock moving, until
     * [condition] holds. Each tick is [TICK_MS] of virtual time, so a rung's
     * backoff passes in a fraction of a second, but in steps small enough for
     * the relay to answer between them.
     */
    private suspend fun pumpUntil(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            relay.pump()
            if (condition()) return
            scheduler.advanceTimeBy(TICK_MS)
            scheduler.runCurrent()
            delay(20)
        }
        fail("timed out after ${timeoutMs}ms waiting for: $what")
    }

    /** Keep the relay answering for [virtualMs] of the reconnect clock, whatever happens. */
    private suspend fun pumpFor(virtualMs: Long) {
        repeat((virtualMs / TICK_MS).toInt()) {
            relay.pump()
            scheduler.advanceTimeBy(TICK_MS)
            scheduler.runCurrent()
            delay(20)
        }
    }

    private suspend fun App.rows(sessionId: String) = messages.getMessagesListBySession(sessionId)

    /** The agent is reached through the relay, a question is sent, and the turn is streaming a tool call. */
    private suspend fun connectedMidTurn(): Pair<App, String> {
        val app = App().also { this.app = it }
        app.agents.createAgent(
            AgentProfile(
                id = "agent-1", address = agent, name = "Agent",
                serverUrl = DEFAULT_SERVER_URL, createdAt = 0L
            )
        )
        app.vm.connectToAgent(agent)
        pumpUntil("the app to connect through the relay") { relay.connects.size == 1 && app.connection.isConnected() }

        app.vm.sendMessage(question)
        pumpUntil("INPUT to reach the Host") { relay.inputs.size == 1 }
        val conversation = relay.connects.single().string("session_id")
            ?: error("a conversation with an id of its own must CONNECT on it")
        pumpUntil("the question to be saved") {
            runBlocking { app.rows(conversation) }.any { it.role == Role.USER }
        }

        relay.emit(llmCall1)
        relay.emit(llmResult1)
        relay.emit(toolCall)
        pumpUntil("the running tool call to render") {
            app.items.any { it is ChatItem.ToolCall && it.status == ToolStatus.RUNNING }
        }
        return app to conversation
    }

    private val liveSocket: FakeOipSocket get() = relay.sockets.last { !it.dead }

    /**
     * The reconnect that got through: back on the relay, on the same
     * conversation, naming the last event it rendered (the tool call).
     */
    private fun assertResumedThroughRelay(conversation: String) {
        assertTrue(
            "every socket must be dialled at the relay: ${relay.requests.map { it.url }}",
            relay.requests.all { it.url.isHttps && it.url.host == RELAY_HOST && it.url.encodedPath == "/ws/input" }
        )
        val resume = relay.connects.last()
        assertEquals("the reconnect must resume the conversation's own session", conversation, resume.string("session_id"))
        assertEquals("the reconnect must name the last event it handled", "ev-3", resume.string("last_msg_id"))
    }

    private suspend fun finishAndAssertArrivedOnce(app: App, conversation: String) {
        finishTurn(conversation)
        pumpUntil("the reply to be saved") {
            runBlocking { app.rows(conversation) }.any { it.role == Role.ASSISTANT && it.content == reply }
        }
        assertTurnArrivedOnce(app, conversation)
        assertTrue(
            "the rest of the turn must have come over a new relay connection: ${relay.connIdsUsed}",
            relay.connIdsUsed.size >= 2
        )
        pumpUntil("the connection to settle on Connected") { app.state is ConnectionState.Connected }
    }

    // ── the tests ───────────────────────────────────────────────────────

    @Test
    fun `the relay socket dies mid-turn and the app resumes through the relay with nothing lost or doubled`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.drop(liveSocket)

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay restarts mid-turn and the app resumes after its close frame`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1012, reason = "Service Restart")

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay going away mid-turn is a restart too`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1001, reason = "Going Away")

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay is down for three dials and the ladder backs off until it is back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val dialsBefore = relay.dialledAt.size
        relay.refuseUpgrades = 3

        val droppedAt = scheduler.currentTime
        relay.drop(liveSocket)

        pumpUntil("the fourth dial to CONNECT") { relay.connects.size == 2 }
        assertEquals("three refused upgrades, then the one that got through", dialsBefore + 4, relay.dialledAt.size)
        val times = listOf(droppedAt) + relay.dialledAt.drop(dialsBefore)
        val gaps = times.zipWithNext { a, b -> b - a }
        gaps.forEachIndexed { i, gap ->
            val rung = AgentConnection.reconnectBackoffMs(i + 1)
            assertTrue(
                "dial ${i + 1} must wait out its ${rung}ms rung (and not much more): gaps were $gaps",
                gap in rung until rung + SLACK_MS
            )
        }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay is back before the Host and the app keeps trying until the Host re-announces`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.agentAway = 2

        relay.drop(liveSocket)

        pumpUntil("the Host to be reached again") { relay.connects.size == 2 }
        assertEquals("both CONNECTs before the Host was back were turned away", 2, relay.connectsTurnedAway)
        assertResumedThroughRelay(conversation)

        finishAndAssertArrivedOnce(app, conversation)

        assertTrue(
            "the relay's offline error must not be left in the conversation: ${app.items.map { it.agentText() }}",
            app.items.none { it.agentText()?.contains("offline", ignoreCase = true) == true }
        )
    }

    @Test
    fun `the Host loses the relay mid-turn and the app resumes once it is back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val socket = liveSocket

        // The relay says nothing when the Host goes; the app finds out from
        // the answer to its PONG.
        relay.hostGone = true
        relay.ping(socket)
        pumpUntil("the relay to drop the socket") { socket.dead }
        // The Host's own supervisor re-announces within a second, well inside
        // the app's first 2 s rung; its turn kept running throughout.
        relay.hostGone = false

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)

        knownIssue(
            "the relay's \"Agent not connected\" on a live socket mid-turn is added to the conversation as a " +
                "reply (\"This agent is offline…\") and stays there after the turn resumes and finishes"
        ) {
            assertTrue(
                "the relay's offline error must not be left in the conversation: ${app.items.map { it.agentText() }}",
                app.items.none { it.agentText()?.contains("offline", ignoreCase = true) == true }
            )
        }
    }

    @Test
    fun `the relay is down longer than the ladder and the app resumes when the network comes back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.refuseUpgrades = Int.MAX_VALUE

        relay.drop(liveSocket)

        pumpUntil("the ladder to give up") { app.state is ConnectionState.Disconnected || app.state is ConnectionState.Error }
        assertEquals("the app stops after its last rung", 1, relay.connects.size)

        // The relay is back; the phone tells the app its network is too.
        relay.refuseUpgrades = 0
        online.value = false
        pumpUntil("the offline flag to reach the app") { true }
        online.value = true

        pumpUntil("the retry to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `a turn that finished while the relay was down arrives once from the Host's newer session`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.refuseUpgrades = 1

        relay.drop(liveSocket)
        relay.finishUnheard(finishedSession.replace("SID", conversation))

        pumpUntil("the reply to be saved") {
            runBlocking { app.rows(conversation) }.any { it.role == Role.ASSISTANT && it.content == reply }
        }
        assertEquals(conversation, relay.connects.last().string("session_id"))
        val rows = app.rows(conversation)
        assertEquals("the question must survive, exactly once", listOf(question), rows.filter { it.role == Role.USER }.map { it.content })
        assertEquals("the Host's copy of the reply must be saved exactly once", 1, rows.count { it.role == Role.ASSISTANT && it.content == reply })
        pumpUntil("the reply to render") { app.items.any { it.agentText() == reply } }
        assertEquals(1, app.items.count { it.agentText() == reply })
        assertEquals(1, app.items.count { it is ChatItem.User && it.content == question })

        // The tool call streaming when the relay failed never gets its
        // tool_result; CONNECTED saying the turn is over must settle it.
        pumpUntil("the screen to settle", timeoutMs = 2_000L) { app.items.none { it.isInProgress() } }
    }

    @Test
    fun `a normal close from the relay mid-turn waits for the user to reconnect`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1000, reason = "")

        // A 1000 close is taken as deliberate: no retry on its own, as the
        // ConnectOnion web client does after any close.
        pumpUntil("the app to show it is disconnected") { app.state is ConnectionState.Disconnected }
        pumpFor(AgentConnection.reconnectBackoffMs(1) * 2)
        assertEquals("nothing may redial before the user asks", 1, relay.connects.size)

        // The user taps Reconnect.
        app.vm.connectToAgent(agent)
        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertEquals("the reconnect must resume the conversation's own session", conversation, relay.connects.last().string("session_id"))
        finishAndAssertArrivedOnce(app, conversation)
    }

    /**
     * Room holds the turn exactly once — the question, the first llm call's
     * footer, the tool call (finished), the reply — and the screen shows each
     * of them once with nothing left spinning.
     */
    private suspend fun assertTurnArrivedOnce(app: App, conversation: String) {
        val rows = app.rows(conversation)
        assertEquals(
            "every row of the turn must be saved exactly once: ${rows.map { it.id }}",
            rows.size,
            rows.map { it.id }.toSet().size
        )
        assertEquals(
            "the question must survive, exactly once",
            listOf(question),
            rows.filter { it.role == Role.USER }.map { it.content }
        )
        assertEquals(
            "the reply must be saved exactly once",
            1,
            rows.count { it.role == Role.ASSISTANT && it.content == reply }
        )
        assertTrue("the first llm call's footer must survive", rows.any { it.id == "trace-1" })
        val savedTool = rows.single { it.id == "tool-1" }.payload?.let(TranscriptItemCodec::decode)
        assertEquals(
            "the replayed tool_result must reach the saved tool call",
            ToolStatus.DONE,
            (savedTool as? ChatItem.ToolCall)?.status
        )

        pumpUntil("the turn to settle on screen") { app.items.none { it.isInProgress() } }
        val items = app.items
        assertEquals(1, items.count { it is ChatItem.User && it.content == question })
        assertEquals("the reply must render once", 1, items.count { it.agentText() == reply })
        assertEquals(
            "the tool call must render once, finished",
            listOf(ToolStatus.DONE),
            items.filterIsInstance<ChatItem.ToolCall>().map { it.status }
        )
    }

    private fun ChatItem.agentText(): String? = when (this) {
        is ChatItem.Agent -> content
        is ChatItem.Turn -> agent?.content
        else -> null
    }

    private companion object {
        const val DB_NAME = "relay-failover-test.db"

        /**
         * The socket must be dialled at `wss://oo.openonion.ai/ws/input`. OkHttp
         * stores a `wss://` URL as `https://`, so the parts are compared instead.
         */
        const val RELAY_HOST = "oo.openonion.ai"

        /** Relay HTTP lookups (published profile, skills); unresolvable, so nothing leaves the JVM. */
        const val DIRECTORY = "ws://test.invalid"

        /** Virtual time per pump tick. */
        const val TICK_MS = 250L

        /** How late a dial may be after its rung: a couple of ticks for the relay to answer. */
        const val SLACK_MS = 3 * TICK_MS

        /** Deadlock guard; a correct run is well inside it. */
        const val TIMEOUT_MS = 15_000L
    }
}
