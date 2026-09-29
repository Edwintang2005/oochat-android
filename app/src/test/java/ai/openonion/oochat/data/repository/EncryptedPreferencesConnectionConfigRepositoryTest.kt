package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.ConnectionConfig
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * [EncryptedPreferencesConnectionConfigRepository] already goes through
 * [ai.openonion.oochat.data.local.SafePreferencesWrapper], so unlike
 * [ai.openonion.oochat.crypto.KeyManager] before its own Robolectric
 * fix, no production change was needed to test it under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedPreferencesConnectionConfigRepositoryTest {

    private lateinit var repository: EncryptedPreferencesConnectionConfigRepository

    private val defaultConfig = ConnectionConfig(serverUrl = "https://relay.example.com", agentAddress = "0xabc")

    @Before
    fun createRepository() {
        repository = EncryptedPreferencesConnectionConfigRepository(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `an unsaved repository has no config`() = runTest {
        assertNull(repository.getConfig())
        assertFalse(repository.hasConfig())
    }

    @Test
    fun `saved config is readable through getConfig, hasConfig, and observeConfig`() = runTest {
        repository.saveConfig(defaultConfig)

        assertEquals(defaultConfig, repository.getConfig())
        assertTrue(repository.hasConfig())
        assertEquals(defaultConfig, repository.observeConfig().first())
    }

    @Test
    fun `a second repository instance sees what the first one saved`() = runTest {
        // Confirms the real init-time load-from-SharedPreferences path, not
        // just this instance's own in-memory flow reflecting its own write.
        repository.saveConfig(defaultConfig)

        val secondInstance = EncryptedPreferencesConnectionConfigRepository(ApplicationProvider.getApplicationContext())

        assertEquals(defaultConfig, secondInstance.getConfig())
    }

    @Test
    fun `deleteConfig wipes storage and the observed flow together`() = runTest {
        repository.saveConfig(defaultConfig)

        repository.deleteConfig()

        assertNull(repository.getConfig())
        assertFalse(repository.hasConfig())
        assertNull(repository.observeConfig().first())
    }

    @Test
    fun `updateLastConnected touches only the timestamp field`() = runTest {
        repository.saveConfig(defaultConfig)
        repository.updateLastConnected(12345L)

        val updated = repository.getConfig()
        assertEquals(12345L, updated?.lastConnected)
        assertEquals(defaultConfig.agentAddress, updated?.agentAddress)
    }

    @Test
    fun `updateLastConnected does nothing without a saved config to patch`() = runTest {
        repository.updateLastConnected(12345L)

        assertNull(repository.getConfig())
    }

    @Test
    fun `updateAgentAddress touches only the address field`() = runTest {
        repository.saveConfig(defaultConfig.copy(agentAddress = "0xold"))
        repository.updateAgentAddress("0xnew")

        val updated = repository.getConfig()
        assertEquals("0xnew", updated?.agentAddress)
        assertEquals(defaultConfig.serverUrl, updated?.serverUrl)
    }

    @Test
    fun `updateAgentAddress can clear the address back to null`() = runTest {
        repository.saveConfig(defaultConfig.copy(agentAddress = "0xold"))
        repository.updateAgentAddress(null)

        assertNull(repository.getConfig()?.agentAddress)
    }
}
