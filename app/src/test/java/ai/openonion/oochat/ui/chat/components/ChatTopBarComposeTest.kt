package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bug this guards against: a long title used to wrap the bar past
 * 200dp (title on its own line, a 3-part "Connected · model · $balance"
 * subtitle wrapping under it). These pin the fixed height and the row-1/
 * row-2 layout the redesign depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTopBarComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bar stays 64dp regardless of title length`() {
        composeRule.setContent {
            ConnectOnionTheme {
                // ConnectOnionTheme's root Surface fills the window with fixed
                // constraints; a plain Column between it and the bar gives the
                // bar loose constraints instead, so its own height(64.dp) is
                // what's measured rather than getting stretched to the window.
                Column {
                    ChatTopBar(
                        title = "A very long conversation title that would have wrapped the old three-line subtitle onto a third line",
                        connectionState = ConnectionState.Connected(address = "0xabc"),
                        isConnected = true,
                        onClearChat = {},
                        onNavigateToLogs = {},
                        onMenuClick = {},
                        modelName = "gemini-2.5-flash",
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat_top_bar").assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun `the dot and title sit on the same baseline`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ChatTopBar(
                    title = "research-assistant",
                    connectionState = ConnectionState.Connected(address = "0xabc"),
                    isConnected = true,
                    onClearChat = {},
                    onNavigateToLogs = {},
                    onMenuClick = {},
                )
            }
        }

        val dot = composeRule.onNodeWithContentDescription("Connected").fetchSemanticsNode()
        val title = composeRule.onNodeWithText("research-assistant").fetchSemanticsNode()
        val delta = kotlin.math.abs(dot.boundsInRoot.center.y - title.boundsInRoot.center.y)
        assertTrue("dot and title are $delta px apart vertically, not on one row", delta < 8f)
    }

    @Test
    fun `the model line indents to the title, not the dot`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ChatTopBar(
                    title = "research-assistant",
                    connectionState = ConnectionState.Connected(address = "0xabc"),
                    isConnected = true,
                    onClearChat = {},
                    onNavigateToLogs = {},
                    onMenuClick = {},
                    modelName = "gemini-2.5-flash",
                )
            }
        }

        val title = composeRule.onNodeWithText("research-assistant").fetchSemanticsNode()
        val model = composeRule.onNodeWithText("gemini-2.5-flash").fetchSemanticsNode()
        val delta = kotlin.math.abs(title.boundsInRoot.left - model.boundsInRoot.left)
        assertTrue("model line is $delta px off the title's left edge, not aligned to it", delta < 1f)
    }

    @Test
    fun `connecting, reconnecting and error read differently through the dot alone`() {
        var state by mutableStateOf<ConnectionState>(ConnectionState.Connecting)
        composeRule.setContent {
            ConnectOnionTheme {
                ChatTopBar(
                    title = "research-assistant",
                    connectionState = state,
                    isConnected = false,
                    onClearChat = {},
                    onNavigateToLogs = {},
                    onMenuClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connecting").assertExists()

        state = ConnectionState.Reconnecting
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Reconnecting").assertExists()

        state = ConnectionState.Error(message = "boom")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Connection failed").assertExists()
    }
}
