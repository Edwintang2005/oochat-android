package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.protocol.SessionMessage
import ai.openonion.oochat.data.protocol.SessionState
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SessionStoreImpl] — specifically its JSON round-trip of
 * [SessionState.messages] through a single serialized
 * Room column (Room has no native support for nested list types), and that
 * a failure anywhere in that path degrades to null/no-op rather than
 * propagating and taking down the caller — [ConnectionRepositoryImpl]
 * calls this on the connect/reconnect hot path, where an unhandled
 * exception would be a regression far worse than a missed session restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreImplTest {

    private lateinit var db: AppDatabase
    private lateinit var store: SessionStoreImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        store = SessionStoreImpl(db.sessionStateDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** session_states rows are FK-bound to a conversation — see the entity. */
    private suspend fun seedConversation(id: String) {
        if (db.agentDao().getAgentByAddress("0xagent") == null) {
            db.agentDao().insertAgent(
                AgentEntity(
                    id = "agent-1",
                    address = "0xagent",
                    name = "Agent",
                    serverUrl = "https://example.com",
                    createdAt = 0L,
                    isActive = true
                )
            )
        }
        db.sessionDao().insertSession(
            ChatSessionEntity(id = id, agentId = "agent-1", title = id, createdAt = 0L, updatedAt = 0L)
        )
    }

    @Test
    fun `saveSession then getSession round-trips messages intact`() = runTest {
        seedConversation("conv-1")
        val session = SessionState(
            sessionId = "sid-1",
            messages = listOf(
                SessionMessage(role = "user", content = "hi"),
                // A tool-calls-only assistant message omits content
                // entirely — see SessionMessage.content's own doc; the
                // round-trip must preserve that null, not coerce it to "".
                SessionMessage(role = "assistant", content = null)
            ),
            turn = 3
        )

        store.saveSession("conv-1", session)
        val restored = store.getSession("conv-1")

        assertEquals(session, restored)
    }

    @Test
    fun `saveSession then getSession round-trips null messages`() = runTest {
        seedConversation("conv-1")
        val session = SessionState(sessionId = "sid-2", messages = null, turn = 0)

        store.saveSession("conv-1", session)

        assertEquals(session, store.getSession("conv-1"))
    }

    @Test
    fun `saveSession leaves trace_json null, clearing whatever an older build wrote`() = runTest {
        // The column is dead but still there (dropping it costs a migration
        // for nothing). What must not happen is a new save leaving a legacy
        // trace tree — potentially megabytes of it — behind in the row.
        seedConversation("conv-1")
        db.sessionStateDao().saveSession(
            ai.openonion.oochat.data.local.db.entity.SessionStateEntity(
                conversationId = "conv-1",
                sessionId = "sid-1",
                turn = 1,
                messagesJson = null,
                traceJson = """["a-legacy-trace-entry"]""",
                lastUpdated = 0L
            )
        )

        store.saveSession("conv-1", SessionState(sessionId = "sid-1", turn = 2))

        assertNull(db.sessionStateDao().getSession("conv-1")?.traceJson)
    }

    @Test
    fun `getSession returns null when nothing was ever saved for that conversation`() = runTest {
        assertNull(store.getSession("conv-never-saved"))
    }

    @Test
    fun `saveSession overwrites a previous session for the same conversation`() = runTest {
        seedConversation("conv-1")
        store.saveSession("conv-1", SessionState(sessionId = "old", turn = 1))
        store.saveSession("conv-1", SessionState(sessionId = "new", turn = 2))

        val restored = store.getSession("conv-1")

        assertEquals("new", restored?.sessionId)
        assertEquals(2, restored?.turn)
    }

    @Test
    fun `two conversations of the same agent keep separate server sessions`() = runTest {
        seedConversation("conv-a")
        seedConversation("conv-b")

        store.saveSession("conv-a", SessionState(sessionId = "sid-a"))
        store.saveSession("conv-b", SessionState(sessionId = "sid-b"))

        assertEquals("sid-a", store.getSession("conv-a")?.sessionId)
        assertEquals("sid-b", store.getSession("conv-b")?.sessionId)
    }

    @Test
    fun `a session saves for a conversation that has no row yet`() = runTest {
        // The blocker this replaced a foreign key to fix: a brand-new
        // conversation is connected and has a session before its Room row
        // exists, and the parent constraint rejected the save — so the app
        // forgot the session and silently started a new one next time.
        store.saveSession("not-yet-a-conversation", SessionState(sessionId = "sid-pending", turn = 1))

        assertEquals("sid-pending", store.getSession("not-yet-a-conversation")?.sessionId)
    }

    @Test
    fun `orphaned sessions are swept, live ones are kept`() = runTest {
        seedConversation("conv-a")
        seedConversation("conv-b")
        store.saveSession("conv-a", SessionState(sessionId = "sid-a"))
        store.saveSession("conv-b", SessionState(sessionId = "sid-b"))
        store.saveSession("abandoned", SessionState(sessionId = "sid-abandoned"))

        // The drawer's trash action deletes only the conversation row; the
        // sweep is what keeps session_states from accumulating rows no
        // conversation can ever claim again.
        db.sessionDao().deleteSessionById("conv-a")
        assertEquals(2, store.deleteOrphanedSessions())

        assertNull("the deleted conversation's session must go", store.getSession("conv-a"))
        assertNull("the abandoned conversation's session must go", store.getSession("abandoned"))
        assertEquals("sid-b", store.getSession("conv-b")?.sessionId)
    }

    @Test
    fun `deleteSessionByConversation removes only that conversation's session`() = runTest {
        seedConversation("conv-a")
        seedConversation("conv-b")
        store.saveSession("conv-a", SessionState(sessionId = "sid-a"))
        store.saveSession("conv-b", SessionState(sessionId = "sid-b"))

        store.deleteSessionByConversation("conv-a")

        assertNull(store.getSession("conv-a"))
        assertEquals("sid-b", store.getSession("conv-b")?.sessionId)
    }

    @Test
    fun `deleteSessionByConversation for a conversation with no saved session is a safe no-op`() = runTest {
        store.deleteSessionByConversation("conv-never-saved")
    }

    @Test
    fun `getSession on a corrupted messages_json column degrades to null instead of throwing`() = runTest {
        // Simulates a malformed row (e.g. hand-edited DB, a future schema
        // change) reaching decodeFromString — SessionStoreImpl must catch
        // and log this (see its own runCatchingCancellable), not crash the
        // connect/reconnect path that calls it.
        seedConversation("conv-1")
        store.saveSession("conv-1", SessionState(sessionId = "sid-1"))
        db.sessionStateDao().saveSession(
            db.sessionStateDao().getSession("conv-1")!!.copy(messagesJson = "{not valid json")
        )

        assertNull(store.getSession("conv-1"))
    }
}
