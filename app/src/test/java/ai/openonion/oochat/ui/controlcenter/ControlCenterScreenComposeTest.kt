package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlCenterScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64))

    private fun setContent(
        app: ControlCenterApp? = this.app,
        supported: Boolean = true,
        stopped: Boolean = false,
        onReload: () -> Unit = {},
        agentName: String? = "Aria",
        chatAwaitsUser: Boolean = false,
        navigationBlocked: Boolean = false,
        onBack: () -> Unit = {},
        onDismissNavigationBlocked: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterScreenContent(
                    app = app,
                    supported = supported,
                    stopped = stopped,
                    onReload = onReload,
                    content = { Text("app ${it.revision.take(9)}") },
                    onNavigateBack = onBack,
                    agentName = agentName,
                    chatAwaitsUser = chatAwaitsUser,
                    navigationBlocked = navigationBlocked,
                    onDismissNavigationBlocked = onDismissNavigationBlocked
                )
            }
        }
    }

    @Test
    fun `the published app fills the screen under the agent's name`() {
        setContent()

        composeRule.onNodeWithText("Aria").assertIsDisplayed()
        composeRule.onNodeWithText("app sha256:aa").assertIsDisplayed()
    }

    @Test
    fun `an agent that has not named itself still gets a title`() {
        setContent(agentName = null)

        composeRule.onNodeWithText("Agent home").assertIsDisplayed()
    }

    @Test
    fun `an app the Host has not sent for this connection is not shown`() {
        setContent(app = null)

        composeRule.onNodeWithText("No Control Center yet").assertIsDisplayed()
        composeRule.onNodeWithText("app sha256:aa").assertDoesNotExist()
    }

    @Test
    fun `a WebView too old to host the app says so`() {
        setContent(supported = false)

        composeRule.onNodeWithText("Control Center unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("app sha256:aa").assertDoesNotExist()
    }

    @Test
    fun `an app whose page stopped offers a reload instead of reloading by itself`() {
        var reloads = 0
        setContent(stopped = true, onReload = { reloads++ })

        composeRule.onNodeWithText("Control Center stopped").assertIsDisplayed()
        composeRule.onNodeWithText("app sha256:aa").assertDoesNotExist()
        composeRule.onNodeWithText("Reload").performClick()

        assertEquals(1, reloads)
    }

    @Test
    fun `Home opens the Control Center where it can be hosted, or where there is nothing else`() {
        var asked = 0
        val supported = { asked++; true }
        val unsupported = { false }

        assertTrue(homeOpensControlCenter(hasApp = true, hasDashboard = true, supported = supported))
        assertFalse(homeOpensControlCenter(hasApp = true, hasDashboard = true, supported = unsupported))
        // Without a legacy page, the screen is the only place to say the WebView is too old.
        assertTrue(homeOpensControlCenter(hasApp = true, hasDashboard = false, supported = unsupported))
        assertFalse(homeOpensControlCenter(hasApp = false, hasDashboard = true, supported = supported))
        assertFalse(homeOpensControlCenter(hasApp = false, hasDashboard = false, supported = supported))
        assertEquals("the WebView was loaded when no choice needed it", 1, asked)
    }

    @Test
    fun `a conversation waiting on the user says so, and is the way back`() {
        var backs = 0
        setContent(chatAwaitsUser = true, onBack = { backs++ })

        composeRule.onNodeWithText("The conversation is waiting for you").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `a blocked navigation shows the dismissible bar over the app`() {
        var dismissed = 0
        setContent(navigationBlocked = true, onDismissNavigationBlocked = { dismissed++ })

        composeRule.onNodeWithText("app sha256:aa").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
    }
}
