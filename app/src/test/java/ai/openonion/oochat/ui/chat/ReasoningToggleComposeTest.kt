package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A turn shows only its newest thought; the toggle above it reads the rest.
 * Driven through [MessageList], which owns the open/closed choice, because
 * that choice has to outlive the bubble: each new thought replaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReasoningToggleComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun thought(id: String, text: String) =
        ChatItem.Thinking(id = id, status = ThinkingStatus.DONE, content = text, kind = "reflect")

    private val user = ChatItem.User(id = "u1", content = "build it")

    private fun show(initial: List<ChatItem>): (List<ChatItem>) -> Unit {
        var items by mutableStateOf(initial)
        composeRule.setContent {
            ConnectOnionTheme {
                MessageList(
                    items = items,
                    timestamps = items.associate { it.id to 1_700_000_000_000L },
                    wasCleared = false,
                    onRespond = {},
                    onApprove = { _, _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = {},
                    onUlwResponse = { _, _, _ -> },
                    renderMarkdown = false,
                    conversationKey = "conv-1",
                    modifier = Modifier.requiredHeight(800.dp)
                )
            }
        }
        composeRule.waitForIdle()
        return { items = it }
    }

    @Test
    fun `only the newest thought shows until the toggle is opened`() {
        show(listOf(user, thought("t1", "First idea"), thought("t2", "Second idea")))

        composeRule.onNodeWithText("Second idea").assertIsDisplayed()
        composeRule.onNodeWithText("First idea").assertDoesNotExist()

        composeRule.onNodeWithText("Show all 2 thoughts").performClick()

        composeRule.onNodeWithText("First idea").assertIsDisplayed()
        composeRule.onNodeWithText("Hide earlier thoughts").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("First idea").assertDoesNotExist()
    }

    @Test
    fun `an open toggle stays open when the agent has another thought`() {
        val update = show(listOf(user, thought("t1", "First idea"), thought("t2", "Second idea")))
        composeRule.onNodeWithText("Show all 2 thoughts").performClick()

        update(listOf(user, thought("t1", "First idea"), thought("t2", "Second idea"), thought("t3", "Third idea")))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("First idea").assertIsDisplayed()
        composeRule.onNodeWithText("Second idea").assertIsDisplayed()
        composeRule.onNodeWithText("Third idea").assertIsDisplayed()
        composeRule.onNodeWithText("Hide earlier thoughts").assertIsDisplayed()
    }

    @Test
    fun `a turn with one thought has no toggle`() {
        show(listOf(user, thought("t1", "Only idea")))

        composeRule.onNodeWithText("Only idea").assertIsDisplayed()
        composeRule.onNodeWithText("Show all", substring = true).assertDoesNotExist()
    }
}
