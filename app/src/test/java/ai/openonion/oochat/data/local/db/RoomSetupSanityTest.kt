package ai.openonion.oochat.data.local.db

import ai.openonion.oochat.data.local.db.entity.AgentEntity
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
 * Sanity check, not feature coverage: proves Robolectric's shadow Context is
 * sufficient to stand up a real in-memory [AppDatabase] and round-trip a row
 * through it, before relying on that same setup in the heavier
 * repository-level Room tests (Agent/Session/Message/DefaultAgent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSetupSanityTest {

    private lateinit var database: AppDatabase

    private fun sampleAgent(id: String) = AgentEntity(
        id = id,
        address = "0xabc",
        name = "Test Agent",
        serverUrl = "https://example.com",
        createdAt = 1000L
    )

    @Before
    fun openInMemoryDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `a row written through insertAgent comes back unchanged`() = runTest {
        val agent = sampleAgent("a1")
        database.agentDao().insertAgent(agent)

        assertEquals(agent, database.agentDao().getAgentById("a1"))
    }

    @Test
    fun `looking up an id that was never inserted yields null`() = runTest {
        assertNull(database.agentDao().getAgentById("missing"))
    }
}
