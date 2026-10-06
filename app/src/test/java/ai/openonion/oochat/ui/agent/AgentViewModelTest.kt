package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.AgentDiscoveryRepository
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepositoryContract
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.knownIssue
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

    // -- what counts as a 0x address -----------------------------------
    //
    // A ConnectOnion address is an Ed25519 public key: 0x + 64 hex, 66 chars.

    private val formatError = "Please enter a valid agent address (0x followed by 64 hex characters)"

    @Test
    fun `createAgent rejects every near-miss of a 0x address`() = runTest {
        val nearMisses = mapOf(
            "63 hex characters" to "0x" + "a".repeat(63),
            "65 hex characters" to "0x" + "a".repeat(65),
            "no 0x prefix" to "a".repeat(64),
            "an Ethereum-length address" to "0x" + "a".repeat(40),
            "a non-hex character" to "0x" + "a".repeat(63) + "g",
            "an upper-case 0X prefix" to "0X" + "a".repeat(64)
        )

        for ((what, address) in nearMisses) {
            // The error sticks until cleared, so each case must earn its own.
            viewModel.clearError()
            assertFalse(what, viewModel.createAgent(name = "A", address = address, serverUrl = "https://x.com"))
            assertEquals(what, formatError, viewModel.uiState.value.error?.message)
        }
        assertEquals(0, agentRepository.createCount)
    }

    @Test
    fun `createAgent tells someone who pasted a URL into the address field what it wants instead`() = runTest {
        assertFalse(viewModel.createAgent(name = "A", address = "https://agent.example.com", serverUrl = "https://x.com"))

        val message = viewModel.uiState.value.error?.message.orEmpty()
        assertTrue(message, message.contains("not a URL"))
        assertTrue(message, message.contains("0x"))
    }

    @Test
    fun `createAgent rejects an address over 200 characters`() = runTest {
        // Which of the two messages wins is not the point - the length cap and
        // the format check both describe it - only that nothing is saved.
        assertFalse(viewModel.createAgent(name = "A", address = "0x" + "a".repeat(199), serverUrl = "https://x.com"))

        assertTrue(viewModel.uiState.value.error != null)
        assertEquals(0, agentRepository.createCount)
    }

    @Test
    fun `createAgent accepts upper-case hex digits`() = runTest {
        assertTrue(viewModel.createAgent(name = "A", address = "0x" + "AbCd".repeat(16), serverUrl = "https://x.com"))
    }

    @Test
    fun `a valid address is saved with the name, server and routing chosen`() = runTest {
        val address = validAddress('5')

        assertTrue(
            viewModel.createAgent(
                name = "Research",
                address = address,
                serverUrl = "https://agent.example.com",
                apiKey = "key-1",
                connectionMode = "direct"
            )
        )

        val saved = agentRepository.agents.value.single()
        assertEquals(address, saved.address)
        assertEquals("Research", saved.name)
        assertEquals("https://agent.example.com", saved.serverUrl)
        assertEquals("key-1", saved.apiKey)
        assertEquals("direct", saved.connectionMode)
        assertTrue(saved.isActive)
        assertEquals("the first agent becomes the default", saved.id, viewModel.uiState.value.defaultAgentId)
    }

    @Test
    fun `createAgent accepts both relay and direct server URL schemes`() = runTest {
        // A different address for each, or the duplicate check would reject the rest.
        val urlsAndAddresses = listOf(
            "https://x.com" to validAddress('a'),
            "http://10.0.0.2:8000" to validAddress('b'),
            "wss://x.com" to validAddress('c'),
            "ws://x.com" to validAddress('d')
        )

        for ((url, address) in urlsAndAddresses) {
            assertTrue(url, viewModel.createAgent(name = url, address = address, serverUrl = url))
        }
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

    // -- known issues in adding agents ---------------------------------
    //
    // The fake repository matches addresses exactly, as Room's
    // `WHERE address = :address` and the entity's unique index do.

    @Test
    fun `known issue - the same address in another letter case is saved as a second agent`() = runTest {
        agentRepository.agents.value = listOf(testAgent(address = "0x" + "ab".repeat(32)))
        viewModel = buildViewModel()

        val created = viewModel.createAgent(name = "Again", address = "0x" + "AB".repeat(32), serverUrl = "https://x.com")

        knownIssue("addresses are compared case-sensitively, so 0xAB... and 0xab... are two agents") {
            assertFalse("the same key in capitals is the same agent", created)
        }
    }

    @Test
    fun `known issue - editing an agent onto another agent's address is not caught`() = runTest {
        val taken = validAddress('1')
        agentRepository.agents.value = listOf(testAgent("a1", address = taken), testAgent("a2", address = validAddress('2')))
        viewModel = buildViewModel()

        val updated = viewModel.updateAgent(testAgent("a2", address = taken))

        // In Room the unique index turns this into a SQLiteConstraintException
        // inside AgentEditScreenWrapper's unguarded launch.
        knownIssue("updateAgent has no duplicate-address check, unlike createAgent") {
            assertFalse("updateAgent reported success", updated)
            assertEquals("Agent with this address already exists", viewModel.uiState.value.error?.message)
        }
    }

    @Test
    fun `known issue - adding a discovered agent that is already saved saves it again`() = runTest {
        val address = validAddress('3')
        agentRepository.agents.value = listOf(testAgent("saved-uuid", address = address))
        viewModel = buildViewModel()

        // Discovery ids its results by address, so this is a different id.
        viewModel.addDiscoveredAgent(testAgent(id = address, address = address))

        // In Room the insert is REPLACE on a unique address, so the saved row
        // is deleted and re-created under the new id: its API key (kept by
        // id in secure storage) and its place in the list are lost.
        knownIssue("addDiscoveredAgent skips the already-saved check connectToDiscoveredAgent has") {
            assertEquals(0, agentRepository.createCount)
        }
    }

    @Test
    fun `known issue - the first agent added from discovery does not become the default`() = runTest {
        viewModel.addDiscoveredAgent(testAgent(id = "d1", address = validAddress('4')))

        knownIssue("addDiscoveredAgent skips the first-agent default createAgent and connectToDiscoveredAgent apply") {
            assertEquals("d1", defaultAgentRepository.defaultAgentId)
        }
    }

    @Test
    fun `known issue - Add Agent tells someone who pasted a URL to use a dropdown it does not have`() = runTest {
        viewModel.createAgent(name = "A", address = "https://agent.example.com", serverUrl = "https://x.com")
        val message = viewModel.uiState.value.error?.message.orEmpty()

        // The Add Agent form has no saved-agent dropdown (see
        // AgentFormScreenComposeTest); only Edit does. createAgent backs Add.
        knownIssue("the URL-as-address error ends \"Please select from the dropdown.\", which Add Agent does not have") {
            assertFalse(message, message.contains("dropdown"))
        }
    }
}
