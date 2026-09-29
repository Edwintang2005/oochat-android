package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tapping the transcript's background puts the composer's keyboard away, and
 * nothing else does. The exemption is bought with gesture consumption rather
 * than a whitelist, so this covers what that is really worth: the invite-code
 * field and the button beside it, both living inside a transcript card, have
 * to keep their own taps.
 *
 * Robolectric cannot see the real IME, so every assertion is on focus — which
 * is the thing being changed anyway (hiding the IME alone would leave the
 * caret in the field and the pill in its focused border).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptTapDismissesKeyboardComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val gate = ChatItem.OnboardRequired(id = "gate-1", methods = listOf("invite_code"))

    /** The transcript above a stand-in composer, as ChatScreen stacks them. */
    private fun show(items: List<ChatItem>) {
        composeRule.setContent {
            var composerText by remember { mutableStateOf("") }
            Column {
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
                    modifier = Modifier.requiredHeight(420.dp)
                )
                BasicTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("composer")
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun focusComposer() {
        composeRule.onNodeWithTag("composer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer").assertIsFocused()
    }

    /** Below the last row but inside the list: transcript background, nothing else. */
    private fun tapListBackground() {
        composeRule.onNode(hasScrollAction()).performTouchInput {
            click(Offset(width / 2f, height - 8f))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a tap on the transcript background clears the composer's focus`() {
        show(listOf(ChatItem.User("u1", "hello")))
        focusComposer()

        tapListBackground()

        composeRule.onNodeWithTag("composer").assertIsNotFocused()
    }

    @Test
    fun `a tap on a text field inside a card lands in that field instead`() {
        show(listOf(gate))
        focusComposer()

        composeRule.onNodeWithText("Enter your invite code").performClick()
        composeRule.waitForIdle()

        // Focused, so the card's field took the tap: had it reached the
        // transcript, clearFocus would have run after and left this empty.
        composeRule.onNodeWithText("Enter your invite code").assertIsFocused()
    }

    @Test
    fun `a tap on a button inside a card leaves the composer focused`() {
        show(listOf(gate))
        focusComposer()

        composeRule.onNodeWithContentDescription("Show invite code").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("composer").assertIsFocused()
    }

    @Test
    fun `scrolling the transcript leaves the composer focused`() {
        show((1..20).map { ChatItem.User("u$it", "history message $it") })
        focusComposer()

        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("composer").assertIsFocused()
    }
}
