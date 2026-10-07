package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawer's "Add agent" lands on this screen in create mode (Agent list →
 * `+` → AGENT_EDIT/new). Its address field takes the address from the radar
 * above it, so it carries no saved-agent dropdown; Edit mode, which has no
 * radar, keeps one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentFormScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val saved = AgentProfile(
        id = "a1",
        address = "0xsavedaddress0001",
        name = "Saved Bot",
        serverUrl = "https://oo.openonion.ai",
        createdAt = 0L
    )

    private fun setScreen(existingAgent: AgentProfile?, onSave: (AgentProfile) -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                AgentFormScreen(
                    existingAgent = existingAgent,
                    savedAgents = listOf(saved),
                    onSave = onSave,
                    onCancel = {}
                )
            }
        }
    }

    /** Name, Server URL, then Agent Address — the same order in both modes. */
    private fun addressField() = composeRule.onAllNodes(hasSetTextAction())[2].performScrollTo()

    private fun serverUrlField() = composeRule.onAllNodes(hasSetTextAction())[1].performScrollTo()

    @Test
    fun `Add agent's address field offers no saved-agent dropdown`() {
        setScreen(existingAgent = null)

        addressField().performClick()

        composeRule.onNodeWithText("Saved Bot").assertDoesNotExist()
    }

    @Test
    fun `Edit agent's address field keeps its saved-agent dropdown`() {
        // Same address so the dropdown's live filter (it matches against
        // whatever is already in the field) still lists it, but a different
        // name — otherwise the Name field's own text answers the assertion.
        setScreen(existingAgent = saved.copy(id = "b2", name = "Edited Bot"))

        addressField().performClick()

        composeRule.onNodeWithText("Saved Bot").assertIsDisplayed()
    }

    // -- what Save hands over ------------------------------------------

    private val address = "0x" + "f5a1".repeat(16)

    private fun save() = composeRule.onNodeWithText("Save agent").performScrollTo().performClick()

    @Test
    fun `an agent added by address alone is named after it and routed through the relay`() {
        var result: AgentProfile? = null
        setScreen(existingAgent = null) { result = it }

        // Pasted addresses arrive with whitespace more often than not.
        addressField().performTextInput("  $address \n")
        save()

        val agent = result ?: error("Save handed nothing over")
        assertEquals(address, agent.address)
        assertEquals(defaultAgentName(address), agent.name)
        assertEquals("https://oo.openonion.ai", agent.serverUrl)
        assertEquals("relay", agent.connectionMode)
        assertNull("a blank API key is none, not an empty string", agent.apiKey)
    }

    @Test
    fun `choosing Direct saves a direct agent at the URL typed`() {
        var result: AgentProfile? = null
        setScreen(existingAgent = null) { result = it }

        composeRule.onNodeWithText("Direct Connection").performScrollTo().performClick()
        // The toggle clears Server URL from a LaunchedEffect; typing before it
        // settles would have the URL wiped straight back out.
        composeRule.waitForIdle()
        serverUrlField().performTextInput(" https://agent.example.com ")
        addressField().performTextInput(address)
        save()

        val agent = result ?: error("Save handed nothing over")
        assertEquals("direct", agent.connectionMode)
        assertEquals("https://agent.example.com", agent.serverUrl)
        assertEquals(address, agent.address)
    }
}
