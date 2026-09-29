package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
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
 * On-device bug this guards against: [ApprovalCard] showed only the tool
 * name ("edit") while awaiting a decision, and its post-decision
 * confirmation bar collapsed to "Allowed: edit — running…" — no file, no
 * command, no way to recall what was actually approved once the card
 * scrolled past. See [ai.openonion.oochat.util.ToolArgsSummaryTest]
 * for the underlying pure-function coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionCardsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pending approval card shows the argument being approved`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-1",
                        tool = "edit",
                        arguments = mapOf("file_path" to "src/App.tsx")
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("src/App.tsx").assertExists()
    }

    @Test
    fun `pending approval card renders no extra summary line when arguments are empty`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-2",
                        tool = "load_guide",
                        arguments = emptyMap()
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        // The tool-name pill (and its title fallback) still show, just
        // nothing extra underneath them for the empty argument map.
        composeRule.onNodeWithText("=", substring = true).assertDoesNotExist()
    }

    @Test
    fun `decided approval card's confirmation receipt still names what was approved`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-3",
                        tool = "edit",
                        arguments = mapOf("file_path" to "src/App.tsx"),
                        decision = ApprovalDecision(approved = true, scope = "once", mode = null)
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Allowed: edit", substring = true).assertExists()
        composeRule.onNodeWithText("src/App.tsx", substring = true).assertExists()
    }
}
