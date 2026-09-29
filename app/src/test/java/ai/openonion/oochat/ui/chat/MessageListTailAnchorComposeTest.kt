package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The list has to stay on the newest message as a turn fills in. The case that
 * was missed: a reply does not arrive as a new row. `mergeIntoPendingTurn`
 * writes it into the Turn placeholder that was created before the tool cards,
 * so the row that grows is in the middle of the list — the item count and the
 * last item both stay exactly as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageListTailAnchorComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pendingTurn = ChatItem.Turn(
        id = "turn-1",
        thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING)
    )

    private val toolCall = ChatItem.ToolCall(
        id = "tool-1",
        name = "bash",
        args = mapOf("command" to "TAIL-OF-THE-LIST"),
        status = ToolStatus.DONE,
        result = "ok"
    )

    private fun repliedTurn(lines: Int) = ChatItem.Turn(
        id = "turn-1",
        thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.DONE),
        agent = ChatItem.Agent(
            id = "turn-1",
            content = (1..lines).joinToString("\n") { "reply line $it" }
        )
    )

    /**
     * [height] models the viewport the IME leaves behind: the composer is
     * lifted and this weighted list is what shrinks (see ChatScreen's
     * safeDrawing inset), so a raised keyboard is simply a shorter list.
     */
    private fun show(initial: List<ChatItem>, height: Dp = 360.dp): (List<ChatItem>) -> Unit {
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
                    modifier = Modifier.requiredHeight(height)
                )
            }
        }
        composeRule.waitForIdle()
        return { next -> items = next; composeRule.waitForIdle() }
    }

    /** The bottom edge of the list's own box, not of the whole screen. */
    private fun assertTailInsideViewport(text: String = "TAIL-OF-THE-LIST") {
        val tail = composeRule.onNodeWithText(text, substring = true)
            .getUnclippedBoundsInRoot().bottom
        val viewport = composeRule.onNode(hasScrollAction()).getUnclippedBoundsInRoot().bottom
        assertTrue(
            "The newest row ends at $tail but the list ends at $viewport",
            tail <= viewport
        )
    }

    @Test
    fun `a reply folded in above the tool cards still leaves the list at the bottom`() {
        val update = show(listOf(ChatItem.User("u1", "run the tests"), pendingTurn, toolCall))

        // Same size, same last item — only the middle row grew.
        update(listOf(ChatItem.User("u1", "run the tests"), repliedTurn(30), toolCall))

        assertTailInsideViewport()
    }

    @Test
    fun `a reader scrolled up into history is left where they are`() {
        val history = (1..12).map { ChatItem.User("u$it", "history message $it") }
        val update = show(history + pendingTurn + toolCall)

        composeRule.onNode(hasScrollAction()).performScrollToIndex(1)
        composeRule.waitForIdle()
        update(history + repliedTurn(30) + toolCall)

        composeRule.onNodeWithText("history message 1").assertIsDisplayed()
        composeRule.onNodeWithText("TAIL-OF-THE-LIST", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the user's own send lands at the bottom from up in the history`() {
        val history = (1..12).map { ChatItem.User("u$it", "history message $it") }
        val update = show(history + pendingTurn + toolCall)

        composeRule.onNode(hasScrollAction()).performScrollToIndex(1)
        composeRule.waitForIdle()
        update(history + pendingTurn + toolCall + ChatItem.User("sent", "JUST-SENT-THIS"))

        assertTailInsideViewport("JUST-SENT-THIS")
    }

    /** Same send, but into the short viewport a raised keyboard leaves. */
    @Test
    fun `the user's own send lands at the bottom with the keyboard up`() {
        val history = (1..12).map { ChatItem.User("u$it", "history message $it") }
        val update = show(history + pendingTurn + toolCall, height = 200.dp)

        composeRule.onNode(hasScrollAction()).performScrollToIndex(1)
        composeRule.waitForIdle()
        update(history + pendingTurn + toolCall + ChatItem.User("sent", "JUST-SENT-THIS"))

        assertTailInsideViewport("JUST-SENT-THIS")
    }
}
