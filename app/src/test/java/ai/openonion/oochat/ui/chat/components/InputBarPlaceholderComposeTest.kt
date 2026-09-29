package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's placeholder is the only explanation the user gets for a
 * locked field, so it has to name the real reason. It used to be handed one
 * flag meaning "editable", which made every ordinary turn read "Connect to an
 * agent first" while the top bar's dot was green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarPlaceholderComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun bar(isConnected: Boolean, isAgentWorking: Boolean, awaitingOnboardCode: Boolean = false) {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = isConnected,
                        awaitingOnboardCode = awaitingOnboardCode,
                        isSending = false,
                        isAgentWorking = isAgentWorking,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
    }

    @Test
    fun `a busy agent on a live connection does not claim the connection is gone`() {
        bar(isConnected = true, isAgentWorking = true)

        composeRule.onNodeWithText("Connect to an agent first").assertDoesNotExist()
        composeRule.onNodeWithText("Agent is working…").assertIsDisplayed()
    }

    @Test
    fun `a genuinely disconnected composer keeps its existing wording`() {
        bar(isConnected = false, isAgentWorking = false)

        composeRule.onNodeWithText("Connect to an agent first").assertIsDisplayed()
    }

    @Test
    fun `an idle live connection invites a message`() {
        bar(isConnected = true, isAgentWorking = false)

        composeRule.onNodeWithText("Message…").assertIsDisplayed()
    }

    @Test
    fun `an onboarding gate still points at the invite code`() {
        // The socket has not reached Connected while the code is outstanding.
        bar(isConnected = false, isAgentWorking = false, awaitingOnboardCode = true)

        composeRule.onNodeWithText("Redeem the invite code above to start").assertIsDisplayed()
    }
}
