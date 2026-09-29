package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.SafePreferencesWrapper
import ai.openonion.oochat.domain.model.AgentProfile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DefaultAgentRepository] against a real (Robolectric-
 * backed) [SafePreferencesWrapper] and a minimal in-memory [AgentRepository]
 * fake — this class only needs an [AgentRepository] to look up profiles by
 * id/fall back to the first active agent, not the full Room stack, so a
 * fake keeps this test focused on [DefaultAgentRepository]'s own prefs logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAgentRepositoryTest {

    private class FakeAgentRepository : AgentRepository {
        val agents = mutableListOf<AgentProfile>()
        override fun getAllAgents() = MutableStateFlow(agents.toList())
        override fun getActiveAgents() = MutableStateFlow(agents.filter { it.isActive })
        override suspend fun getAgentById(id: String) = agents.find { it.id == id }
        override suspend fun getAgentByAddress(address: String) = agents.find { it.address == address }
        override suspend fun createAgent(agent: AgentProfile): AgentProfile { agents += agent; return agent }
        override suspend fun updateAgent(agent: AgentProfile) {}
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun updateLastConnected(agentId: String) {}
        override suspend fun getDefaultAgent() = agents.firstOrNull { it.isActive }
        override suspend fun reorderAgents(orderedIds: List<String>) {}
    }

    private lateinit var agentRepository: FakeAgentRepository
    private lateinit var repository: DefaultAgentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        agentRepository = FakeAgentRepository()
        repository = DefaultAgentRepository(SafePreferencesWrapper(context, "test_default_agent_prefs"), agentRepository)
    }

    private fun profile(id: String, isActive: Boolean = true) = AgentProfile(
        id = id, address = "0x$id", name = id, serverUrl = "https://example.com",
        createdAt = 0L, isActive = isActive
    )

    @Test
    fun `hasDefaultAgent is false before anything has been set`() = runTest {
        assertFalse(repository.hasDefaultAgent())
    }

    @Test
    fun `setDefaultAgent then getDefaultAgent resolves the persisted id via AgentRepository`() = runTest {
        agentRepository.agents += profile("a1")

        repository.setDefaultAgent("a1")

        assertTrue(repository.hasDefaultAgent())
        assertEquals("a1", repository.getDefaultAgent()?.id)
    }

    @Test
    fun `getDefaultAgent falls back to the first active agent when nothing was explicitly set`() = runTest {
        agentRepository.agents += profile("a1", isActive = false)
        agentRepository.agents += profile("a2", isActive = true)

        assertEquals("a2", repository.getDefaultAgent()?.id)
    }

    @Test
    fun `clearDefaultAgent removes the persisted id`() = runTest {
        agentRepository.agents += profile("a1", isActive = false)
        repository.setDefaultAgent("a1")
        assertEquals("a1", repository.getDefaultAgent()?.id)

        repository.clearDefaultAgent()

        assertFalse(repository.hasDefaultAgent())
        // No explicit default anymore, and the fake's fallback finds no
        // active agent either (a1 is inactive), so this must be null.
        assertNull(repository.getDefaultAgent())
    }
}
