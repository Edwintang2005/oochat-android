package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.Role
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = MessageRepositoryImpl(db.messageDao())

        // ChatMessageEntity has a real foreign key to chat_sessions(id),
        // which itself has one to agent_profiles(id) — every test below
        // persists messages into sessions "s1"/"s2", so both need real
        // parent rows up front.
        db.agentDao().insertAgent(
            AgentEntity(id = "agent-1", address = "0xagent-1", name = "agent-1", serverUrl = "https://example.com", createdAt = 0L)
        )
        db.sessionDao().insertSession(
            ChatSessionEntity(id = "s1", agentId = "agent-1", title = "s1", createdAt = 0L, updatedAt = 0L)
        )
        db.sessionDao().insertSession(
            ChatSessionEntity(id = "s2", agentId = "agent-1", title = "s2", createdAt = 0L, updatedAt = 0L)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(id: String, sessionId: String, timestamp: Long, content: String = "hi", images: List<String>? = null) = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = Role.USER,
        content = content,
        timestamp = timestamp,
        images = images
    )

    @Test
    fun `createMessage then getMessagesListBySession returns it ordered by timestamp ascending`() = runTest {
        repository.createMessage(message("m2", "s1", timestamp = 200L, content = "second"))
        repository.createMessage(message("m1", "s1", timestamp = 100L, content = "first"))

        val messages = repository.getMessagesListBySession("s1")

        assertEquals(listOf("first", "second"), messages.map { it.content })
    }

    @Test
    fun `getMessagesListBySession only returns messages for that session`() = runTest {
        repository.createMessage(message("m1", "s1", timestamp = 100L))
        repository.createMessage(message("m2", "s2", timestamp = 100L))

        assertEquals(1, repository.getMessagesListBySession("s1").size)
    }

    @Test
    fun `createMessage with an existing id replaces it instead of duplicating`() = runTest {
        // Confirms Room's real OnConflictStrategy.REPLACE behavior on
        // MessageDao.insertMessage (bare `id` primary key, not scoped to
        // session) — this is the mechanism that used to let two different
        // sessions' first messages silently overwrite each other back when
        // ChatViewModel minted ids from a per-instance counter that reset
        // on startNewSession(); ids are UUIDs now (see ChatViewModel.sendMessage),
        // but this DAO-level REPLACE behavior on a colliding id is still real.
        repository.createMessage(message("m1", "s1", timestamp = 100L, content = "original"))
        repository.createMessage(message("m1", "s2", timestamp = 200L, content = "overwritten"))

        assertTrue("original session's message must be gone", repository.getMessagesListBySession("s1").isEmpty())
        assertEquals("overwritten", repository.getMessagesListBySession("s2").single().content)
    }

    @Test
    fun `createMessage with images persists and round-trips them through the JSON-encoded column`() = runTest {
        repository.createMessage(message("m1", "s1", timestamp = 100L, images = listOf("file:///a.jpg", "file:///b.jpg")))
        repository.createMessage(message("m2", "s1", timestamp = 200L, images = null))

        val messages = repository.getMessagesListBySession("s1")

        assertEquals(listOf("file:///a.jpg", "file:///b.jpg"), messages.first { it.id == "m1" }.images)
        assertEquals(null, messages.first { it.id == "m2" }.images)
    }

    @Test
    fun `getMessageCount counts only that session's messages`() = runTest {
        repository.createMessage(message("m1", "s1", timestamp = 100L))
        repository.createMessage(message("m2", "s1", timestamp = 200L))
        repository.createMessage(message("m3", "s2", timestamp = 100L))

        assertEquals(2, repository.getMessageCount("s1"))
    }

    @Test
    fun `deleteMessagesBySession clears the whole session's log`() = runTest {
        repository.createMessage(message("m1", "s1", timestamp = 100L))
        repository.createMessage(message("m2", "s1", timestamp = 200L))
        repository.createMessage(message("m3", "s2", timestamp = 100L))

        repository.deleteMessagesBySession("s1")

        assertTrue(repository.getMessagesListBySession("s1").isEmpty())
        assertEquals(1, repository.getMessagesListBySession("s2").size)
    }

    @Test
    fun `existsById is true only for a persisted id`() = runTest {
        repository.createMessage(message("m1", "s1", timestamp = 100L))

        assertTrue(repository.existsById("m1"))
        assertFalse(repository.existsById("never-inserted"))
    }
}
