package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A reply the server replayed carries no metadata — its wire transcript is
 * role and content only, so duration and tokens never existed for it. It
 * still gets a footer, holding the tick alone, so it does not read as a
 * different kind of message from the ones this device watched arrive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredTurnFooterComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setTurn(item: ChatItem.Turn) {
        composeRule.setContent {
            ConnectOnionTheme { TurnBubble(item = item) }
        }
    }

    @Test
    fun `a restored reply still gets the tick`() {
        setTurn(
            ChatItem.Turn(
                id = "asst-restored",
                agent = ChatItem.Agent(id = "asst-restored", content = "Replayed from the server.")
            )
        )

        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
    }

    @Test
    fun `a restored reply claims no numbers it never had`() {
        setTurn(
            ChatItem.Turn(
                id = "asst-restored",
                agent = ChatItem.Agent(id = "asst-restored", content = "Replayed from the server.")
            )
        )

        composeRule.onNodeWithText("tokens", substring = true).assertDoesNotExist()
        // The footer holds the tick and nothing else — no duration either.
        composeRule.onNodeWithText("·", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a turn this device watched keeps its figures`() {
        setTurn(
            ChatItem.Turn(
                id = "asst-live",
                agent = ChatItem.Agent(id = "asst-live", content = "Seen live."),
                thinking = ChatItem.Thinking(
                    id = "think-live",
                    status = ThinkingStatus.DONE,
                    durationMs = 2000.0,
                    tokensTotal = 52
                )
            )
        )

        composeRule.onNodeWithText("2s · 52 tokens", substring = true).assertExists()
    }

    @Test
    fun `a turn with no reply at all is not footered as finished`() {
        // An in-flight placeholder has its own running footer; what must not
        // happen is a bare tick appearing under nothing.
        setTurn(ChatItem.Turn(id = "asst-empty"))

        composeRule.onNodeWithContentDescription("Done").assertDoesNotExist()
    }
}
