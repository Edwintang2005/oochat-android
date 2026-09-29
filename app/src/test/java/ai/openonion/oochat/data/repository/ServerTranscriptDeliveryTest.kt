package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
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

/**
 * That a replayed transcript actually reaches [ConnectionRepository.observeEvents]
 * every time a conversation is connected — including the second time the same
 * conversation is connected, after switching away and back.
 *
 * `_serverTranscript` is a StateFlow, so a value equal to the one already
 * there is silently not emitted. The transcript of a conversation is the same
 * object each time it is replayed, which makes "switch away and back" the
 * exact shape that produces a dropped replay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerTranscriptDeliveryTest {

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class FakeWebSocketFactory : WebSocketFactory {
        val createCount = MutableStateFlow(0)
        @Volatile var lastListener: WebSocketListener? = null
        @Volatile var lastWebSocket: FakeWebSocket? = null
        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            val socket = FakeWebSocket()
            lastWebSocket = socket
            lastListener = listener
            // Published last, so a waiter that sees the new count is also
            // guaranteed to see the listener that belongs to it.
            createCount.update { it + 1 }
            return socket
        }
    }

    private class NoopSink : PendingMessageSink {
        override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?) = Unit
        override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) = Unit
        override fun clear(agentAddress: String) = Unit
    }

    private class MemoryStore : SessionStore {
        val saved = mutableMapOf<String, SessionState>()
        override suspend fun saveSession(conversationId: String, session: SessionState) { saved[conversationId] = session }
        override suspend fun getSession(conversationId: String): SessionState? = saved[conversationId]
        override suspend fun deleteSessionByConversation(conversationId: String) { saved.remove(conversationId) }
        override suspend fun deleteOrphanedSessions(): Int = 0
    }

    private lateinit var factory: FakeWebSocketFactory
    private lateinit var repository: ConnectionRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A flow, not a list: waiting on it is event-driven, so nothing here
    // sleeps waiting for the repository's IO-dispatched collector.
    private val received = MutableStateFlow<List<ChatEvent.ServerTranscriptReceived>>(emptyList())

    private val convA = "conv-A"
    private val convB = "conv-B"
    private val A_REPLY = "Hello! What's your name?"
    private val B_REPLY = "Different conversation, different reply."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyManager = KeyManager(context)
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()
        val sink = NoopSink()
        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            sessionStore = MemoryStore(),
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url -> AgentConnection(km, url, factory, pendingMessageSink = sink) }
        )
        // One long-lived subscriber, exactly as ChatViewModel does in init.
        scope.launch {
            repository.observeEvents().collect {
                if (it is ChatEvent.ServerTranscriptReceived) received.update { seen -> seen + it }
            }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    /** A CONNECTED whose body carries one assistant reply for [sessionId]. */
    private fun connectedWith(sessionId: String, reply: String) =
        """{"type":"CONNECTED","session_id":"$sessionId","session":{"session_id":"$sessionId","turn":1,""" +
            """"messages":[{"role":"user","content":"Hi"},{"role":"assistant","content":"$reply"}]}}"""

    private fun send(frame: String) {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, frame)
    }

    /**
     * Suspend until [flow] carries a value matching [condition].
     *
     * Event-driven, not polled: the timeout is a deadlock guard rather than a
     * budget the assertion leans on. Frames are delivered exactly once, so a
     * wait that expires means the delivery guarantee itself regressed.
     */
    private suspend fun <T> awaitUntil(what: String, flow: Flow<T>, condition: (T) -> Boolean) {
        val settled = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            flow.first(condition)
            true
        }
        if (settled == null) {
            fail("timed out after ${AWAIT_TIMEOUT_MS}ms waiting for: $what")
        }
    }

    /** Suspend until a transcript carrying [reply] has reached the subscriber. */
    private suspend fun awaitTranscript(what: String, reply: String) =
        awaitUntil(what, received) { list -> list.any { r -> r.entries.any { it.content == reply } } }

    private fun sawReply(reply: String) =
        received.value.any { r -> r.entries.any { it.content == reply } }

    private suspend fun awaitSockets(n: Int) =
        awaitUntil("socket #$n to open", factory.createCount) { it >= n }

    @Test
    fun `a conversation's transcript is delivered again when it is reconnected`() = runBlocking {
        // The device sequence: in A, leave for B, come back to A. A's reply
        // finished server-side while we were away, so the transcript on the
        // second connect is the only way it can reach the chat list.
        repository.connect("0xAAA", convA)
        send(connectedWith(convA, A_REPLY))
        awaitTranscript("A's first transcript", A_REPLY)

        repository.switchConversation(convB)
        awaitSockets(2)
        received.value = emptyList()

        repository.switchConversation(convA)
        awaitSockets(3)

        send(connectedWith(convA, A_REPLY))
        awaitTranscript("A's transcript to be delivered a second time", A_REPLY)
    }

    @Test
    fun `a transcript for a conversation left before it was consumed is not delivered later`() = runBlocking {
        // The conflating StateFlow drops it, and that is correct: the user is
        // no longer in that conversation, so merging it would be wrong, and
        // returning to it re-delivers on the fresh CONNECT (the test above).
        repository.connect("0xAAA", convA)
        send(connectedWith(convA, A_REPLY))
        awaitTranscript("A's transcript", A_REPLY)
        received.value = emptyList()

        repository.switchConversation(convB)
        awaitSockets(2)

        // B's own transcript is the marker: it travels the same collector, so
        // once it has arrived anything still owed for A would already have
        // been delivered ahead of it. A fixed sleep here only ever proved
        // that 300ms had passed.
        send(connectedWith(convB, B_REPLY))
        awaitTranscript("B's transcript", B_REPLY)

        assertTrue(
            "the conversation we left must not keep delivering its transcript: " +
                received.value.map { r -> r.entries.map { it.content } },
            !sawReply(A_REPLY)
        )
    }

    /** The sticky transcript a subscriber joining right now would be handed, or null. */
    private suspend fun replayedToANewSubscriber(): ChatEvent.ServerTranscriptReceived? =
        withTimeoutOrNull(REPLAY_BUDGET_MS) {
            repository.observeEvents().filterIsInstance<ChatEvent.ServerTranscriptReceived>().first()
        }

    @Test
    fun `a reconciled transcript is released instead of held for the connection's life`() = runBlocking {
        // Stickiness is only affordable because it ends: once a subscriber has
        // merged the entries they are in Room, and keeping a whole
        // conversation's replayed text alive after that buys nothing.
        repository.connect("0xAAA", convA)
        send(connectedWith(convA, A_REPLY))
        awaitTranscript("A's transcript", A_REPLY)

        // Positive control, and what makes the budget below meaningful: the
        // replay is a StateFlow value, so it reaches a joining subscriber
        // immediately rather than eventually.
        assertEquals(A_REPLY, replayedToANewSubscriber()?.entries?.single()?.content)

        repository.releaseServerTranscript()

        assertNull(
            "the transcript was replayed after being reconciled, so the connection is " +
                "still holding a whole conversation's text for nobody",
            replayedToANewSubscriber()
        )
    }

    private companion object {
        /**
         * How long [replayedToANewSubscriber] waits. Not a guess at latency —
         * the positive control above proves the same call returns at once when
         * there is something to hand over.
         */
        const val REPLAY_BUDGET_MS = 2_000L

        /** Deadlock guard for [awaitUntil]; never reached while the code is correct. */
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
