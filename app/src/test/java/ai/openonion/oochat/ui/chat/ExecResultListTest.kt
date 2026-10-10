package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ExecOutcome
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExecResultListTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `success result is displayed`() {
        composeRule.setContent {
            ExecResultList(
                items = listOf(
                    ExecUiItem(
                        execId = "exec-1",
                        tool = "read_file",
                        outcome = ExecOutcome.Success("file contents")
                    )
                ),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("read_file").assertExists()
        composeRule.onNodeWithText("Success").assertExists()
        composeRule.onNodeWithText("file contents").assertExists()
    }

    @Test
    fun `error result is displayed`() {
        composeRule.setContent {
            ExecResultList(
                items = listOf(
                    ExecUiItem(
                        execId = "exec-2",
                        tool = "delete_file",
                        outcome = ExecOutcome.Error("Permission denied")
                    )
                ),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("delete_file").assertExists()
        composeRule.onNodeWithText("Error").assertExists()
        composeRule.onNodeWithText("Permission denied").assertExists()
    }

    @Test
    fun `pending result displays running state`() {
        composeRule.setContent {
            ExecResultList(
                items = listOf(
                    ExecUiItem(
                        execId = "exec-3",
                        tool = "read_file"
                    )
                ),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("Running…").assertExists()
    }

    @Test
    fun `empty result list displays no cards`() {
        composeRule.setContent {
            ExecResultList(items = emptyList(), onDismiss = {})
        }

        composeRule.onNodeWithText("Direct tool execution").assertDoesNotExist()
        composeRule.onNodeWithText("Running…").assertDoesNotExist()
    }

    @Test
    fun `long output shows a truncated preview`() {
        composeRule.setContent {
            ExecResultList(
                items = listOf(
                    ExecUiItem(
                        execId = "exec-long",
                        tool = "read_file",
                        outcome = ExecOutcome.Success(
                            "start-" + "x".repeat(MAX_EXEC_RESULT_CHARS) + "-hidden-tail"
                        )
                    )
                ),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("output truncated", substring = true).assertExists()
        composeRule.onNodeWithText("hidden-tail").assertDoesNotExist()
    }

    @Test
    fun `dismiss button reports the card id`() {
        var dismissedId: String? = null
        composeRule.setContent {
            ExecResultList(
                items = listOf(
                    ExecUiItem(
                        execId = "exec-dismiss",
                        tool = "read_file",
                        outcome = ExecOutcome.Success("file contents")
                    )
                ),
                onDismiss = { dismissedId = it }
            )
        }

        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals("exec-dismiss", dismissedId)
    }
}
