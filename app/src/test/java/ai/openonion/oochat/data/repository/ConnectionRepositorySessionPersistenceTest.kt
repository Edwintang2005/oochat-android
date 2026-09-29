package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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

/**
 * Reproduces the "first conversation loses the whole reply on reconnect" bug.
 *
 * Every SessionStore write in ConnectionRepositoryImpl hangs off a non-null
 * `event.session` (CONNECTED at :108, OUTPUT at :155) or off the explicit
 * disconnect() path at :270. A brand-new conversation hits none of them: the
 * server's CONNECTED for a fresh session carries `session_id` but no `session`
 * body, mid-turn state arrives as `session_sync` (which the repository doesn't
 * persist at all), and the agent-switch teardown inside connect() calls
 * AgentConnection.disconnect() directly, bypassing :270. The sessionId is
 * therefore never written, the next attempt connects with sessionId=null, and
 * the server orphans the in-flight reply.
 *
 * Every frame here is delivered exactly once. ConnectionRepositoryImpl.connect()
 * does not return until its collector is subscribed to `conn.events`, so a frame
 * fed afterwards cannot be dropped by the replay=0 bus; re-sending until
 * something sticks would only hide a regression in that guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRepositorySessionPersistenceTest {

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

    /** Records every write so a test can assert what (if anything) was persisted. */
    private class RecordingSessionStore : SessionStore {
        // A flow, not a plain map: waits on it are event-driven rather than
        // polled, so nothing in this file sleeps waiting for an IO hop.
        private val _saved = MutableStateFlow<Map<String, SessionState>>(emptyMap())
        val savedFlow: StateFlow<Map<String, SessionState>> = _saved
        val saved: Map<String, SessionState> get() = _saved.value

        /** Writes attempted, not distinct values stored — see the debounce test. */
        val writeCount = java.util.concurrent.atomic.AtomicInteger(0)

        /** Seed a session as if a previous run had stored it. */
        fun preload(conversationId: String, session: SessionState) {
            _saved.update { it + (conversationId to session) }
        }

        override suspend fun saveSession(conversationId: String, session: SessionState) {
            writeCount.incrementAndGet()
            _saved.update { it + (conversationId to session) }
        }
        override suspend fun getSession(conversationId: String): SessionState? = _saved.value[conversationId]
        override suspend fun deleteSessionByConversation(conversationId: String) {
            _saved.update { it - conversationId }
        }
        override suspend fun deleteOrphanedSessions(): Int = 0
    }

    private class NoopSink : PendingMessageSink {
        override fun enqueue(
            agentAddress: String,
            sessionId: String?,
            prompt: String,
            images: List<String>?,
            files: List<FileAttachment>?
        ) = Unit
        override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) = Unit
        override fun clear(agentAddress: String) = Unit
    }

    private lateinit var factory: FakeWebSocketFactory
    private lateinit var store: RecordingSessionStore
    private lateinit var repository: ConnectionRepositoryImpl

    private val agentA = "0xAAA"
    private val agentB = "0xBBB"
    // Sessions are keyed by the local conversation, not the agent — see
    // SessionStateEntity. Each connect names the conversation it is for.
    private val convA = "conv-A"
    private val convB = "conv-B"

    @Before
    fun setUp() {
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext<android.content.Context>())
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()
        store = RecordingSessionStore()
        val sink = NoopSink()

        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            sessionStore = store,
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url -> AgentConnection(km, url, factory, pendingMessageSink = sink) }
        )
    }

    private fun send(frame: String) {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, frame)
    }

    /**
     * Suspend until [flow] carries a value matching [condition].
     *
     * Every wait in this file is on a flow the code under test writes, so the
     * value cannot be missed once we are collecting. The timeout is a
     * deadlock guard, not a budget the assertion depends on — it only fires
     * when the behaviour under test is genuinely broken.
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

    private suspend fun awaitConnected() =
        awaitUntil("connection state to reach Connected", repository.connectionState) {
            it is ConnectionState.Connected
        }

    private suspend fun awaitSockets(n: Int) =
        awaitUntil("socket #$n to open", factory.createCount) { it >= n }

    /**
     * Feed a frame with no side effects beyond [ConnectionRepository.agentProfile]
     * and wait for it to land. The bus preserves order, so anything sent before
     * the marker has been processed by the time this returns — which is how the
     * negative assertions below prove a frame was refused rather than merely
     * still in flight.
     */
    private suspend fun awaitFramesDrained() {
        send("""{"type":"AGENT_PROFILE","name":"marker","model":"test"}""")
        awaitUntil("the marker frame to be processed", repository.agentProfile) { it != null }
    }

    /** A fresh server session: session_id present, no `session` body (len=92 on the wire). */
    private fun connectedIdOnly(sessionId: String) = """{"type":"CONNECTED","session_id":"$sessionId"}"""

    private fun connectedWithBody(sessionId: String, turn: Int) =
        """{"type":"CONNECTED","session_id":"$sessionId","session":{"session_id":"$sessionId","turn":$turn,"messages":[]}}"""

    private fun sessionSync(sessionId: String, turn: Int, reply: String) =
        """{"type":"session_sync","session":{"session_id":"$sessionId","turn":$turn,""" +
            """"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"$reply"}]}}"""

    @Test
    fun `connect resumes the named conversation's own session, not the agent's`() = runBlocking {
        // Two conversations with the same agent, each with its own server
        // session. Connecting for one must hand the connection that one's
        // session id — keying this by agent address is what made a new
        // conversation inherit an older one's server-side history.
        store.preload(convA, SessionState(sessionId = "sess-for-A", turn = 4))
        store.preload(convB, SessionState(sessionId = "sess-for-B", turn = 9))

        repository.connect(agentA, convA)

        assertEquals("sess-for-A", repository.getCurrentSession()?.sessionId)

        repository.connect(agentA, convB)

        assertEquals("sess-for-B", repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a conversation with nothing stored connects on a session named by its own id`() = runBlocking {
        // The whole point of minting client-side: the conversation id is the
        // session id, so a conversation the server has never seen still
        // CONNECTs on a session of its own instead of waiting to be told one.
        // It also means the id survives losing session_states entirely.
        repository.connect(agentA, convA)

        assertEquals(convA, repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a stored session wins over the bare conversation id, keeping its transcript`() = runBlocking {
        store.preload(convA, SessionState(sessionId = convA, turn = 7))

        repository.connect(agentA, convA)

        assertEquals(convA, repository.getCurrentSession()?.sessionId)
        assertEquals(
            "the stored session carries the turn count a bare id cannot",
            7,
            repository.getCurrentSession()?.turn
        )
    }

    @Test
    fun `connect with no conversation at all carries no session`() = runBlocking {
        // The brand-new-conversation path: no session_id on the CONNECT is
        // what makes the server allocate one for this conversation alone.
        store.preload(convA, SessionState(sessionId = "sess-for-A", turn = 4))

        repository.connect(agentA, conversationId = null)

        assertNull(repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a server-assigned session we then switch away from cannot be filed under the new conversation`() = runBlocking {
        // The device repro exactly. The first connect had no conversation, so
        // nothing staged an id and the server allocated its own; the switch
        // then asked for conversation B and was answered with that allocated
        // id. Unless a server-assigned id is recorded as one we have been on,
        // it looks like a fresh allocation and gets adopted — which is how
        // B's row ended up naming a session it never requested.
        repository.connect(agentA, conversationId = null)
        send(connectedWithBody("server-allocated", turn = 1))
        awaitConnected()

        repository.switchConversation(convB)
        awaitSockets(2)

        // The host answers the switch with the session it allocated earlier.
        send(connectedWithBody("server-allocated", turn = 9))
        awaitFramesDrained()

        assertFalse(
            "conversation B was filed under a session the server never gave it",
            store.saved.containsKey(convB)
        )
    }

    @Test
    fun `a CONNECTED naming the previous conversation's session is never saved under the new one`() = runBlocking {
        // Verbatim from the device against wss://oo.openonion.ai: we asked for
        // conversation B's id, the relay answered with A's (it pins one
        // session per socket), and we saved A's id under B — so the row named
        // a session it never requested. Refusing the frame is what keeps the
        // "conversation id is the session id" property true.
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        // Positive control: writes really are reaching the store on this path.
        awaitUntil("conversation A's session to be stored", store.savedFlow) { it[convA]?.turn == 1 }

        repository.switchConversation(convB)
        awaitSockets(2)

        // The host ignores the switch and answers with A's session anyway.
        send(connectedWithBody(convA, turn = 9))
        awaitFramesDrained()

        assertFalse(
            "conversation B was given the session it never asked for",
            store.saved.containsKey(convB)
        )
        assertEquals(
            "conversation A's stored session must not be rewritten from a refused frame",
            1,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `switching conversation clears the mode chip and any unconfirmed mode change`() = runBlocking {
        // Mode belongs to the server session, so it becomes unknown again on a
        // switch for the same reason it does on connect. Two changes landed
        // side by side here — per-conversation sessions and the pending-mode
        // rollback — and this is where they meet.
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)
        assertEquals(true, repository.modePending.value)

        repository.switchConversation(convB)

        assertEquals(
            "the mode of the conversation we left must not carry over",
            ApprovalMode.DEFAULT,
            repository.approvalMode.value
        )
        assertEquals(
            "a mode_change awaiting confirmation there is never answered here",
            false,
            repository.modePending.value
        )
    }

    @Test
    fun `a CONNECTED frame that carries only session_id still persists the session id`() = runBlocking {
        repository.connect(agentA, convA)
        // Exactly one delivery: the id it carries only ever changes the
        // session once, so a lost first frame can never be made good by a
        // second copy — that is what made this the flakiest test in the file.
        send(connectedIdOnly("sess-new"))
        awaitConnected()
        awaitUntil("the session write to land for agentA", store.savedFlow) { it.containsKey(convA) }

        assertNotNull(
            "CONNECTED carried session_id=sess-new but nothing was persisted, so the next " +
                "connect sends sessionId=null and the server orphans the in-flight reply",
            store.saved[convA]
        )
        assertEquals("sess-new", store.saved[convA]?.sessionId)
    }

    @Test
    fun `a session_sync frame persists the session it carries`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        send(sessionSync("sess-1", turn = 1, reply = "the reply"))
        awaitUntil("session_sync to persist turn 1", store.savedFlow) { it[convA]?.turn == 1 }

        assertEquals(
            "session_sync is the only frame carrying mid-turn state, but the repository " +
                "ignores it — the turn the user is watching is never persisted",
            1,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `a burst of session_sync frames is conflated into far fewer writes`() = runBlocking {
        // Each write re-encodes the whole transcript and rewrites the row, and
        // session_sync streams after every trace entry — so a turn with 20 tool
        // calls paid for 20 of them on the same coroutine that has to read the
        // next frame. The newest session is the only one resume ever reads.
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        val writesBefore = store.writeCount.get()

        repeat(BURST) { turn -> send(sessionSync("sess-1", turn = turn + 1, reply = "reply-$turn")) }

        awaitUntil("the newest session_sync to be persisted", store.savedFlow) { it[convA]?.turn == BURST }
        val writes = store.writeCount.get() - writesBefore
        assertTrue(
            "$BURST session_sync frames caused $writes writes — they are not being conflated",
            writes < BURST
        )
        assertEquals(
            "conflating must still land the newest session, not an intermediate one",
            BURST,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `a conversation switch flushes what the debounce window is still holding`() = runBlocking {
        // The window is only safe because every lifecycle boundary writes
        // inline and supersedes it. Leaving a queued write behind here would
        // lose the transcript of the conversation being left.
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        send(sessionSync("sess-1", turn = 7, reply = "mid-turn"))
        awaitFramesDrained()

        repository.switchConversation(convB)

        assertEquals(
            "the switch returned before the pending session_sync was written, so the " +
                "conversation being left keeps a stale turn count",
            7,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `switching agents persists the outgoing agent's live session before tearing it down`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        // The agent starts replying: turn advances, but only via session_sync.
        send(sessionSync("sess-1", turn = 1, reply = "the reply"))
        awaitUntil("session_sync to persist turn 1", store.savedFlow) { it[convA]?.turn == 1 }

        // User navigates to another agent — connect() tears the socket down
        // through AgentConnection.disconnect(), not repository.disconnect().
        // It suspends until the switch is done, so the assert needs no wait.
        repository.connect(agentB, convB)

        assertEquals(
            "agent A's last known session must survive the switch; it is stale at turn=0 " +
                "(or absent), so reconnecting to A resumes the wrong session",
            1,
            store.saved[convA]?.turn
        )
    }

    private companion object {
        /** Deadlock guard for [awaitUntil]; never reached while the code is correct. */
        const val AWAIT_TIMEOUT_MS = 15_000L

        /** session_sync frames in one turn, matching ChatEventReducer's "after each trace entry". */
        const val BURST = 20
    }
}
