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
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.domain.usecase.PersistenceTransaction
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
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.io.File
import java.net.ProtocolException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What [RelayFailoverTest] and [NetworkDropTest] share: the app wired from the
 * production classes as in [ProcessDeathResumeTest], a fake relay at
 * `wss://oo.openonion.ai/ws/input` in front of [FakeOipHost], a turn streaming
 * a tool call, and the checks that it resumes with nothing lost or doubled.
 * The reconnect ladder runs on a virtual clock, so its 2-32 s rungs cost no
 * real time and their spacing can be checked.
 */
abstract class RelayResumeTestBase {

    /**
     * The relay in front of the Host. It forwards frames both ways and stamps
     * each client socket's frames with a `conn_id` of its own, as it does for
     * a Host that announced `relay_features: ["conn_id"]`. The relay's own
     * failures are switched on per socket.
     */
    protected inner class FakeRelay : FakeOipHost() {
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

        /**
         * Set while the phone has no network: every dial fails before reaching
         * the relay, with no response, as OkHttp reports a failed DNS lookup.
         */
        @Volatile var dialFailure: Throwable? = null

        override fun open(socket: FakeOipSocket) {
            dialFailure?.let { cause ->
                socket.dead = true
                deliver { socket.listener.onFailure(socket, cause, null) }
                return
            }
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

        /** Every agent event the relay forwarded, as (connection, event id), in order. */
        val forwarded = CopyOnWriteArrayList<Pair<String, String?>>()

        override fun onTheWire(socket: FakeOipSocket, frame: String): String {
            val connId = connIds[socket] ?: return frame
            val fields = Json.parseToJsonElement(frame).jsonObject.toMutableMap()
            forwarded += connId to (fields["id"] as? JsonPrimitive)?.content
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

        /** The socket fails with [cause] and no close frame, as OkHttp reports it. */
        fun fail(socket: FakeOipSocket, cause: Throwable) {
            socket.dead = true
            deliver { socket.listener.onFailure(socket, cause, null) }
        }

        val connIdsUsed: Set<String> get() = connIds.values.toSet()

        private fun badGateway(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(502)
            .message("Bad Gateway")
            .build()
    }

    /**
     * The connection as the screen sees it, counting connect calls that have
     * returned. A connect call decides it failed by sampling the state, so
     * breaking the relay before it has returned turns the break into a failed
     * first attempt and a fresh connection.
     */
    protected class CountedConnect(
        private val inner: ConnectToAgentUseCase
    ) : ConnectToAgentUseCaseContract by inner {
        val finished = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?): Boolean =
            inner.connect(agentAddress, conversationId, directUrl).also { finished.incrementAndGet() }
    }

    /** One launch of the app, wired as DefaultAppContainer wires it. */
    protected inner class App {
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
        val connectCalls = CountedConnect(connection)
        val vm = ChatViewModel(
            application = application,
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectCalls,
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

    /**
     * The phone's network. A SharedFlow, not a StateFlow, so the screen sees
     * every change: a StateFlow could hand it only the last of an off-on pair.
     */
    protected val online = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(true) }
    private val network = object : NetworkMonitor {
        override val isOnline = online
    }

    private lateinit var application: ConnectOnionApplication
    private lateinit var keyManager: KeyManager
    protected lateinit var relay: FakeRelay
    private lateinit var main: ExecutorService
    protected lateinit var scheduler: TestCoroutineScheduler
    private var app: App? = null

    protected val agent = "0x" + "ab".repeat(32)
    protected val question = "What's in the logs?"
    protected val reply = "Nothing unusual: three warnings, no errors."

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

    protected val finishedSession =
        """{"session_id":"SID","turn":1,"messages":[""" +
            """{"role":"user","content":"$question"},{"role":"assistant","content":"$reply"}]}"""

    private fun output(sid: String) =
        """{"type":"OUTPUT","result":"$reply","session_id":"$sid","duration_ms":2400,""" +
            """"session":${finishedSession.replace("SID", sid)}}"""

    /**
     * The agent finishes the tool call while the app cannot hear it: the Host
     * buffers the tool_result for replay, and the relay has nowhere to send it.
     */
    protected fun missWhileDown() {
        assertTrue("the frame must be missed, so no socket may reach the app", relay.sockets.all { it.dead || it.unreachable })
        connsBeforeMiss = relay.connIdsUsed
        relay.emit(toolResult)
    }

    private var connsBeforeMiss: Set<String> = emptySet()

    /** The rest of the turn, sent live once the app is back. */
    private fun finishTurn(conversation: String) {
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
    protected suspend fun pumpUntil(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
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
    protected suspend fun pumpFor(virtualMs: Long) {
        repeat((virtualMs / TICK_MS).toInt()) {
            relay.pump()
            scheduler.advanceTimeBy(TICK_MS)
            scheduler.runCurrent()
            delay(20)
        }
    }

    protected suspend fun App.rows(sessionId: String) = messages.getMessagesListBySession(sessionId)

    /** The agent is reached through the relay, a question is sent, and the turn is streaming a tool call. */
    protected suspend fun connectedMidTurn(): Pair<App, String> {
        val app = App().also { this.app = it }
        app.agents.createAgent(
            AgentProfile(
                id = "agent-1", address = agent, name = "Agent",
                serverUrl = DEFAULT_SERVER_URL, createdAt = 0L
            )
        )
        app.vm.connectToAgent(agent)
        pumpUntil("the app to connect through the relay") {
            relay.connects.size == 1 && app.connection.isConnected() && app.connectCalls.finished.get() == 1
        }

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

    protected val liveSocket: FakeOipSocket get() = relay.sockets.last { !it.dead }

    /**
     * The reconnect that got through: back on the relay, on the same
     * conversation, naming the last event it rendered (the tool call).
     */
    protected fun assertResumedThroughRelay(conversation: String) {
        assertTrue(
            "every socket must be dialled at the relay: ${relay.requests.map { it.url }}",
            relay.requests.all { it.url.isHttps && it.url.host == RELAY_HOST && it.url.encodedPath == "/ws/input" }
        )
        val resume = relay.connects.last()
        assertEquals("the reconnect must resume the conversation's own session", conversation, resume.string("session_id"))
        assertEquals("the reconnect must name the last event it handled", "ev-3", resume.string("last_msg_id"))
    }

    /**
     * The tool_result the app missed while the relay was down is replayed on
     * the new connection, once, and lands once in Room and on screen, before
     * any later frame is sent.
     */
    private suspend fun assertMissedFrameReplayedOnce(app: App, conversation: String) {
        pumpUntil("the missed tool_result to be replayed") {
            app.items.any { it is ChatItem.ToolCall && it.id == "tool-1" && it.status == ToolStatus.DONE }
        }
        pumpUntil("the replayed tool_result to be saved") {
            val saved = runBlocking { app.rows(conversation) }.firstOrNull { it.id == "tool-1" }?.payload
            (saved?.let(TranscriptItemCodec::decode) as? ChatItem.ToolCall)?.status == ToolStatus.DONE
        }
        val deliveries = relay.forwarded.filter { it.second == "ev-4" }.map { it.first }
        assertEquals("the missed tool_result must be forwarded exactly once: $deliveries", 1, deliveries.size)
        assertTrue(
            "it must come over a connection opened after it was missed: ${deliveries.single()} vs $connsBeforeMiss",
            deliveries.single() !in connsBeforeMiss
        )
        assertEquals(
            "the replayed tool_result must fold into the one tool call on screen",
            listOf(ToolStatus.DONE),
            app.items.filterIsInstance<ChatItem.ToolCall>().map { it.status }
        )
        assertEquals("the tool call must be saved once", 1, app.rows(conversation).count { it.id == "tool-1" })
    }

    protected suspend fun finishAndAssertArrivedOnce(app: App, conversation: String) {
        assertMissedFrameReplayedOnce(app, conversation)
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

    protected fun ChatItem.agentText(): String? = when (this) {
        is ChatItem.Agent -> content
        is ChatItem.Turn -> agent?.content
        else -> null
    }

    protected companion object {
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
