package ai.openonion.oochat.ui.recovery

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RecoveryViewModel]. The `Application()` stub is safe
 * because [ConnectionConfigRepository] is an in-memory fake, so no Android
 * API is ever hit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {

    private class FakeConnectionConfigRepository(
        var config: ConnectionConfig? = null
    ) : ConnectionConfigRepository {
        /** When set, getConfig() suspends here — lets a test observe pre-load state. */
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun getConfig(): ConnectionConfig? {
            blockUntil?.await()
            return config
        }
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) { this.config = config }
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

    private fun createVm() = RecoveryViewModel(
        application = Application(),
        configRepository = configRepository
    )

    @Test
    fun `init loads the saved config for display`() = runTest(testDispatcher) {
        val saved = ConnectionConfig(
            serverUrl = "https://relay.example.com",
            apiKey = "secret",
            agentAddress = "0xabc123"
        )
        configRepository.config = saved

        val vm = createVm()
        testScheduler.runCurrent()

        assertEquals(saved, vm.config.value)
    }

    @Test
    fun `init with no saved config leaves config null`() = runTest(testDispatcher) {
        configRepository.config = null

        val vm = createVm()
        testScheduler.runCurrent()

        assertNull(vm.config.value)
    }

    @Test
    fun `config starts null before the init load resolves`() {
        // Block getConfig() mid-flight so the init{} load can't complete yet,
        // proving RecoveryScreen's AnimatedVisibility(config != null) starts
        // hidden rather than momentarily showing stale/default data.
        configRepository.config = ConnectionConfig(serverUrl = "https://relay.example.com")
        configRepository.blockUntil = CompletableDeferred()

        val vm = createVm()

        assertNull(vm.config.value)
    }
}
