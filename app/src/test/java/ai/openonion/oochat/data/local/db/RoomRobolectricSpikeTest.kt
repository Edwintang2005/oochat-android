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

/** Spike test: confirms Robolectric's Context shadow is enough to build and query a real in-memory Room database, unblocking the planned Room-backed repository tests (AgentRepositoryImpl, SessionRepositoryImpl, MessageRepositoryImpl, DefaultAgentRepository). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRobolectricSpikeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertAgent then getAgentById round-trips a real row`() = runTest {
        val agent = AgentEntity(
            id = "a1",
            address = "0xabc",
            name = "Test Agent",
            serverUrl = "https://example.com",
            createdAt = 1000L
        )

        db.agentDao().insertAgent(agent)
        val loaded = db.agentDao().getAgentById("a1")

        assertEquals(agent, loaded)
    }

    @Test
    fun `getAgentById returns null for an unknown id`() = runTest {
        assertNull(db.agentDao().getAgentById("missing"))
    }
}
