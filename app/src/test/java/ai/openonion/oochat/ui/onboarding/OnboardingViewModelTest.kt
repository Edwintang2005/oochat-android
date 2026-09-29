package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.ui.agent.components.AgentFormState
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OnboardingViewModel]. Covers:
 *  - [OnboardingViewModel.connect]'s validate() happy path and every sad/edge
 *    path (blank fields, malformed URL, "address looks like a URL" confusion,
 *    over-length fields)
 *  - a successful connect() persists the trimmed config and invokes onSaved
 *  - prefill-from-saved-config behavior: [OnboardingViewModel.prefillConfig]
 *    / [OnboardingViewModel.isPrefillLoaded] for both a returning user (saved
 *    config exists) and a genuine first run (none does)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private class FakeConnectionConfigRepository(
        var config: ConnectionConfig? = null
    ) : ConnectionConfigRepository {
        var savedConfig: ConnectionConfig? = null
        var saveCount = 0
        /** When set, getConfig() suspends here — lets a test observe pre-load state. */
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun getConfig(): ConnectionConfig? {
            blockUntil?.await()
            return config
        }
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) {
            saveCount++
            savedConfig = config
            this.config = config
        }
        override suspend fun deleteConfig() { config = null }
        override suspend fun hasConfig(): Boolean = config != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var configRepository: FakeConnectionConfigRepository

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        configRepository = FakeConnectionConfigRepository()
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun createVm() = OnboardingViewModel(
        application = Application(),
        configRepository = configRepository
    )

    private fun validForm(
        serverUrl: String = "https://relay.example.com",
        agentAddress: String = "0xabc123",
        apiKey: String = ""
    ) = AgentFormState(serverUrl = serverUrl, agentAddress = agentAddress, apiKey = apiKey)

    // ── Prefill from a previously-saved config ─────────────────────────

    @Test
    fun `a first run with no saved config leaves prefillConfig null once loaded`() = runTest(testDispatcher) {
        configRepository.config = null

        val vm = createVm()
        testScheduler.runCurrent()

        assertTrue("the load must have resolved", vm.isPrefillLoaded.value)
        assertNull("no config was ever saved, so there is nothing to prefill", vm.prefillConfig.value)
    }

    @Test
    fun `a returning user's saved config is exposed as prefillConfig once loaded`() = runTest(testDispatcher) {
        val saved = ConnectionConfig(
            serverUrl = "https://relay.example.com",
            apiKey = "secret-key",
            agentAddress = "0xdeadbeef"
        )
        configRepository.config = saved

        val vm = createVm()
        testScheduler.runCurrent()

        assertTrue(vm.isPrefillLoaded.value)
        assertNotNull(vm.prefillConfig.value)
        assertEquals(saved, vm.prefillConfig.value)
    }

    @Test
    fun `isPrefillLoaded starts false before the load resolves`() {
        // Block getConfig() mid-flight so the init{} load can't complete yet.
        configRepository.blockUntil = CompletableDeferred()

        val vm = createVm()

        assertFalse(
            "must distinguish 'nothing saved' from 'haven't checked yet' so the " +
                "screen doesn't render a form before it knows whether to prefill it",
            vm.isPrefillLoaded.value
        )
    }

    // ── connect(): happy path ────────────────────────────────────────────

    @Test
    fun `connect with a valid form saves the trimmed config and invokes onSaved`() = runTest(testDispatcher) {
        val vm = createVm()
        var savedCalled = false
        val form = AgentFormState(
            serverUrl = "  https://relay.example.com/  ",
            agentAddress = "  0xabc123  ",
            apiKey = "  my-key  "
        )

        vm.connect(form, onSaved = { savedCalled = true })
        testScheduler.runCurrent()

        assertTrue("onSaved must fire on a successful save", savedCalled)
        assertEquals(1, configRepository.saveCount)
        val saved = configRepository.savedConfig
        assertNotNull(saved)
        assertEquals("https://relay.example.com", saved!!.serverUrl)
        assertEquals("0xabc123", saved.agentAddress)
        assertEquals("my-key", saved.apiKey)
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun `connect with a blank optional apiKey saves it as null`() = runTest(testDispatcher) {
        val vm = createVm()

        vm.connect(validForm(apiKey = "   "), onSaved = {})
        testScheduler.runCurrent()

        assertNull(configRepository.savedConfig?.apiKey)
    }

    // ── connect(): invalid form is a silent no-op (button is disabled) ──

    @Test
    fun `connect does nothing when the form itself is invalid`() = runTest(testDispatcher) {
        val vm = createVm()
        var savedCalled = false
        // Blank agentAddress makes AgentFormState.isValid false.
        val form = AgentFormState(serverUrl = "https://relay.example.com", agentAddress = "")

        vm.connect(form, onSaved = { savedCalled = true })
        testScheduler.runCurrent()

        assertFalse(savedCalled)
        assertEquals(0, configRepository.saveCount)
        assertNull("the form guards this case itself, so no error message is needed", vm.errorMessage.value)
    }

    // ── connect(): validate() sad/edge paths ────────────────────────────

    @Test
    fun `connect rejects a server URL over 500 characters`() = runTest(testDispatcher) {
        val vm = createVm()
        var savedCalled = false
        val longUrl = "https://" + "a".repeat(500) + ".com"

        vm.connect(validForm(serverUrl = longUrl), onSaved = { savedCalled = true })
        testScheduler.runCurrent()

        assertFalse(savedCalled)
        assertEquals("URL is too long (maximum 500 characters)", vm.errorMessage.value)
        assertEquals(0, configRepository.saveCount)
    }

    @Test
    fun `connect rejects an agent address over 200 characters`() = runTest(testDispatcher) {
        val vm = createVm()
        val longAddress = "0x" + "a".repeat(200)

        vm.connect(validForm(agentAddress = longAddress), onSaved = {})
        testScheduler.runCurrent()

        assertEquals("Agent address is too long (maximum 200 characters)", vm.errorMessage.value)
        assertEquals(0, configRepository.saveCount)
    }

    @Test
    fun `connect rejects a server URL missing a recognized scheme`() = runTest(testDispatcher) {
        val vm = createVm()

        vm.connect(validForm(serverUrl = "relay.example.com"), onSaved = {})
        testScheduler.runCurrent()

        assertEquals("Please enter a valid URL (e.g., https://server.com)", vm.errorMessage.value)
        assertEquals(0, configRepository.saveCount)
    }

    @Test
    fun `connect rejects an agent address that looks like a URL instead of a hex address`() = runTest(testDispatcher) {
        val vm = createVm()
        // Leading whitespace deliberately evades AgentFormState.isValid's own
        // (untrimmed) startsWith check, so this exercises validate()'s own
        // URL-confusion branch inside connect() rather than being turned away
        // earlier by the form-level guard.
        val form = validForm(agentAddress = "  https://not-an-address.com")

        vm.connect(form, onSaved = {})
        testScheduler.runCurrent()

        assertEquals(
            "Agent address should be a hex address (0x...), not a URL. Please select from the dropdown.",
            vm.errorMessage.value
        )
        assertEquals(0, configRepository.saveCount)
    }

    @Test
    fun `connect accepts every supported URL scheme`() = runTest(testDispatcher) {
        val vm = createVm()

        for (scheme in listOf("https://", "http://", "wss://", "ws://")) {
            configRepository.saveCount = 0
            vm.connect(validForm(serverUrl = "${scheme}relay.example.com"), onSaved = {})
            testScheduler.runCurrent()

            assertNull("scheme $scheme should be accepted", vm.errorMessage.value)
            assertEquals(1, configRepository.saveCount)
        }
    }

    @Test
    fun `a later valid connect clears a previous error message`() = runTest(testDispatcher) {
        val vm = createVm()
        vm.connect(validForm(serverUrl = "relay.example.com"), onSaved = {})
        testScheduler.runCurrent()
        assertNotNull(vm.errorMessage.value)

        vm.connect(validForm(), onSaved = {})
        testScheduler.runCurrent()

        assertNull(vm.errorMessage.value)
    }
}
