package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.network.AgentDiscoveryService
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke-level coverage for [ChatViewModel]'s constructor: confirms it can
 * actually be built under Robolectric with production defaults, with an
 * explicit collaborator swapped in, and with a fully custom repository —
 * doesn't exercise behavior, just that dependency injection wires up
 * without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelInjectionTest {

    private lateinit var application: Application

    @Before
    fun grabApplicationContext() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `constructs with every default left as-is`() {
        val viewModel = ChatViewModel(application = application, initialMyAddress = "0xTEST")

        assertNotNull(viewModel)
    }

    @Test
    fun `constructs with an explicit AgentDiscoveryService swapped in`() {
        val viewModel = ChatViewModel(
            application = application,
            agentDiscovery = AgentDiscoveryService(),
            initialMyAddress = "0xTEST"
        )

        assertNotNull(viewModel)
    }

    @Test
    fun `constructs with a custom AgentRepository implementation`() {
        val viewModel = ChatViewModel(
            application = application,
            agentRepository = EmptyAgentRepository(),
            initialMyAddress = "0xTEST"
        )

        assertNotNull(viewModel)
    }
}

/** No-op [AgentRepository] — this file only cares that construction succeeds, not what it returns. */
private class EmptyAgentRepository : AgentRepository {
    override fun getAllAgents() = kotlinx.coroutines.flow.emptyFlow<List<AgentProfile>>()
    override fun getActiveAgents() = kotlinx.coroutines.flow.emptyFlow<List<AgentProfile>>()
    override suspend fun getAgentById(id: String): AgentProfile? = null
    override suspend fun getAgentByAddress(address: String): AgentProfile? = null
    override suspend fun createAgent(agent: AgentProfile) = agent
    override suspend fun updateAgent(agent: AgentProfile) {}
    override suspend fun deleteAgent(agentId: String) {}
    override suspend fun updateLastConnected(agentId: String) {}
    override suspend fun getDefaultAgent(): AgentProfile? = null
    override suspend fun reorderAgents(orderedIds: List<String>) {}
}
