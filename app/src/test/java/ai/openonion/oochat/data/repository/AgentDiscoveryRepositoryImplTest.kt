package ai.openonion.oochat.data.repository

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AgentDiscoveryRepositoryImplTest {

    private lateinit var repository: AgentDiscoveryRepositoryImpl

    @Before
    fun setUp() {
        repository = AgentDiscoveryRepositoryImpl()
    }

    // ── discoverFromRelay ──────────────────────────────────────────

    @Test
    fun `discoverFromRelay throws for an unreachable host instead of silently returning empty`() = runTest {
        // A real network failure must propagate (not resolve to emptyList())
        // so callers -- AgentViewModel.discoverAgents() -- can tell "the
        // relay legitimately has zero agents" apart from "the request failed".
        try {
            repository.discoverFromRelay("https://nonexistent.invalid.local")
            fail("Expected discoverFromRelay to throw for an unreachable host")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun `discoverFromRelay returns one row per address, whatever the relay lists`() = runTest {
        // The address becomes the row id and the results list is keyed on it,
        // so a repeated address crashes the LazyColumn rather than showing a
        // duplicate. The relay promises no such uniqueness, and this is server
        // data — the app cannot choose not to receive it.
        val server = MockWebServer()
        val address = "0x" + "ab".repeat(32)
        server.enqueue(
            MockResponse().setBody(
                """{"agents":[
                  |{"address":"$address","profile":{"alias":"first"}},
                  |{"address":"$address","profile":{"alias":"second"}}]}"""
                    .trimMargin()
            )
        )
        server.start()

        val agents = repository.discoverFromRelay(server.url("/").toString())

        assertEquals(1, agents.size)
        assertEquals("first", agents[0].name)
        server.shutdown()
    }
}
