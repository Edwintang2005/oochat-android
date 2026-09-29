package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.protocol.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryGatedSessionStoreTest {

    private class FakeSessionStore : SessionStore {
        val saved = mutableMapOf<String, SessionState>()
        var deletedConversation: String? = null

        override suspend fun saveSession(conversationId: String, session: SessionState) {
            saved[conversationId] = session
        }

        override suspend fun getSession(conversationId: String): SessionState? = saved[conversationId]

        var orphanSweeps = 0
        override suspend fun deleteOrphanedSessions(): Int { orphanSweeps++; return 0 }

        override suspend fun deleteSessionByConversation(conversationId: String) {
            deletedConversation = conversationId
        }
    }

    private fun session(id: String) = SessionState(sessionId = id, messages = null, turn = 0)

    @Test
    fun `getSession returns null when memory is disabled, even if a session is persisted`() = runTest {
        val delegate = FakeSessionStore()
        delegate.saved["conv1"] = session("sid1")
        val memoryEnabled = MutableStateFlow(false)
        val store = MemoryGatedSessionStore(delegate, memoryEnabled)

        assertNull(store.getSession("conv1"))
    }

    @Test
    fun `getSession returns the delegate's value when memory is enabled`() = runTest {
        val delegate = FakeSessionStore()
        delegate.saved["conv1"] = session("sid1")
        val memoryEnabled = MutableStateFlow(true)
        val store = MemoryGatedSessionStore(delegate, memoryEnabled)

        assertEquals("sid1", store.getSession("conv1")?.sessionId)
    }

    @Test
    fun `saveSession always passes through to the delegate regardless of the toggle`() = runTest {
        val delegate = FakeSessionStore()
        val memoryEnabled = MutableStateFlow(false)
        val store = MemoryGatedSessionStore(delegate, memoryEnabled)

        store.saveSession("conv1", session("sid1"))

        assertEquals("sid1", delegate.saved["conv1"]?.sessionId)
    }

    @Test
    fun `deleteSessionByConversation always passes through to the delegate regardless of the toggle`() = runTest {
        val delegate = FakeSessionStore()
        val memoryEnabled = MutableStateFlow(false)
        val store = MemoryGatedSessionStore(delegate, memoryEnabled)

        store.deleteSessionByConversation("conv1")

        assertEquals("conv1", delegate.deletedConversation)
    }
}
