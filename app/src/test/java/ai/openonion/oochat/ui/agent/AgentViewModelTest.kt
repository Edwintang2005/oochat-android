package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.AgentDiscoveryRepository
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepositoryContract
import ai.openonion.oochat.domain.model.AgentProfile
import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AgentViewModel].
 *
 * Enabled by the constructor-injection fix in this same phase: every
 * dependency is now interface-typed with a production default pulled from
 * [ai.openonion.oochat.di.AppContainer], giving tests the same
 * fake-injection seam [ai.openonion.oochat.ui.chat.ChatViewModelTest]
 * already relies on — previously all 5 dependencies were constructed inline
 * in the class body with no override point at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {

    // ── Test fakes ─────────────────────────────────────────────────────

    private class FakeAgentRepository : AgentRepository {
        val agents = MutableStateFlow<List<AgentProfile>>(emptyList())
        var createCount = 0
        var updateCount = 0
        var deleteCount = 0
        var lastDeletedId: String? = null

        override fun getAllAgents(): Flow<List<AgentProfile>> = agents
        override fun getActiveAgents(): Flow<List<AgentProfile>> = agents

        override suspend fun getAgentById(id: String): AgentProfile? =
            agents.value.find { it.id == id }

        override suspend fun getAgentByAddress(address: String): AgentProfile? =
            agents.value.find { it.address == address }

        override suspend fun createAgent(agent: AgentProfile): AgentProfile {
            createCount++
            agents.value = agents.value + agent
            return agent
        }

        override suspend fun updateAgent(agent: AgentProfile) {
            updateCount++
            agents.value = agents.value.map { if (it.id == agent.id) agent else it }
        }

        override suspend fun deleteAgent(agentId: String) {
            deleteCount++
            lastDeletedId = agentId
            agents.value = agents.value.filter { it.id != agentId }
        }

        override suspend fun updateLastConnected(agentId: String) {}

        override suspend fun getDefaultAgent(): AgentProfile? = agents.value.firstOrNull { it.isActive }

        var reorderedIds: List<String>? = null
        override suspend fun reorderAgents(orderedIds: List<String>) {
            reorderedIds = orderedIds
            val byId = agents.value.associateBy { it.id }
            agents.value = orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(position = index) }
        }
    }

    private class FakeDefaultAgentRepository : DefaultAgentRepositoryContract {
        var defaultAgentId: String? = null
        var setCount = 0
        var clearCount = 0

        override suspend fun getDefaultAgent(): AgentProfile? = null
        override suspend fun setDefaultAgent(agentId: String) {
            setCount++
            defaultAgentId = agentId
        }
        override suspend fun clearDefaultAgent() {
            clearCount++
            defaultAgentId = null
        }
        override suspend fun hasDefaultAgent(): Boolean = defaultAgentId != null
    }

    private class FakeAgentDiscoveryRepository : AgentDiscoveryRepository {
        var discoveredResult: List<AgentProfile> = emptyList()
        var shouldThrow: Boolean = false

        override suspend fun discoverFromRelay(relayUrl: String): List<AgentProfile> {
            if (shouldThrow) throw RuntimeException("discovery failed")
            return discoveredResult
        }
    }

    private class FakeConnectionConfigRepository : ConnectionConfigRepository {
        var config: ConnectionConfig? = null
        override suspend fun getConfig(): ConnectionConfig? = config
        override fun observeConfig(): Flow<ConnectionConfig?> = MutableStateFlow(config)
        override suspend fun saveConfig(config: ConnectionConfig) { this.config = config }
        override suspend fun deleteConfig() { config = null }
        override suspend fun hasConfig(): Boolean = config != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    /**
     * Minimal fake — this test never drives a real connection, but
     * [AgentViewModel] now reads [connectionState]/[agentProfile] eagerly
     * (StateFlow properties, not suspend calls) to expose
     * `connectedAgentAddress`/`liveAgentProfile`, so a fake with no
     * network/Android dependency is needed even for tests that never touch
     * either derived property.
     */
    private class FakeConnectToAgentUseCase : ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract {
        override val connectionState: kotlinx.coroutines.flow.StateFlow<ai.openonion.oochat.domain.model.ConnectionState> =
            MutableStateFlow(ai.openonion.oochat.domain.model.ConnectionState.Disconnected)
        override val agentProfile: kotlinx.coroutines.flow.StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> =
            MutableStateFlow(null)
        override val dashboardHtml: kotlinx.coroutines.flow.StateFlow<String?> = MutableStateFlow(null)
        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?) = true
        override suspend fun switchConversation(conversationId: String?) {}
        override fun liveSessionIdFor(agentAddress: String): String? = null
        override fun observeEvents(): Flow<ai.openonion.oochat.domain.model.ChatEvent> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>?) {}
        override suspend fun respond(answer: String) {}
        override suspend fun interrupt() {}
        override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {}
        override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {}
        override suspend fun respondToPlanReview(message: String) {}
        override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {}
        override val approvalMode: kotlinx.coroutines.flow.StateFlow<ai.openonion.oochat.domain.model.ApprovalMode> =
            MutableStateFlow(ai.openonion.oochat.domain.model.ApprovalMode.DEFAULT)
        override val modePending: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun setMode(mode: ai.openonion.oochat.domain.model.ApprovalMode, turns: Int?) {}
        override fun retryNow() {}
        override suspend fun querySessionStatus() {}
        override suspend fun disconnect() {}
        override fun isConnected() = false
        override suspend fun reset() {}
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** A single hex digit repeated 64 times — passes the `0x` + 64 hex chars address format. */
    private fun validAddress(digit: Char) = "0x" + digit.toString().repeat(64)

    private fun testAgent(id: String = "agent-1", address: String = validAddress('a'), isActive: Boolean = true) =
        AgentProfile(
            id = id,
            address = address,
            name = "Test Agent $id",
            description = null,
            serverUrl = "https://relay.example.com",
            apiKey = null,
            avatarUrl = null,
            createdAt = 0L,
            lastConnectedAt = null,
            isActive = isActive,
            connectionMode = "relay"
        )

    private lateinit var agentRepository: FakeAgentRepository
    private lateinit var defaultAgentRepository: FakeDefaultAgentRepository
    private lateinit var discoveryRepository: FakeAgentDiscoveryRepository
    private lateinit var configRepository: FakeConnectionConfigRepository
    private lateinit var viewModel: AgentViewModel

    /** All fakes injected positionally, mirroring the production constructor order. */
    private fun buildViewModel() = AgentViewModel(
        Application(), agentRepository, defaultAgentRepository, discoveryRepository, configRepository,
        FakeConnectToAgentUseCase()
    )

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
        agentRepository = FakeAgentRepository()
        defaultAgentRepository = FakeDefaultAgentRepository()
        discoveryRepository = FakeAgentDiscoveryRepository()
        configRepository = FakeConnectionConfigRepository()
        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun `init loads agents from repository flow`() = runTest {
        agentRepository.agents.value = listOf(testAgent("a1"), testAgent("a2"))
        // Re-create so init() observes the pre-seeded flow.
        viewModel = buildViewModel()

        assertEquals(2, viewModel.uiState.value.agents.size)
    }

    @Test
    fun `createAgent with blank name sets error and does not call repository`() = runTest {
        viewModel.createAgent(name = "", address = "0x123", serverUrl = "https://x.com")

        assertEquals(0, agentRepository.createCount)
        assertEquals("All fields are required", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `createAgent with duplicate address sets error`() = runTest {
        val dupeAddress = validAddress('d')
        agentRepository.agents.value = listOf(testAgent(address = dupeAddress))

        viewModel.createAgent(name = "New", address = dupeAddress, serverUrl = "https://x.com")

        assertEquals(0, agentRepository.createCount)
        assertEquals("Agent with this address already exists", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `createAgent with malformed address sets error and does not call repository`() = runTest {
        viewModel.createAgent(name = "First", address = "0xnotarealaddress", serverUrl = "https://x.com")

        assertEquals(0, agentRepository.createCount)
        assertEquals(
            "Please enter a valid agent address (0x followed by 64 hex characters)",
            viewModel.uiState.value.error?.message
        )
    }

    @Test
    fun `createAgent with non-http server URL sets error and does not call repository`() = runTest {
        viewModel.createAgent(name = "First", address = validAddress('1'), serverUrl = "ftp://x.com")

        assertEquals(0, agentRepository.createCount)
        assertEquals(
            "Please enter a valid URL (e.g., https://server.com)",
            viewModel.uiState.value.error?.message
        )
    }

    @Test
    fun `createAgent with over-length server URL sets error and does not call repository`() = runTest {
        val longUrl = "https://" + "x".repeat(500) + ".com"

        viewModel.createAgent(name = "First", address = validAddress('2'), serverUrl = longUrl)

        assertEquals(0, agentRepository.createCount)
        assertEquals(
            "URL is too long (maximum 500 characters)",
            viewModel.uiState.value.error?.message
        )
    }

    @Test
    fun `createAgent success calls repository and sets as default when first agent`() = runTest {
        viewModel.createAgent(name = "First", address = validAddress('3'), serverUrl = "https://x.com")

        assertEquals(1, agentRepository.createCount)
        assertEquals(1, defaultAgentRepository.setCount)
    }

    @Test
    fun `createAgent success does not set default when agents already exist`() = runTest {
        agentRepository.agents.value = listOf(testAgent("existing"))
        viewModel = buildViewModel()

        viewModel.createAgent(name = "Second", address = validAddress('4'), serverUrl = "https://x.com")

        assertEquals(0, defaultAgentRepository.setCount)
    }

    @Test
    fun `updateAgent calls repository and refreshes selected agent`() = runTest {
        val agent = testAgent("a1")
        agentRepository.agents.value = listOf(agent)
        viewModel = buildViewModel()
        viewModel.selectAgent("a1")

        val updated = agent.copy(name = "Renamed")
        val result = viewModel.updateAgent(updated)

        assertTrue(result)
        assertEquals(1, agentRepository.updateCount)
        assertEquals("Renamed", viewModel.uiState.value.selectedAgent?.name)
    }

    @Test
    fun `updateAgent with invalid address rejects the save instead of reporting success`() = runTest {
        val agent = testAgent("a1")
        agentRepository.agents.value = listOf(agent)
        viewModel = buildViewModel()

        val invalid = agent.copy(address = "not-a-valid-address")
        val result = viewModel.updateAgent(invalid)

        assertFalse(result)
        assertEquals(0, agentRepository.updateCount)
        assertEquals(
            "Please enter a valid agent address (0x followed by 64 hex characters)",
            viewModel.uiState.value.error?.message
        )
    }

    @Test
    fun `deleteAgent clears default and selection when deleting the default agent`() = runTest {
        val agent = testAgent("a1")
        agentRepository.agents.value = listOf(agent)
        viewModel = buildViewModel()
        viewModel.selectAgent("a1")
        viewModel.setDefaultAgent("a1")

        viewModel.deleteAgent("a1")

        assertEquals(1, agentRepository.deleteCount)
        assertEquals("a1", agentRepository.lastDeletedId)
        assertNull(viewModel.uiState.value.defaultAgentId)
        assertNull(viewModel.uiState.value.selectedAgent)
    }

    @Test
    fun `setDefaultAgent updates uiState and delegates to repository`() = runTest {
        viewModel.setDefaultAgent("a1")

        assertEquals("a1", viewModel.uiState.value.defaultAgentId)
        assertEquals(1, defaultAgentRepository.setCount)
    }

    @Test
    fun `discoverAgents populates discoveredAgents on success`() = runTest {
        discoveryRepository.discoveredResult = listOf(testAgent("d1"), testAgent("d2"))

        viewModel.discoverAgents("https://relay.example.com")

        assertEquals(2, viewModel.uiState.value.discoveredAgents.size)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `discoverAgents sets discoveryError on failure, leaving error untouched`() = runTest {
        discoveryRepository.shouldThrow = true

        viewModel.discoverAgents("https://relay.example.com")

        assertTrue(viewModel.uiState.value.discoveredAgents.isEmpty())
        assertTrue(viewModel.uiState.value.discoveryError?.message?.startsWith("Discovery failed") == true)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `addDiscoveredAgent creates it and removes from discovered list`() = runTest {
        val discovered = testAgent("d1")
        discoveryRepository.discoveredResult = listOf(discovered)
        viewModel.discoverAgents("https://relay.example.com")

        viewModel.addDiscoveredAgent(discovered)

        assertEquals(1, agentRepository.createCount)
        assertTrue(viewModel.uiState.value.discoveredAgents.isEmpty())
    }

    @Test
    fun `connectToDiscoveredAgent persists a new agent, sets it default when first, and saves config`() = runTest {
        val discovered = testAgent(id = "0xnew", address = "0xnew")
        var onSavedCalled = false

        viewModel.connectToDiscoveredAgent(discovered, onSaved = { onSavedCalled = true })

        assertEquals(1, agentRepository.createCount)
        assertEquals(1, defaultAgentRepository.setCount)
        assertEquals("0xnew", defaultAgentRepository.defaultAgentId)
        assertEquals(discovered.serverUrl, configRepository.config?.serverUrl)
        assertEquals(discovered.address, configRepository.config?.agentAddress)
        assertTrue(onSavedCalled)
    }

    @Test
    fun `connectToDiscoveredAgent does not set default when agents already exist`() = runTest {
        agentRepository.agents.value = listOf(testAgent("existing", address = "0xexisting"))
        viewModel = buildViewModel()
        val discovered = testAgent(id = "0xnew2", address = "0xnew2")

        viewModel.connectToDiscoveredAgent(discovered, onSaved = {})

        assertEquals(1, agentRepository.createCount)
        assertEquals(0, defaultAgentRepository.setCount)
        assertEquals("0xnew2", configRepository.config?.agentAddress)
    }

    @Test
    fun `connectToDiscoveredAgent skips re-creating an already-saved agent but still saves config`() = runTest {
        val alreadySaved = testAgent(id = "0xdupe", address = "0xdupe")
        agentRepository.agents.value = listOf(alreadySaved)
        viewModel = buildViewModel()
        var onSavedCalled = false

        viewModel.connectToDiscoveredAgent(alreadySaved, onSaved = { onSavedCalled = true })

        assertEquals(0, agentRepository.createCount)
        assertEquals(0, defaultAgentRepository.setCount)
        assertEquals("0xdupe", configRepository.config?.agentAddress)
        assertTrue(onSavedCalled)
    }

    @Test
    fun `reorderAgents delegates the new order to the repository`() = runTest {
        agentRepository.agents.value = listOf(testAgent("a1"), testAgent("a2"), testAgent("a3"))
        viewModel = buildViewModel()

        viewModel.reorderAgents(listOf("a3", "a1", "a2"))

        assertEquals(listOf("a3", "a1", "a2"), agentRepository.reorderedIds)
        assertEquals(listOf("a3", "a1", "a2"), viewModel.uiState.value.agents.map { it.id })
    }

    @Test
    fun `clearError resets error state`() = runTest {
        viewModel.createAgent(name = "", address = "", serverUrl = "")
        assertTrue(viewModel.uiState.value.error != null)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }
}
