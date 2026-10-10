package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.ConnectOnionApplication
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
import ai.openonion.oochat.network.WebSocketFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The app process dies while a reply is streaming, and the next launch must
 * resume that turn with nothing lost and nothing doubled.
 *
 * CI has no emulator, so the kill is modelled rather than performed: every
 * object the process held — ViewModel, connection, socket, Room handle — is
 * abandoned without a close frame, a flush or an `onCleared`, and the next
 * launch is rebuilt from scratch on the same database *file*. The disk is
 * the only thing a real process death leaves behind, so it is the only thing
 * shared between the two "processes" here. Both are wired from the
 * production classes [ai.openonion.oochat.di.DefaultAppContainer] uses; only
 * the socket is fake.
 *
 * The fake Host replays the way `connectonion`'s does: each turn has its own
 * event buffer (a fresh `WebSocketIO` per INPUT), and a CONNECT for a
 * `running` session restarts forwarding just after `last_msg_id`, or from the
 * start of the turn when that id is absent or unknown
 * (`WebSocketIO.position_after`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessDeathResumeTest {

    /** One socket the app opened. Once [dead], the Host never hears from it again. */
    private class HostSocket(val listener: WebSocketListener) : WebSocket {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var opened = false
        @Volatile var dead = false
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws/input").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (dead) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = !dead
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    /**
     * The Host's side of OIP, reduced to what resume depends on: answer
     * CONNECT, record INPUT, and keep the running turn's events so a
     * reattaching client can be replayed from its cursor.
     */
    private class FakeHost : WebSocketFactory {
        /**
         * Frames reach the app on one background thread, in order, as OkHttp's
         * reader thread delivers them. Robolectric's test thread is the main
         * thread, and the app rightly refuses to touch Room from there.
         */
        private val reader = Executors.newSingleThreadExecutor()
        val sockets = CopyOnWriteArrayList<HostSocket>()
        val connects = CopyOnWriteArrayList<JsonObject>()
        val inputs = CopyOnWriteArrayList<JsonObject>()
        private val answered = mutableSetOf<String>()

        /** This turn's events, in order — the Host's `_msgs_from_agent`. */
        private val turnEvents = mutableListOf<String>()
        var running = false
            private set

        /** The transcript the Host holds once the turn has finished. */
        var finishedSession: String? = null

        override fun create(request: Request, listener: WebSocketListener): WebSocket =
            HostSocket(listener).also { sockets += it }

        private val live: HostSocket? get() = sockets.lastOrNull { !it.dead }

        /** Open new sockets and answer every CONNECT and INPUT not yet seen. */
        fun pump() {
            for (socket in sockets) {
                if (socket.dead) continue
                if (!socket.opened) {
                    socket.opened = true
                    deliver { socket.listener.onOpen(socket, switchingProtocols()) }
                }
                for (text in socket.sent) {
                    if (!answered.add(text)) continue
                    val frame = Json.parseToJsonElement(text).jsonObject
                    when (frame.string("type")) {
                        "CONNECT" -> answerConnect(socket, frame)
                        "INPUT" -> {
                            inputs += frame
                            turnEvents.clear()
                            running = true
                        }
                    }
                }
            }
        }

        private fun answerConnect(socket: HostSocket, frame: JsonObject) {
            connects += frame
            val sid = frame.string("session_id") ?: "host-minted-${connects.size}"
            val finished = finishedSession
            val reply = when {
                running -> """{"type":"CONNECTED","session_id":"$sid","status":"running"}"""
                // The turn ended while the client was gone: the Host's copy is
                // newer, so it sends it (merge_sessions → server_newer).
                finished != null ->
                    """{"type":"CONNECTED","session_id":"$sid","status":"connected",""" +
                        """"server_newer":true,"session":$finished}"""
                else -> """{"type":"CONNECTED","session_id":"$sid","status":"new"}"""
            }
            deliver(socket, reply)
            if (running) {
                // resume_forwarding: start just past last_msg_id, else replay all.
                val cursor = frame.string("last_msg_id")
                val start = turnEvents.indexOfFirst { idOf(it) == cursor }.let { if (it < 0) 0 else it + 1 }
                turnEvents.drop(start).forEach { deliver(socket, it) }
            }
        }

        /** The agent emits [frame]: buffered for replay, forwarded if anyone is attached. */
        fun emit(frame: String) {
            turnEvents += frame
            live?.let { deliver(it, frame) }
        }

        /** OUTPUT: the turn is over and its transcript becomes the session's. */
        fun finish(output: String, session: String) {
            running = false
            finishedSession = session
            live?.let { deliver(it, output) }
        }

        /** The socket fails as a lost network does: no close frame, just an error. */
        fun drop(socket: HostSocket) {
            socket.dead = true
            deliver { socket.listener.onFailure(socket, java.io.IOException("Software caused connection abort"), null) }
        }

        private fun deliver(socket: HostSocket, frame: String) = deliver { socket.listener.onMessage(socket, frame) }

        private fun deliver(call: () -> Unit) {
            reader.execute(call)
        }

        fun shutdown() {
            reader.shutdownNow()
        }

        /** The turn ends with nobody attached to hear it. */
        fun finishUnheard(session: String) {
            running = false
            finishedSession = session
        }

        private fun idOf(frame: String): String? =
            Json.parseToJsonElement(frame).jsonObject.string("id")

        private fun switchingProtocols(): Response = Response.Builder()
            .request(Request.Builder().url("wss://fake.example.com/ws/input").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
    }

    /**
     * Everything one launch of the app holds in memory, wired as
     * DefaultAppContainer wires it, over the database file at [DB_NAME].
     */
    private inner class AppProcess {
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
            relayUrl = RELAY,
            connectionTimeoutMs = 10_000L,
            sessionStore = sessionStore,
            pendingMessageSink = outbox,
            repositoryFactory = {
                ConnectionRepositoryImpl(
                    keyManager = keyManager,
                    relayUrl = RELAY,
                    sessionStore = sessionStore,
                    pendingMessageSink = outbox,
                    agentConnectionFactory = { km, url ->
                        AgentConnection(
                            km, url, host,
                            signer = { _, _ -> "fake-signature" },
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
            networkMonitor = alwaysOnline,
            isAppInForeground = { true }
        )

        val items: List<ChatItem> get() = vm.uiState.value.chatItems

        /**
         * Process death: the Host's socket goes silent with no close frame,
         * and nothing this process still has queued ever reaches the disk.
         * Its objects are simply never used again.
         */
        fun kill() {
            host.sockets.forEach { it.dead = true }
            db.close()
        }
    }

    private val alwaysOnline = object : NetworkMonitor {
        override val isOnline = flowOf(true)
    }

    private lateinit var application: ConnectOnionApplication
    private lateinit var keyManager: KeyManager
    private lateinit var host: FakeHost
    private lateinit var main: ExecutorService
    private val processes = mutableListOf<AppProcess>()

    private val agent = "0x" + "ab".repeat(32)
    private val question = "What's in the logs?"
    private val reply = "Nothing unusual: three warnings, no errors."

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.deleteDatabase(DB_NAME)
        // A real thread, not a test dispatcher: the Host is driven from the
        // test thread while the ViewModel's coroutines run, as on a device.
        main = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(main.asCoroutineDispatcher())
        keyManager = KeyManager(application)
        keyManager.save(TestIdentity.keys())
        host = FakeHost()
    }

    @After
    fun tearDown() {
        host.shutdown()
        processes.forEach { if (it.db.isOpen) it.db.close() }
        Dispatchers.resetMain()
        main.shutdownNow()
        application.deleteDatabase(DB_NAME)
    }

    private fun launch(): AppProcess = AppProcess().also { processes += it }

    // ── the turn, as the Host streams it ────────────────────────────────

    // Ids as the Host stamps them: llm_call/llm_result share the trace id,
    // and io.send gives every other event a UUID of its own.
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

    /** The rest of the turn, after the tool call the app was killed during. */
    private fun finishTurn(conversation: String) {
        host.emit(toolResult)
        host.emit(llmCall2)
        host.emit(llmResult2)
        host.emit(assistant)
        host.finish(output(conversation), finishedSession.replace("SID", conversation))
    }

    // ── driving ─────────────────────────────────────────────────────────

    /** Keep the Host answering until [condition] holds. */
    private suspend fun pumpUntil(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            host.pump()
            if (condition()) return
            delay(20)
        }
        fail("timed out after ${timeoutMs}ms waiting for: $what")
    }

    private suspend fun AppProcess.rows(sessionId: String) = messages.getMessagesListBySession(sessionId)

    private suspend fun awaitReplySaved(app: AppProcess, conversation: String) =
        pumpUntil("the reply to be saved") {
            runBlocking { app.rows(conversation) }.any { it.role == Role.ASSISTANT && it.content == reply }
        }

    /** First launch: the agent is saved, a question is sent, the turn starts streaming. */
    private suspend fun firstLaunchMidTurn(): Pair<AppProcess, String> {
        val first = launch()
        first.agents.createAgent(
            AgentProfile(
                id = "agent-1", address = agent, name = "Agent",
                serverUrl = "https://oo.openonion.ai", createdAt = 0L
            )
        )
        first.vm.connectToAgent(agent)
        pumpUntil("the first launch to connect") { host.connects.size == 1 && first.connection.isConnected() }

        first.vm.sendMessage(question)
        pumpUntil("INPUT to reach the Host") { host.inputs.size == 1 }
        val conversation = host.connects.single().string("session_id")
            ?: error("a conversation with an id of its own must CONNECT on it")
        pumpUntil("the question to be saved") {
            runBlocking { first.rows(conversation) }.any { it.role == Role.USER }
        }

        host.emit(llmCall1)
        host.emit(llmResult1)
        host.emit(toolCall)
        pumpUntil("the running tool call to render") {
            first.items.any { it is ChatItem.ToolCall && it.status == ToolStatus.RUNNING }
        }
        return first to conversation
    }

    // ── the tests ───────────────────────────────────────────────────────

    @Test
    fun `killed mid-stream, the next launch resumes the turn with nothing lost or doubled`() = runBlocking {
        val (first, conversation) = firstLaunchMidTurn()

        // Why the cursor must not outlive the process: a running turn reaches
        // Room piecemeal, so the relaunch cannot claim to have rendered it.
        assertTrue(
            "precondition: the streaming turn is on screen",
            first.items.any { it.isInProgress() }
        )
        assertTrue(
            "precondition: the turn's reply is not yet in Room",
            first.rows(conversation).none { it.role == Role.ASSISTANT }
        )

        first.kill()

        val second = launch()
        second.vm.connectToAgent(agent)
        pumpUntil("the relaunch to CONNECT") { host.connects.size == 2 }

        val resume = host.connects.last()
        assertEquals(
            "the relaunch must resume the conversation's own server session",
            conversation,
            resume.string("session_id")
        )
        assertNull(
            "a relaunch has rendered none of the in-flight turn, so it must ask for all of it",
            resume.string("last_msg_id")
        )

        // The agent carries on; the Host has already replayed llm_call,
        // llm_result and tool_call from the start of the turn.
        pumpUntil("the replayed tool call to render") {
            second.items.any { it is ChatItem.ToolCall && it.id == "tool-1" }
        }
        finishTurn(conversation)
        awaitReplySaved(second, conversation)

        assertTurnArrivedOnce(second, conversation)
    }

    @Test
    fun `a dropped network resumes from the last event it rendered`() = runBlocking {
        val (app, conversation) = firstLaunchMidTurn()

        // Same process, socket lost: everything streamed so far is still on
        // screen, so only what came after it should be sent again.
        host.drop(host.sockets.last())

        pumpUntil("the reconnect to CONNECT") { host.connects.size == 2 }
        val resume = host.connects.last()
        assertEquals(conversation, resume.string("session_id"))
        assertEquals(
            "the reconnect must name the last event it handled",
            "ev-3",
            resume.string("last_msg_id")
        )

        finishTurn(conversation)
        awaitReplySaved(app, conversation)

        assertTurnArrivedOnce(app, conversation)
    }

    @Test
    fun `a turn that finished while the app was dead arrives once from the Host's newer session`() = runBlocking {
        val (first, conversation) = firstLaunchMidTurn()
        first.kill()
        host.finishUnheard(finishedSession.replace("SID", conversation))

        val second = launch()
        second.vm.connectToAgent(agent)
        awaitReplySaved(second, conversation)

        assertEquals(conversation, host.connects.last().string("session_id"))
        val rows = second.rows(conversation)
        assertEquals(
            "the question must survive, exactly once",
            listOf(question),
            rows.filter { it.role == Role.USER }.map { it.content }
        )
        assertEquals(
            "the Host's copy of the reply must be saved exactly once",
            1,
            rows.count { it.role == Role.ASSISTANT && it.content == reply }
        )
        assertEquals(1, second.items.count { it.agentText() == reply })
        assertEquals(1, second.items.count { it is ChatItem.User && it.content == question })
    }

    @Test
    fun `a tool call running when the app died stops spinning once the Host says the turn is over`() = runBlocking {
        val (first, conversation) = firstLaunchMidTurn()
        first.kill()
        host.finishUnheard(finishedSession.replace("SID", conversation))

        val second = launch()
        second.vm.connectToAgent(agent)
        awaitReplySaved(second, conversation)

        knownIssue(
            "the tool call is saved as RUNNING mid-turn, and a turn that ends while the app is dead never " +
                "sends its tool_result, so it spins forever (CONNECTED status is not read)"
        ) {
            // Short: the reply is already saved, so the screen has nothing left to wait for.
            pumpUntil("the screen to settle", timeoutMs = 2_000L) { second.items.none { it.isInProgress() } }
        }
    }

    /**
     * Room holds the turn exactly once — the question, the first llm call's
     * footer, the tool call (finished), the reply — and the screen shows each
     * of them once with nothing left spinning. Room is the check for loss:
     * it is what the next launch is rebuilt from.
     */
    private suspend fun assertTurnArrivedOnce(app: AppProcess, conversation: String) {
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
            "a replayed tool call must fold into the one already shown",
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
        const val DB_NAME = "process-death-resume-test.db"
        const val RELAY = "ws://test.invalid"

        /** Deadlock guard; a correct run is well inside it. */
        const val TIMEOUT_MS = 15_000L

        fun JsonObject.string(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
