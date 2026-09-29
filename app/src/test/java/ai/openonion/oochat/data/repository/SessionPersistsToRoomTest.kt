package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That a connected conversation's session actually reaches the database.
 *
 * Deliberately wires the REAL Room-backed [SessionStoreImpl] rather than an
 * in-memory fake. A whole suite of fakes stored into a map and reported
 * success, so every one of them passed while the real table stayed empty:
 * `session_states` had a foreign key to `chat_sessions`, a conversation is
 * connected long before its row is created on first message, and SQLite
 * rejected every save with the error logged and swallowed. The app forgot
 * its session id and silently started a new one on the next connect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPersistsToRoomTest {

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
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

    private class NoopSink : PendingMessageSink {
        override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?) = Unit
        override fun drain(agentAddress: String, sessionId: String?, send: (QueuedMessage) -> Unit) = Unit
        override fun clear(agentAddress: String) = Unit
    }

    private lateinit var db: AppDatabase
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var repository: ConnectionRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyManager = KeyManager(context)
        keyManager.save(keyManager.keysFromHex("aa".repeat(32) + "bb".repeat(32)))
        factory = FakeWebSocketFactory()
        val sink = NoopSink()
        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            sessionStore = SessionStoreImpl(db.sessionStateDao()),
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url -> AgentConnection(km, url, factory, pendingMessageSink = sink) }
        )
    }

    @After
    fun tearDown() = db.close()

    private fun awaitRow(conversationId: String, frame: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            factory.lastListener?.onMessage(factory.lastWebSocket!!, frame)
            if (runBlocking { db.sessionStateDao().getSession(conversationId) } != null) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `a brand-new conversation's session reaches the database before it has a row`() = runBlocking {
        // No chat_sessions row: the conversation exists only as a minted id
        // until its first message. Its session still has to be stored, or
        // returning to it starts a new one.
        val conversationId = "conv-with-no-row-yet"
        repository.connect("0xAAA", conversationId)

        val landed = awaitRow(
            conversationId,
            """{"type":"CONNECTED","session_id":"$conversationId","session":{"session_id":"$conversationId","turn":3,"messages":[]}}"""
        )

        assertEquals(
            "the session was never written — this conversation will silently start over",
            true,
            landed
        )
        val row = db.sessionStateDao().getSession(conversationId)
        assertNotNull(row)
        assertEquals(conversationId, row!!.sessionId)
        assertEquals(3, row.turn)
    }

    @Test
    fun `a server-assigned session id is stored against the conversation that got it`() = runBlocking {
        // The stale-session recovery path: our id was refused and the server
        // allocated its own, so the conversation id is NO LONGER the session
        // id. The stored row is the only thing that remembers the mapping.
        val conversationId = "conv-with-no-row-yet"
        repository.connect("0xAAA", conversationId)

        val landed = awaitRow(
            conversationId,
            """{"type":"CONNECTED","session_id":"server-allocated","session":{"session_id":"server-allocated","turn":1,"messages":[]}}"""
        )

        assertEquals(true, landed)
        assertEquals(
            "a server-allocated session must be recoverable, or the conversation loses it",
            "server-allocated",
            db.sessionStateDao().getSession(conversationId)?.sessionId
        )
    }
}
