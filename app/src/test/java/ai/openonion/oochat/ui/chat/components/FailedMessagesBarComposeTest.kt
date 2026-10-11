package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The retry control by the composer: there only while something has failed,
 * one Retry per failed message, each naming the message it resends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FailedMessagesBarComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun user(
        id: String,
        content: String,
        state: UserMessageState = UserMessageState.FAILED,
        images: List<String>? = null,
        files: List<ChatFileAttachment>? = null
    ) = ChatItem.User(id = id, content = content, images = images, files = files, state = state)

    private fun setBar(failed: List<ChatItem.User>, onRetry: (String) -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme { FailedMessagesBar(failed = failed, onRetry = onRetry) }
        }
    }

    // ── Visibility ───────────────────────────────────────────────────

    @Test
    fun `nothing failed, nothing rendered`() {
        setBar(failed = emptyList())

        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `only failed user messages are offered`() {
        val items: List<ChatItem> = listOf(
            user("sent", "fine", UserMessageState.SENT),
            user("queued", "waiting", UserMessageState.QUEUED),
            user("acking", "sending", UserMessageState.AWAITING_ACK),
            user("f1", "first"),
            ChatItem.Agent(id = "a", content = "reply"),
            user("f2", "second")
        )

        assertEquals(listOf("f1", "f2"), items.failedUserMessages().map { it.id })
    }

    @Test
    fun `one failed message shows a retry naming it`() {
        setBar(failed = listOf(user("m1", "hello there")))

        composeRule.onNodeWithText("Not sent: hello there").assertExists()
        composeRule.onNodeWithContentDescription("Retry sending: hello there")
            .assertExists()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // A single failure needs no count.
        composeRule.onNodeWithText("messages not sent", substring = true).assertDoesNotExist()
    }

    // ── Tapping ──────────────────────────────────────────────────────

    @Test
    fun `tapping retry resends that message`() {
        val retried = mutableListOf<String>()
        setBar(failed = listOf(user("m1", "hello")), onRetry = { retried += it })

        composeRule.onNodeWithContentDescription("Retry sending: hello").performClick()

        assertEquals(listOf("m1"), retried)
    }

    @Test
    fun `the control clears once its message is resent`() {
        // Mirrors ChatScreen: the bar is derived from the transcript, and a
        // resend moves the message out of FAILED (ChatViewModel queues it).
        composeRule.setContent {
            var items by remember { mutableStateOf(listOf(user("m1", "hello"))) }
            ConnectOnionTheme {
                FailedMessagesBar(
                    failed = items.failedUserMessages(),
                    onRetry = { id ->
                        items = items.map { if (it.id == id) it.copy(state = UserMessageState.QUEUED) else it }
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Retry sending: hello").performClick()

        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithText("Not sent", substring = true).assertDoesNotExist()
    }

    // ── Several failures ─────────────────────────────────────────────

    @Test
    fun `several failures each get their own retry, and each resends its own message`() {
        val retried = mutableListOf<String>()
        setBar(
            failed = listOf(user("m1", "first"), user("m2", "second")),
            onRetry = { retried += it }
        )

        composeRule.onNodeWithText("2 messages not sent").assertExists()
        composeRule.onNodeWithContentDescription("Retry sending: second").performClick()
        composeRule.onNodeWithContentDescription("Retry sending: first").performClick()

        assertEquals(listOf("m2", "m1"), retried)
    }

    // ── What a row quotes ────────────────────────────────────────────

    @Test
    fun `the preview is the first non-blank line of the text`() {
        assertEquals("real line", failedMessagePreview(user("m", "\n  \n real line \nmore")))
    }

    @Test
    fun `an attachment-only message is named by what was attached`() {
        val file = ChatFileAttachment(name = "report.pdf", path = "file:///x/report.pdf")
        assertEquals("Image", failedMessagePreview(user("m", "", images = listOf("a"))))
        assertEquals("3 images", failedMessagePreview(user("m", "", images = listOf("a", "b", "c"))))
        assertEquals("report.pdf", failedMessagePreview(user("m", "", files = listOf(file))))
        assertEquals("2 files", failedMessagePreview(user("m", "", files = listOf(file, file))))
        assertEquals("2 attachments", failedMessagePreview(user("m", "", images = listOf("a"), files = listOf(file))))
    }

    @Test
    fun `a held-too-long image message is offered with a label TalkBack can read`() {
        setBar(failed = listOf(user("img", "", images = listOf("file:///x/a.jpg"))))

        composeRule.onNodeWithContentDescription("Retry sending: Image").assertExists()
    }

    // ── The bubble keeps its own retry ───────────────────────────────

    @Test
    fun `the failed bubble still says so and still retries on tap`() {
        val failed = user("m1", "hello")
        val fromBubble = mutableListOf<String>()
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    UserBubble(item = failed, timestamp = null, onResend = { fromBubble += failed.id })
                    FailedMessagesBar(failed = listOf(failed), onRetry = {})
                }
            }
        }

        composeRule.onNodeWithText("Not sent · Tap to try again").assertExists()
        composeRule.onNodeWithContentDescription("Not sent. Tap to try again.").performClick()

        assertEquals(listOf("m1"), fromBubble)
    }
}
