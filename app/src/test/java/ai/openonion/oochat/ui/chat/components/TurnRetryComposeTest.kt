package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Retry starts a whole turn, and nothing below the button serialises it —
 * `retryFailedTurn` sends with `waitForRunningTurn = false`, which bypasses
 * the outgoing send lock. Two taps therefore meant two INPUT frames and two
 * answers, so the latch that prevents the second one is behaviour worth
 * holding still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TurnRetryComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun failedTurn(id: String = "turn-1") = ChatItem.Turn(
        id = id,
        thinking = ChatItem.Thinking(
            id = id,
            status = ThinkingStatus.ERROR,
            content = "Connection lost mid-turn"
        )
    )

    @Test
    fun `tapping Retry twice starts one turn, not two`() {
        var retries = 0
        composeRule.setContent {
            ConnectOnionTheme {
                TurnBubble(item = failedTurn(), onRetry = { retries++ })
            }
        }

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `Retry goes disabled once tapped, so the dead button reads as dead`() {
        composeRule.setContent {
            ConnectOnionTheme {
                TurnBubble(item = failedTurn(), onRetry = {})
            }
        }

        composeRule.onNodeWithText("Retry").performClick()

        composeRule.onNodeWithText("Retry").assertIsNotEnabled()
    }
}
