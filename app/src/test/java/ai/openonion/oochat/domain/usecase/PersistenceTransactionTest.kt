package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ThinkingStatus
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PersistenceTransaction.persistMessageAtomically] drives Room's
 * `withTransaction`, which needs a real open helper/query executor — a
 * hand-stubbed [AppDatabase] can't satisfy that. So, following the pattern
 * established in [ai.openonion.oochat.data.local.db.RoomRobolectricSpikeTest],
 * this test runs against a real in-memory Room database under Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceTransactionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var database: AppDatabase
    private lateinit var transaction: PersistenceTransaction

    private val agentId = "agent-1"

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        transaction = PersistenceTransaction(database)

        runBlocking {
            database.agentDao().insertAgent(
                AgentEntity(
                    id = agentId,
                    address = "0xabc",
                    name = "Test Agent",
                    serverUrl = "https://example.com",
                    createdAt = 0L
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private suspend fun insertSession(sessionId: String, title: String) {
        database.sessionDao().insertSession(
            ChatSessionEntity(
                id = sessionId,
                agentId = agentId,
                title = title,
                createdAt = 0L,
                updatedAt = 0L,
                messageCount = 0,
                lastMessagePreview = null
            )
        )
    }

    @Test
    fun `persistMessageAtomically inserts message`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing")
        val item = ChatItem.User(id = "u1", content = "hello")

        transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> },
            onPreviewUpdate = { _, _, _ -> }
        )

        val inserted = database.messageDao().getMessagesListBySession(sessionId).single()
        assertEquals("u1", inserted.id)
        assertEquals("hello", inserted.content)
        assertEquals(Role.USER.name, inserted.role)
    }

    @Test
    fun `persistMessageAtomically updates title for first user message`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "New conversation")
        val item = ChatItem.User(id = "u1", content = "test message")

        var updatedTitle: String? = null
        transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, title -> updatedTitle = title },
            onPreviewUpdate = { _, _, _ -> }
        )

        assertEquals("test message", updatedTitle)
        assertEquals("test message", database.sessionDao().getSessionById(sessionId)?.title)
    }

    @Test
    fun `persistMessageAtomically skips title update when session title is not default`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing Title")
        val item = ChatItem.User(id = "u1", content = "test message")

        var titleUpdateCalled = false
        transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> titleUpdateCalled = true },
            onPreviewUpdate = { _, _, _ -> }
        )

        assertTrue(!titleUpdateCalled)
        assertEquals("Existing Title", database.sessionDao().getSessionById(sessionId)?.title)
    }

    @Test
    fun `persistMessageAtomically noop when item is Agent with null agent`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing")
        val item = ChatItem.Turn(id = "t1", agent = null)

        transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> },
            onPreviewUpdate = { _, _, _ -> }
        )

        assertTrue(database.messageDao().getMessagesListBySession(sessionId).isEmpty())
    }

    @Test
    fun `a replayed message keeps its original timestamp and position`() = runTest(testDispatcher) {
        val sessionId = "session-replay"
        insertSession(sessionId, "Existing")

        suspend fun persist(item: ChatItem) = transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> },
            onPreviewUpdate = { _, _, _ -> }
        )

        // Turn 1: a question and its reply. The reply carries a content-derived
        // id, which is what makes a later session_sync replay hit the same row.
        persist(ChatItem.User(id = "u1", content = "run ls"))
        val reply1 = ChatItem.Agent(id = "asst-stable-1", content = "I can see the files")
        persist(reply1)

        Thread.sleep(5)

        // Turn 2 arrives, and session_sync replays turn 1's reply alongside it.
        persist(ChatItem.User(id = "u2", content = "delete the readme"))
        persist(reply1)
        persist(ChatItem.Agent(id = "asst-stable-2", content = "I have deleted it"))

        val ids = database.messageDao().getMessagesListBySession(sessionId).map { it.id }
        assertEquals(
            "the replayed reply must stay where it happened, not jump past the next question",
            listOf("u1", "asst-stable-1", "u2", "asst-stable-2"),
            ids
        )
    }

    @Test
    fun `a message replayed into a different session stays with the session that first owned it`() = runTest(testDispatcher) {
        val sessionA = "session-a"
        val sessionB = "session-b"
        insertSession(sessionA, "Existing")
        insertSession(sessionB, "New conversation")

        suspend fun persist(sessionId: String, item: ChatItem) = transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> },
            onPreviewUpdate = { _, _, _ -> }
        )

        // Same content-derived id, first persisted under session A.
        val reply = ChatItem.Agent(id = "asst-shared", content = "Sure, I can help with that.")
        persist(sessionA, reply)

        // session_sync replays the same id into a brand-new session (drawer
        // "+" then send) — REPLACE must not relocate the row out of A.
        persist(sessionB, reply)

        val sessionAIds = database.messageDao().getMessagesListBySession(sessionA).map { it.id }
        assertTrue(
            "the reply must stay filed under the session that first received it",
            sessionAIds.contains("asst-shared")
        )
        val sessionBIds = database.messageDao().getMessagesListBySession(sessionB).map { it.id }
        assertTrue(
            "the row must not have been copied into session B either",
            sessionBIds.isEmpty()
        )
    }

    // ── turn metadata ────────────────────────────────────────────────

    private suspend fun persist(sessionId: String, item: ChatItem) =
        transaction.persistMessageAtomically(
            sessionId = sessionId,
            item = item,
            onTitleUpdate = { _, _ -> },
            onPreviewUpdate = { _, _, _ -> }
        )

    private fun doneThinking(id: String) = ChatItem.Thinking(
        id = id,
        status = ThinkingStatus.DONE,
        model = "gemini-2.5-flash",
        durationMs = 2100.0,
        tokensTotal = 46,
        costUsd = 0.0071,
        contextPercent = 12.5
    )

    @Test
    fun `a turn keeps its assistant row while storing its footer metadata`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing")
        val turn = ChatItem.Turn(
            id = "turn-1",
            thinking = doneThinking("turn-1"),
            agent = ChatItem.Agent(id = "turn-1", content = "the answer")
        )

        persist(sessionId, turn)

        val row = database.messageDao().getMessagesListBySession(sessionId).single()
        // The row stays a normal assistant message — that is what keeps it
        // searchable, counted and available as the drawer preview.
        assertEquals(Role.ASSISTANT.name, row.role)
        assertEquals("the answer", row.content)
        assertEquals(1, database.messageDao().getMessageCount(sessionId))
        assertEquals(
            doneThinking("turn-1"),
            TranscriptItemCodec.decodeTurnThinking(row.payload!!)
        )
    }

    @Test
    fun `a replayed turn does not erase the metadata already recorded`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing")
        persist(
            sessionId,
            ChatItem.Turn(
                id = "turn-1",
                thinking = doneThinking("turn-1"),
                agent = ChatItem.Agent(id = "turn-1", content = "the answer")
            )
        )

        // session_sync replays the same turn with no thinking of its own,
        // and insertMessage is REPLACE.
        persist(
            sessionId,
            ChatItem.Turn(id = "turn-1", agent = ChatItem.Agent(id = "turn-1", content = "the answer"))
        )

        val row = database.messageDao().getMessagesListBySession(sessionId).single()
        assertEquals(
            doneThinking("turn-1"),
            TranscriptItemCodec.decodeTurnThinking(row.payload!!)
        )
    }

    @Test
    fun `a turn with nothing worth reporting stores no payload`() = runTest(testDispatcher) {
        val sessionId = "session-1"
        insertSession(sessionId, "Existing")

        persist(
            sessionId,
            ChatItem.Turn(
                id = "turn-1",
                thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.DONE),
                agent = ChatItem.Agent(id = "turn-1", content = "the answer")
            )
        )

        val row = database.messageDao().getMessagesListBySession(sessionId).single()
        assertNull("a bare Done is not worth a footer", row.payload)
        assertNull(row.itemType)
    }
}
