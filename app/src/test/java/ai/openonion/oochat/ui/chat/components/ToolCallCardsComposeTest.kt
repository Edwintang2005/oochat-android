package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ToolStatus
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
 * On-device bug this guards against: a tool card that told the user nothing
 * beyond its own name — no command for `bash`, no path for `edit` — so
 * approving (or reading back) a tool call was a guess. See
 * [ai.openonion.oochat.util.ToolArgsSummaryTest] for the pure
 * `summarizeToolCall()` coverage the generic fallback below is built on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolCallCardsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bash card shows the command`() {
        composeRule.setContent {
            ConnectOnionTheme {
                BashCallCard(
                    ChatItem.ToolCall(
                        id = "t1",
                        name = "bash",
                        args = mapOf("command" to "git status"),
                        status = ToolStatus.DONE
                    )
                )
            }
        }

        composeRule.onNodeWithText("git status").assertExists()
    }

    @Test
    fun `file diff card shows the file path`() {
        composeRule.setContent {
            ConnectOnionTheme {
                FileDiffCallCard(
                    ChatItem.ToolCall(
                        id = "t2",
                        name = "edit",
                        args = mapOf(
                            "file_path" to "src/App.tsx",
                            "old_string" to "a",
                            "new_string" to "b"
                        ),
                        status = ToolStatus.DONE
                    )
                )
            }
        }

        composeRule.onNodeWithText("src/App.tsx").assertExists()
    }

    @Test
    fun `generic fallback card renders no args line when args are null`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ToolCallBubble(
                    ChatItem.ToolCall(
                        id = "t3",
                        name = "custom_tool",
                        args = null,
                        status = ToolStatus.DONE
                    )
                )
            }
        }

        composeRule.onNodeWithText("custom_tool").assertExists()
        // No args to summarize — must not render an empty/blank second line.
        composeRule.onNodeWithText("=", substring = true).assertDoesNotExist()
    }

    @Test
    fun `generic fallback card shows a compact args summary for an unrecognised tool`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ToolCallBubble(
                    ChatItem.ToolCall(
                        id = "t4",
                        name = "custom_tool",
                        args = mapOf("target" to "prod"),
                        status = ToolStatus.DONE
                    )
                )
            }
        }

        composeRule.onNodeWithText("target=prod").assertExists()
    }
}
