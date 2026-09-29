package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.ConnectionRepositoryImpl
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.data.repository.SessionStore
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.WebSocketFactory
import ai.openonion.oochat.ui.loading.LoadingOutcome
import ai.openonion.oochat.ui.loading.LoadingViewModel
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Cold start must open exactly one socket and open it on the conversation it
 * is going to resume.
 *
 * The regression this guards: LoadingScreen's probe connected with
 * `conversationId = null`, so the server minted a session nobody would ever
 * use, and ChatScreen's own connect a second later re-pointed the connection
 * at the persisted conversation — a wasted CONNECT signature, an orphaned
 * server session, and a Connected -> Reconnecting -> Connected flicker on
 * every launch.
 *
 * Both halves of the handoff run through production code: the probe is a real
 * [LoadingViewModel], and ChatScreen's conversation comes from a real
 * [ConversationHistoryUseCase], so the test fails if the two ever disagree
 * about which conversation the agent resumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartConnectionTest {

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
        val sockets = CopyOnWriteArrayList<Pair<WebSocketListener, FakeWebSocket>>()
        val createCount: Int get() = sockets.size

        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            val ws = FakeWebSocket()
            sockets += listener to ws
            return ws
        }
    }

    /** Room's `session_states`, keyed by conversation exactly as production is. */
    private class FakeSessionStore : SessionStore {
        val byConversation = mutableMapOf<String, SessionState>()
        override suspend fun saveSession(conversationId: String, session: SessionState) {
            byConversation[conversationId] = session
        }
        override suspend fun getSession(conversationId: String): SessionState? = byConversation[conversationId]
        override suspend fun deleteSessionByConversation(conversationId: String) { byConversation -= conversationId }
        override suspend fun deleteOrphanedSessions(): Int = 0
    }

    private class FakeAgentRepository : AgentRepository {
        val agents = mutableListOf<AgentProfile>()
        override fun getAllAgents(): Flow<List<AgentProfile>> = throw NotImplementedError()
        override fun getActiveAgents(): Flow<List<AgentProfile>> = throw NotImplementedError()
        override suspend fun getAgentById(id: String): AgentProfile? = agents.find { it.id == id }
        override suspend fun getAgentByAddress(address: String): AgentProfile? = agents.find { it.address == address }
        override suspend fun createAgent(agent: AgentProfile): AgentProfile { agents += agent; return agent }
        override suspend fun updateAgent(agent: AgentProfile) {}
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun updateLastConnected(agentId: String) {}
        override suspend fun getDefaultAgent(): AgentProfile? = agents.firstOrNull()
        override suspend fun reorderAgents(orderedIds: List<String>) {}
    }

    private class FakeSessionRepository : SessionRepository {
        val sessions = mutableMapOf<String, ChatSession>()
        private val byAgent = mutableMapOf<String, MutableStateFlow<List<ChatSession>>>()
        private fun flowFor(agentId: String) =
            byAgent.getOrPut(agentId) { MutableStateFlow(emptyList()) }
        private fun republish(agentId: String) {
            flowFor(agentId).value = sessions.values.filter { it.agentId == agentId }
        }

        fun seed(session: ChatSession) {
            sessions[session.id] = session
            republish(session.agentId)
        }

        override fun getAllSessions(): Flow<List<ChatSession>> = throw NotImplementedError()
        override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> = flowFor(agentId)
        override suspend fun getSessionById(id: String): ChatSession? = sessions[id]
        override suspend fun createSession(agentId: String, title: String, id: String): ChatSession =
            ChatSession(id = id, agentId = agentId, title = title, createdAt = 0L, updatedAt = 0L)
                .also { seed(it) }
        override suspend fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)?.let { republish(it.agentId) }
        }
        override suspend fun renameSession(sessionId: String, newTitle: String) {}
        override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {}
    }

    private class FakeMessageRepository : MessageRepository {
        val messages = mutableListOf<ChatMessage>()
        override suspend fun getMessagesListBySession(sessionId: String): List<ChatMessage> =
            messages.filter { it.sessionId == sessionId }
        override suspend fun createMessage(message: ChatMessage) { messages += message }
        override suspend fun deleteMessagesBySession(sessionId: String) {}
        override suspend fun getMessageCount(sessionId: String): Int = 0
        override suspend fun existsById(id: String): Boolean = false
        override suspend fun getOwningSessionId(id: String): String? = null
        override suspend fun getSessionIdByUserContent(content: String): String? = null
    }

    private class FakeConfigRepository(private val agentAddress: String) : ConnectionConfigRepository {
        private val config = ConnectionConfig(
            serverUrl = "https://oo.openonion.ai",
            agentAddress = agentAddress
        )
        override suspend fun getConfig(): ConnectionConfig? = config
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) {}
        override suspend fun deleteConfig() {}
        override suspend fun hasConfig(): Boolean = true
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    private val agent = "0x1234567890123456789012345678901234567890"
    private val resumedConversation = "79da58ad-0000-4000-8000-000000000001"

    private lateinit var keyManager: KeyManager
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var agentRepo: FakeAgentRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var useCase: ConnectToAgentUseCase

    @Before
    fun setUp() {
        // viewModelScope has to run off the test thread: this test drives the
        // fake server by hand from the test thread while the ViewModel's
        // connect is in flight.
        Dispatchers.setMain(Dispatchers.Default)
        keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()
        sessionStore = FakeSessionStore()
        agentRepo = FakeAgentRepository()
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        useCase = ConnectToAgentUseCase(
            keyManager = keyManager,
            connectionTimeoutMs = 15_000L,
            sessionStore = sessionStore,
            repositoryFactory = {
                ConnectionRepositoryImpl(
                    keyManager = keyManager,
                    relayUrl = "ws://test.invalid",
                    sessionStore = sessionStore,
                    agentConnectionFactory = { km, url ->
                        AgentConnection(km, url, factory, signer = { _, _ -> "fake-signature" })
                    }
                )
            }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("wss://fake.example.com/ws").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private fun connectFrames(ws: FakeWebSocket) =
        ws.sentMessages.filter { it.contains("\"type\":\"CONNECT\"") }

    private fun sessionIdOf(connectFrame: String): String? =
        Regex("\"session_id\":\"([^\"]+)\"").find(connectFrame)?.groupValues?.get(1)

    private val openedSockets = mutableSetOf<FakeWebSocket>()
    private val answeredFrames = mutableSetOf<String>()

    /** Every session this fake relay minted for a CONNECT that named none, in order. */
    private val mintedSessions = mutableListOf<String>()

    /**
     * The relay's half of the handshake: open every new socket, then answer
     * each CONNECT with the session it asked for — or a freshly minted one
     * when it asked for none, which is exactly how an unnamed connect earns an
     * orphan session.
     */
    private fun pumpServer() {
        factory.sockets.forEach { (listener, ws) ->
            if (openedSockets.add(ws)) listener.onOpen(ws, fakeResponse())
            connectFrames(ws).forEach { frame ->
                if (!answeredFrames.add(frame)) return@forEach
                val sid = sessionIdOf(frame)
                    ?: "server-minted-${answeredFrames.size}".also { mintedSessions += it }
                listener.onMessage(
                    ws,
                    """{"type":"CONNECTED","session_id":"$sid","session":{"session_id":"$sid","turn":1}}"""
                )
            }
        }
    }

    private suspend fun pumpUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            pumpServer()
            delay(20)
        }
        pumpServer()
        // Every caller then awaits a Deferred, so returning quietly here hangs
        // the test on a never-completing await rather than reporting anything.
        if (!condition()) fail("timed out after ${timeoutMs}ms waiting for the pumped condition")
    }

    private fun loadingViewModel() = LoadingViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        configRepository = FakeConfigRepository(agent),
        connectUseCase = useCase,
        resumableConversation = ResumableConversationUseCase(agentRepo, sessionRepo)
    )

    /** ChatScreen's half of the handoff, driven by the real conversation resolver. */
    private suspend fun chatScreenConnect(
        scope: CoroutineScope,
        history: ConversationHistoryUseCase = chatHistory()
    ): String? {
        history.ensureActiveSession(agent, scope)
        val conversationId = history.conversationId
        useCase.connect(agent, conversationId)
        return conversationId
    }

    private fun chatHistory(): ConversationHistoryUseCase {
        return ConversationHistoryUseCase(
            agentRepo,
            sessionRepo,
            messageRepo,
            FakePersistenceTransaction(messageRepo, sessionRepo),
            // Wired as ChatViewModel wires it, so a conversation with no id of
            // its own adopts the live connection's session here too.
            liveSessionId = { useCase.liveSessionIdFor(it) }
        )
    }

    private fun seedAgent() {
        agentRepo.agents += AgentProfile(
            id = "agent-1",
            address = agent,
            name = "Agent",
            serverUrl = "https://oo.openonion.ai",
            createdAt = 0L
        )
    }

    private fun seedAgentWithHistory() {
        seedAgent()
        sessionRepo.seed(
            ChatSession(
                id = resumedConversation,
                agentId = "agent-1",
                title = "Yesterday's chat",
                createdAt = 0L,
                updatedAt = 10L,
                messageCount = 4
            )
        )
    }

    @Test
    fun `a cold start with history opens one socket on the conversation it resumes`() = runBlocking {
        seedAgentWithHistory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val vm = loadingViewModel()
        vm.start()
        pumpUntil { vm.uiState.value.outcome == LoadingOutcome.CONNECTED }
        assertEquals(
            "the probe must reach Connected",
            LoadingOutcome.CONNECTED,
            vm.uiState.value.outcome
        )

        val chat = async(Dispatchers.Default) { chatScreenConnect(scope) }
        pumpUntil { chat.isCompleted }
        assertEquals(
            "ChatScreen must land on the same conversation the probe connected as",
            resumedConversation,
            chat.await()
        )

        assertEquals(
            "one cold start must mean one socket — the probe's, joined by ChatScreen",
            1,
            factory.createCount
        )
        val frames = factory.sockets.flatMap { (_, ws) -> connectFrames(ws) }
        assertEquals("one socket must mean one CONNECT signature", 1, frames.size)
        assertEquals(
            "the single CONNECT must carry the persisted conversation, not a blank session",
            resumedConversation,
            sessionIdOf(frames.single())
        )
        assertEquals(
            "no session may be persisted other than the resumed conversation's own",
            setOf(resumedConversation),
            sessionStore.byConversation.keys.toSet()
        )
        scope.cancel()
    }

    @Test
    fun `a saved agent with no conversation yet adopts the session the probe was given`() = runBlocking {
        // The common first launch: onboarding wrote the agent row, but nobody
        // has sent a message, so there is nothing to resume. The probe connects
        // unnamed and the server mints a session — and ChatScreen's brand-new
        // conversation has to take that id rather than mint a rival one.
        seedAgent()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val vm = loadingViewModel()
        vm.start()
        pumpUntil { vm.uiState.value.outcome == LoadingOutcome.CONNECTED }
        assertEquals(LoadingOutcome.CONNECTED, vm.uiState.value.outcome)
        assertNull(
            "with nothing to resume the probe's CONNECT carries no session id",
            sessionIdOf(factory.sockets.flatMap { (_, ws) -> connectFrames(ws) }.single())
        )

        val chat = async(Dispatchers.Default) { chatScreenConnect(scope) }
        pumpUntil { chat.isCompleted }

        assertEquals(
            "adopting the live session means joining its socket, not opening a second",
            1,
            factory.createCount
        )
        assertEquals(
            "one socket must mean one CONNECT signature",
            1,
            factory.sockets.flatMap { (_, ws) -> connectFrames(ws) }.size
        )
        assertEquals(
            "the new conversation must be the session the server already minted",
            mintedSessions.single(),
            chat.await()
        )
        assertEquals(
            "the adopted id is the key the session is filed under — conversation id IS session id",
            setOf(mintedSessions.single()),
            sessionStore.byConversation.keys.toSet()
        )
        scope.cancel()
    }

    @Test
    fun `new chat after adopting still gets a session of its own`() = runBlocking {
        // The other half of adoption: it must not leak into the explicit
        // "+ new chat" action, which is exactly the request for a fresh session.
        seedAgent()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val vm = loadingViewModel()
        vm.start()
        pumpUntil { vm.uiState.value.outcome == LoadingOutcome.CONNECTED }

        val history = chatHistory()
        val chat = async(Dispatchers.Default) { chatScreenConnect(scope, history) }
        pumpUntil { chat.isCompleted }
        val adopted = chat.await()

        val started = async(Dispatchers.Default) {
            history.startNewSession()
            history.conversationId.also { useCase.switchConversation(it) }
        }
        pumpUntil { started.isCompleted }
        val fresh = started.await()

        assertNotEquals("+ new chat must not reuse the adopted session", adopted, fresh)
        assertEquals("a genuinely new session means a new socket", 2, factory.createCount)
        assertEquals(
            "the second CONNECT must name the new conversation",
            fresh,
            sessionIdOf(factory.sockets.last().second.let { connectFrames(it) }.single())
        )
        scope.cancel()
    }

    @Test
    fun `a fresh install still connects, on a server-minted session, without waiting`() = runBlocking {
        // Nothing in Room: no agent row, no conversations. The probe must not
        // block waiting for a conversation that will never exist — it connects
        // unnamed and lets the server mint the session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val vm = loadingViewModel()
        vm.start()
        pumpUntil { vm.uiState.value.outcome == LoadingOutcome.CONNECTED }
        assertEquals(LoadingOutcome.CONNECTED, vm.uiState.value.outcome)

        val frames = factory.sockets.flatMap { (_, ws) -> connectFrames(ws) }
        assertNull(
            "with no local history the CONNECT carries no session id",
            sessionIdOf(frames.single())
        )

        val chat = async(Dispatchers.Default) { chatScreenConnect(scope) }
        pumpUntil { chat.isCompleted }
        assertNull("an unknown agent has no conversation to resume", chat.await())
        assertTrue("the connection must still be live", useCase.isConnected())
        assertEquals("ChatScreen joins the probe's socket rather than opening its own", 1, factory.createCount)
        scope.cancel()
    }
}
