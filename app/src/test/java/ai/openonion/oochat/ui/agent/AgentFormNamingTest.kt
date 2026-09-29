package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.ui.agent.components.AgentFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent Name is optional. It was mandatory once, which left Save greyed out
 * with nothing on screen saying why — an agent picked off the discovery radar
 * arrives with an address and no name at all.
 */
class AgentFormNamingTest {

    private val address = "0xf5a1cabdd84eef6bff9d649ced40678756e817056797e1dd073eec80b42c65ef"

    @Test
    fun `a blank name does not block submission`() {
        val state = AgentFormState(serverUrl = "https://oo.openonion.ai", agentAddress = address)

        assertTrue(state.isSubmittable)
    }

    @Test
    fun `a missing address still blocks submission`() {
        val state = AgentFormState(serverUrl = "https://oo.openonion.ai", agentAddress = "  ")

        assertFalse(state.isSubmittable)
    }

    @Test
    fun `a missing server URL still blocks submission`() {
        val state = AgentFormState(serverUrl = "", agentAddress = address)

        assertFalse(state.isSubmittable)
    }

    @Test
    fun `an unnamed agent is labelled by its address`() {
        assertEquals("Agent 0xf5a1…65ef", defaultAgentName(address))
    }

    /**
     * The label has to survive a short or empty address too — nothing here
     * should be able to produce a blank row in the agent list.
     */
    @Test
    fun `the fallback label is never blank`() {
        assertEquals("Agent 0xab", defaultAgentName("0xab"))
        assertEquals("Agent ", defaultAgentName(""))
    }
}
