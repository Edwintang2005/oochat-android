package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.domain.model.AgentProfile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AgentRepositoryImpl] against a real in-memory Room
 * database and a real (Robolectric-backed, falls back to plain
 * SharedPreferences) [AgentSecureConfigRepository] — see
 * [ai.openonion.oochat.data.local.db.RoomSetupSanityTest] for
 * why Room works under Robolectric despite it having no AndroidKeyStore shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: AgentRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AgentRepositoryImpl(db.agentDao(), AgentSecureConfigRepositoryImpl(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun profile(id: String = "a1", address: String = "0xabc", apiKey: String? = "secret-key") = AgentProfile(
        id = id,
        address = address,
        name = "Test Agent",
        serverUrl = "https://example.com",
        apiKey = apiKey,
        createdAt = 1000L
    )

    @Test
    fun `createAgent then getAgentById returns the profile with its api key injected back`() = runTest {
        repository.createAgent(profile())

        val loaded = repository.getAgentById("a1")

        assertEquals("0xabc", loaded?.address)
        assertEquals("secret-key", loaded?.apiKey)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `api key is stored in secure storage, not in the Room entity`() = runTest {
        repository.createAgent(profile())

        // The raw Room row must never carry the plaintext key.
        val rawEntity = db.agentDao().getAgentById("a1")
        assertNull(rawEntity?.apiKey)
    }

    @Test
    fun `getAgentByAddress finds the same agent by its address`() = runTest {
        repository.createAgent(profile())

        val loaded = repository.getAgentByAddress("0xabc")

        assertEquals("a1", loaded?.id)
    }

    @Test
    fun `getAgentById returns null for an unknown id`() = runTest {
        assertNull(repository.getAgentById("missing"))
    }

    @Test
    fun `updateAgent persists changes and updates the secure api key`() = runTest {
        repository.createAgent(profile())

        repository.updateAgent(profile().copy(name = "Renamed", apiKey = "new-key"))

        val loaded = repository.getAgentById("a1")
        assertEquals("Renamed", loaded?.name)
        assertEquals("new-key", loaded?.apiKey)
    }

    @Test
    fun `deleteAgent removes both the Room row and the secure api key`() = runTest {
        repository.createAgent(profile())

        repository.deleteAgent("a1")

        assertNull(repository.getAgentById("a1"))
        assertNull(AgentSecureConfigRepositoryImpl(ApplicationProvider.getApplicationContext()).getApiKey("a1"))
    }

    @Test
    fun `getDefaultAgent returns the first active agent`() = runTest {
        repository.createAgent(profile(id = "a1", address = "0x1").copy(isActive = false))
        repository.createAgent(profile(id = "a2", address = "0x2").copy(isActive = true))

        assertEquals("a2", repository.getDefaultAgent()?.id)
    }
}
