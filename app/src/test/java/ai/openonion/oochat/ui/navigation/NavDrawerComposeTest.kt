package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawer's account card names the agent this session is connected to and
 * nothing else. The balance it used to carry as a permanent read-only row is
 * now only said when it is low enough to act on, beside the composer — see
 * [ai.openonion.oochat.ui.chat.components.LowBalanceBar].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavDrawerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setDrawer() {
        composeRule.setContent {
            ConnectOnionTheme {
                NavDrawer(
                    agentSections = emptyList(),
                    activeEntryId = null,
                    themeMode = ThemeMode.SYSTEM,
                    onNewChatForAgent = {},
                    onSelectAgentSession = { _, _ -> },
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                    onOpenAgentList = {},
                    onOpenSettings = {},
                    onThemeModeChange = {},
                    connectedAgentAddress = "0x1a2b3c4d5e6f",
                )
            }
        }
    }

    @Test
    fun `the account card names the connected agent`() {
        setDrawer()

        composeRule.onNodeWithText("Agent").assertExists()
        composeRule.onNodeWithText("0x1a2b…5e6f").assertExists()
    }

    @Test
    fun `the account card carries no balance row at all`() {
        setDrawer()

        composeRule.onNodeWithText("Balance").assertDoesNotExist()
    }
}
