package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.repository.ConnectionRepository
import ai.openonion.oochat.data.repository.ConnectionRepositoryImpl
import ai.openonion.oochat.data.repository.SessionStore
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the duplicate-connection bug: LoadingViewModel's probe
 * and ChatViewModel each held their own [ConnectToAgentUseCase], so one agent
 * got two sockets, two server sessions and two reconnect loops — and whichever
 * session won the race into `session_states` decided what the resume feature
 * replayed. The container now hands both screens the same instance, so these
 * assert what "the same instance" has to buy.
 *
 * Same seam as [ai.openonion.oochat.data.repository.ConnectionRepositoryImplTest]:
 * a real [AgentConnection] over a fake [WebSocketFactory], with the fake server
 * minting a distinct session id per socket so a second socket is visible as a
 * second session rather than only as a second `create()` call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedConnectionTest {

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
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

    private class RecordingSessionStore : SessionStore {
        val saved = CopyOnWriteArrayList<String>()
        override suspend fun saveSession(conversationId: String, session: SessionState) {
            session.sessionId?.let { saved += it }
        }
        override suspend fun getSession(conversationId: String): SessionState? = null
        override suspend fun deleteSessionByConversation(conversationId: String) {}
        override suspend fun deleteOrphanedSessions(): Int = 0
    }

    private val agent = "0x1234567890123456789012345678901234567890"
    private val otherAgent = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"

    private lateinit var keyManager: KeyManager
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var sessionStore: RecordingSessionStore
    private lateinit var useCase: ConnectToAgentUseCase

    @Before
    fun setUp() {
        keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()
        sessionStore = RecordingSessionStore()
        useCase = ConnectToAgentUseCase(
            keyManager = keyManager,
            connectionTimeoutMs = 10_000L,
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

    /**
     * The fake server's CONNECTED reply, one distinct session id per socket.
     * Re-sent by [pumpUntil] until it lands: the repository subscribes to
     * `AgentConnection.events` (replay = 0) on its own IO scope, so an early
     * emission is lost rather than delayed.
     */
    private fun answerConnected() {
        factory.sockets.forEachIndexed { index, (listener, ws) ->
            listener.onMessage(
                ws,
                """{"type":"CONNECTED","session_id":"server-session-$index",""" +
                    """"session":{"session_id":"server-session-$index","turn":1,""" +
                    """"messages":[{"role":"assistant","content":"replayed reply $index"}]}}"""
            )
        }
    }

    private suspend fun pumpUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            answerConnected()
            delay(20)
        }
        // Every caller then awaits a Deferred, so returning quietly here hangs
        // the test on a never-completing await rather than reporting anything.
        if (!condition()) fail("timed out after ${timeoutMs}ms waiting for the pumped condition")
    }

    @Test
    fun `two callers on the shared use case produce one socket and one server session`() = runBlocking {
        // The probe (LoadingViewModel) connects first...
        val probe = async(Dispatchers.Default) { useCase.connect(agent, "conv-1") }
        pumpUntil { probe.isCompleted }
        assertTrue("probe connect must succeed", probe.await())

        // ...then ChatViewModel connects to the same agent AND the same
        // conversation, as it does on every cold start once LoadingScreen
        // hands off.
        val chat = async(Dispatchers.Default) { useCase.connect(agent, "conv-1") }
        pumpUntil { chat.isCompleted }

        assertTrue("second connect must join the live connection", chat.await())
        assertEquals("one agent must mean one WebSocket", 1, factory.createCount)
        assertEquals(
            "one conversation must mean one server session",
            setOf("server-session-0"),
            sessionStore.saved.toSet()
        )
    }

    @Test
    fun `connecting to a different agent still replaces the connection`() = runBlocking {
        val first = async(Dispatchers.Default) { useCase.connect(agent) }
        pumpUntil { first.isCompleted }
        first.await()

        val second = async(Dispatchers.Default) { useCase.connect(otherAgent) }
        pumpUntil { second.isCompleted }
        second.await()

        // The join above must be scoped to "same agent, still live" — switching
        // agents has to build a new socket, not silently keep talking to the old one.
        assertEquals(2, factory.createCount)
    }

    @Test
    fun `a subscriber attaching after CONNECTED still receives the server transcript`() = runBlocking {
        // ChatViewModel is constructed only once LoadingScreen navigates away,
        // i.e. after the shared connection already consumed its CONNECTED frame.
        // ServerTranscriptReceived is what conversation-resume reconciles
        // against, so it is replayed rather than emitted once and lost.
        val probe = async(Dispatchers.Default) { useCase.connect(agent) }
        pumpUntil { probe.isCompleted }
        probe.await()

        val transcript = withTimeout(3_000L) {
            useCase.observeEvents().first { it is ChatEvent.ServerTranscriptReceived }
        } as ChatEvent.ServerTranscriptReceived

        assertEquals(listOf("replayed reply 0"), transcript.entries.map { it.content })
        assertEquals(1, transcript.turn)
    }

    @Test
    fun `concurrent first callers build at most one repository`() {
        // getOrCreateRepository() is reached from several ViewModels now, so
        // "at most one repository" can't rely on every caller being on the main
        // thread. The slow factory widens the window an unsynchronised
        // check-then-assign would lose in.
        val built = CopyOnWriteArrayList<ConnectionRepository>()
        val slowUseCase = ConnectToAgentUseCase(
            keyManager = keyManager,
            repositoryFactory = {
                Thread.sleep(50)
                ConnectionRepositoryImpl(keyManager, "ws://test.invalid").also { built += it }
            }
        )

        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                start.await()
                slowUseCase.observeEvents()
                slowUseCase.connectionState
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(1, built.size)
    }
}
