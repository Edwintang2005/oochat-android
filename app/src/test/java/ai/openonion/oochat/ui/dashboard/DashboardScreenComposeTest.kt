package ai.openonion.oochat.ui.dashboard

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two things this screen has to say that its HTML payload cannot: whose
 * page this is, and whether the conversation it replaced is stuck waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(agentName: String? = null, chatAwaitsUser: Boolean = false, onBack: () -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                DashboardScreen(
                    html = null,
                    allowedSkills = emptyList(),
                    onRunSkill = {},
                    onNavigateBack = onBack,
                    agentName = agentName,
                    chatAwaitsUser = chatAwaitsUser
                )
            }
        }
    }

    @Test
    fun `the bar is titled with the agent whose page this is`() {
        setScreen(agentName = "Aria")

        composeRule.onNodeWithText("Aria").assertIsDisplayed()
    }

    @Test
    fun `an agent that has not named itself still gets a title that means something`() {
        // AGENT_PROFILE carries no name for plenty of agents. "Home" alone named
        // nothing at all, which is what this replaced.
        setScreen(agentName = null)

        composeRule.onNodeWithText("Agent home").assertIsDisplayed()
    }

    @Test
    fun `a conversation waiting on the user says so on this screen`() {
        setScreen(chatAwaitsUser = true)

        composeRule.onNodeWithText("The conversation is waiting for you").assertIsDisplayed()
    }

    @Test
    fun `nothing is claimed while the conversation is running normally`() {
        setScreen(chatAwaitsUser = false)

        composeRule.onNodeWithText("The conversation is waiting for you").assertDoesNotExist()
    }

    @Test
    fun `the waiting notice is the way back to the conversation`() {
        var backCount = 0
        setScreen(chatAwaitsUser = true, onBack = { backCount++ })

        composeRule.onNodeWithText("The conversation is waiting for you").performClick()

        assertEquals(1, backCount)
    }

    /**
     * [DashboardScreenContent] is [DashboardScreen] with `navigationBlocked`
     * hoisted out — the only way to drive it directly, since the real path
     * (a WebView's `shouldOverrideUrlLoading`) never fires under
     * Robolectric's stub WebView.
     */
    private fun setContent(
        chatAwaitsUser: Boolean = false,
        navigationBlocked: Boolean = false,
        onDismissNavigationBlocked: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                DashboardScreenContent(
                    document = "<h1>hi</h1>",
                    allowedSkills = emptyList(),
                    onRunSkill = {},
                    onNavigateBack = {},
                    agentName = "Aria",
                    chatAwaitsUser = chatAwaitsUser,
                    navigationBlocked = navigationBlocked,
                    onNavigationBlocked = {},
                    onDismissNavigationBlocked = onDismissNavigationBlocked
                )
            }
        }
    }

    @Test
    fun `a blocked link shows an informational bar, not a blank page`() {
        setContent(navigationBlocked = true)

        composeRule.onNodeWithText("Links in this page open here — navigation away is blocked").assertIsDisplayed()
    }

    @Test
    fun `a blocked link no longer replaces the page with a full-screen notice`() {
        // This is what the bar replaced — a full-screen EmptyStateMessage
        // titled "Navigation blocked" that lost the whole page over a link tap.
        setContent(navigationBlocked = true)

        composeRule.onNodeWithText("Navigation blocked").assertDoesNotExist()
    }

    @Test
    fun `dismissing the info bar clears it`() {
        var dismissed = false
        setContent(navigationBlocked = true, onDismissNavigationBlocked = { dismissed = true })

        composeRule.onNodeWithContentDescription("Dismiss").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun `the waiting bar stays pinned above the informational one`() {
        setContent(chatAwaitsUser = true, navigationBlocked = true)

        val waiting = composeRule.onNodeWithText("The conversation is waiting for you").fetchSemanticsNode()
        val info = composeRule.onNodeWithText("Links in this page open here — navigation away is blocked").fetchSemanticsNode()
        assertTrue(
            "the waiting bar should sit above the informational one",
            waiting.boundsInRoot.top < info.boundsInRoot.top
        )
    }
}
