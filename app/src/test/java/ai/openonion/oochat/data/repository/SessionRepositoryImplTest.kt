package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = SessionRepositoryImpl(db.sessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ChatSessionEntity has a real foreign key to agent_profiles(id), so
    // every session created in these tests needs a real parent row first.
    private suspend fun seedAgent(id: String) {
        db.agentDao().insertAgent(
            AgentEntity(id = id, address = "0x$id", name = id, serverUrl = "https://example.com", createdAt = 0L)
        )
    }

    @Test
    fun `createSession persists a session with a fresh id and returns it`() = runTest {
        seedAgent("agent-1")
        val session = repository.createSession("agent-1", "New conversation")

        assertTrue(session.id.isNotBlank())
        assertEquals("agent-1", session.agentId)
        assertEquals("New conversation", session.title)
        assertEquals(session, repository.getSessionById(session.id))
    }

    @Test
    fun `getSessionsByAgent only returns sessions for that agent, newest first`() = runTest {
        seedAgent("agent-1")
        seedAgent("agent-2")
        val a1s1 = repository.createSession("agent-1", "first")
        Thread.sleep(2)
        val a1s2 = repository.createSession("agent-1", "second")
        repository.createSession("agent-2", "other agent's session")

        val sessions = repository.getSessionsByAgent("agent-1").first()

        assertEquals(listOf(a1s2.id, a1s1.id), sessions.map { it.id })
    }

    @Test
    fun `renameSession updates the title in place`() = runTest {
        seedAgent("agent-1")
        val session = repository.createSession("agent-1", "New conversation")

        repository.renameSession(session.id, "Renamed")

        assertEquals("Renamed", repository.getSessionById(session.id)?.title)
    }

    @Test
    fun `updateMessageInfo updates count and preview`() = runTest {
        seedAgent("agent-1")
        val session = repository.createSession("agent-1", "New conversation")

        repository.updateMessageInfo(session.id, count = 3, preview = "latest message")

        val updated = repository.getSessionById(session.id)
        assertEquals(3, updated?.messageCount)
        assertEquals("latest message", updated?.lastMessagePreview)
    }

    @Test
    fun `deleteSession removes it`() = runTest {
        seedAgent("agent-1")
        val session = repository.createSession("agent-1", "New conversation")

        repository.deleteSession(session.id)

        assertNull(repository.getSessionById(session.id))
    }

    @Test
    fun `getSessionById returns null for an unknown id`() = runTest {
        assertNull(repository.getSessionById("missing"))
    }
}
