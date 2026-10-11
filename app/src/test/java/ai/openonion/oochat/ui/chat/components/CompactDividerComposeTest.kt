package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
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
 * A `compact` frame lands in the timeline as a thin divider, not a card: it
 * is a note about the agent's own memory, not something to answer. Rendered
 * through [ChatItemBubble] so the test covers the same dispatch the chat
 * list uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactDividerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setItem(item: ChatItem.CompactItem) {
        composeRule.setContent {
            ConnectOnionTheme {
                ChatItemBubble(
                    item = item,
                    timestamp = null,
                    onRespond = {},
                    onApprove = { _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = {},
                    onUlwResponse = { _, _, _ -> }
                )
            }
        }
    }

    @Test
    fun `a compaction in progress says so and does not claim anything was lost yet`() {
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.COMPACTING))

        composeRule.onNodeWithText("Compacting context…").assertIsDisplayed()
        composeRule.onNodeWithText(COMPACT_DONE_HINT).assertDoesNotExist()
    }

    @Test
    fun `a finished compaction shows the rounded before and after percentages`() {
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.DONE, contextBefore = 90.0, contextAfter = 32.951))

        composeRule.onNodeWithText("Context compacted: 90% → 33%").assertIsDisplayed()
    }

    @Test
    fun `a finished compaction explains that earlier detail may be gone`() {
        // The point of the divider: the user should know why the agent no
        // longer quotes what was said above it.
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.DONE, contextBefore = 90.0, contextAfter = 33.0))

        composeRule.onNodeWithText(COMPACT_DONE_HINT).assertIsDisplayed()
    }

    @Test
    fun `a finished compaction without percentages falls back to the server message`() {
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.DONE, message = "compacted 40 messages"))

        composeRule.onNodeWithText("compacted 40 messages").assertIsDisplayed()
        composeRule.onNodeWithText(COMPACT_DONE_HINT).assertIsDisplayed()
    }

    @Test
    fun `a finished compaction with neither percentages nor message still names the event`() {
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.DONE, message = " "))

        composeRule.onNodeWithText("Context compacted").assertIsDisplayed()
    }

    @Test
    fun `a failed compaction shows the server error and no memory-loss hint`() {
        // Nothing was summarised, so telling the user detail was lost would be false.
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.ERROR, error = "context too large"))

        composeRule.onNodeWithText("context too large").assertIsDisplayed()
        composeRule.onNodeWithText(COMPACT_DONE_HINT).assertDoesNotExist()
    }

    @Test
    fun `a failed compaction with no error text falls back to a generic label`() {
        setItem(ChatItem.CompactItem(id = "c1", status = CompactStatus.ERROR))

        composeRule.onNodeWithText("Compaction failed").assertIsDisplayed()
    }
}
