package ai.openonion.oochat.ui.agent.components

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.ui.agent.DiscoveryPhase
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On-device complaint this guards against: the 220dp radar kept its space
 * even once the user had started typing an address by hand, and it had to
 * yield without swallowing the taps on its own saved-agent dropdown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedDiscoveryPanelComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val saved = AgentProfile(
        id = "a1",
        address = "0xsavedaddress0001",
        name = "Saved Bot",
        serverUrl = "https://oo.openonion.ai",
        createdAt = 0L
    )

    private val discovered = AgentProfile(
        id = "d1",
        address = "0xdiscovered0002",
        name = "Radar Bot",
        serverUrl = "https://oo.openonion.ai",
        createdAt = 0L
    )

    private lateinit var readFormState: () -> AgentFormState

    private fun setPanel(savedAgentDropdown: Boolean = true) {
        composeRule.setContent {
            ConnectOnionTheme {
                var formState by remember { mutableStateOf(AgentFormState()) }
                readFormState = { formState }
                EmbeddedDiscoveryPanel(
                    formState = formState,
                    onFormStateChange = { formState = it },
                    savedAgents = listOf(saved),
                    discoveredAgents = listOf(discovered),
                    discoveryPhase = DiscoveryPhase.Done,
                    error = null,
                    onDiscover = {},
                    onSelectDiscovered = {},
                    savedAgentDropdown = savedAgentDropdown,
                    submitButton = {}
                )
            }
        }
    }

    /** The address field is the only editable node once Server URL is skipped by index. */
    private fun addressField() = composeRule.onAllNodes(hasSetTextAction())[1]

    @Test
    fun `the radar is expanded until the address field is touched`() {
        setPanel()

        composeRule.onNodeWithText("1 AGENT FOUND").assertIsDisplayed()
        composeRule.onNodeWithText("Show").assertDoesNotExist()
    }

    @Test
    fun `focusing the address field collapses the radar`() {
        setPanel()

        addressField().performClick()

        composeRule.onNodeWithText("1 AGENT FOUND").assertDoesNotExist()
        composeRule.onNodeWithText("1 agent found").assertIsDisplayed()
        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun `a non-empty address keeps the radar collapsed`() {
        setPanel()

        addressField().performTextInput("0xmanual")

        composeRule.onNodeWithText("1 AGENT FOUND").assertDoesNotExist()
        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun `Show puts the radar back, and it stays back while the results are only read`() {
        setPanel()

        addressField().performTextInput("0xmanual")
        composeRule.onNodeWithText("Show").performClick()

        // Re-opened and left alone: nothing about the address changed, so
        // nothing takes the 220dp away again.
        composeRule.onNodeWithText("1 AGENT FOUND").assertIsDisplayed()
        composeRule.onNodeWithText("Show").assertDoesNotExist()
    }

    @Test
    fun `typing again after Show collapses the radar a second time`() {
        setPanel()

        addressField().performTextInput("0xmanual")
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("1 AGENT FOUND").assertIsDisplayed()

        addressField().performTextInput("more")

        // Going back to typing is the plainest statement that discovery is not
        // wanted. Keyed on `addressInUse`, this could never fire — that flag was
        // already true, so the re-opened radar stayed up over the field forever.
        composeRule.onNodeWithText("1 AGENT FOUND").assertDoesNotExist()
        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun `tapping a saved-agent suggestion still lands the address in the field`() {
        setPanel()

        // Opening the menu focuses the field, which is also what collapses the
        // radar — the tap on the suggestion must survive that.
        addressField().performClick()
        composeRule.onNodeWithText("Saved Bot").performClick()

        assertEquals(
            "picking a suggestion must fill the address field",
            saved.address,
            readFormState().agentAddress
        )
    }

    @Test
    fun `Add Agent's address field offers no saved-agent dropdown`() {
        setPanel(savedAgentDropdown = false)

        addressField().performClick()

        composeRule.onNodeWithText("Saved Bot").assertDoesNotExist()
    }

    @Test
    fun `the onboarding panel keeps its saved-agent dropdown`() {
        setPanel(savedAgentDropdown = true)

        addressField().performClick()

        composeRule.onNodeWithText("Saved Bot").assertIsDisplayed()
    }
}
