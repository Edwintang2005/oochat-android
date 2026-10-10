package ai.openonion.oochat.ui.chat

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
class ExecToolPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `execute sends selected tool and arguments`() {
        var receivedTool = ""
        var receivedArgs = ""

        composeRule.setContent {
            ExecToolPanel(
                tools = listOf("read_file", "delete_file"),
                onExecute = { tool, argsJson ->
                    receivedTool = tool
                    receivedArgs = argsJson
                }
            )
        }

        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("read_file").performClick()
        composeRule.onNodeWithText("delete_file").performClick()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("""{"path":"README.md"}""")

        composeRule.onNodeWithText("Execute").performClick()

        assertEquals("delete_file", receivedTool)
        assertEquals("""{"path":"README.md"}""", receivedArgs)
    }

    @Test
    fun `panel starts collapsed`() {
        composeRule.setContent {
            ExecToolPanel(
                tools = listOf("read_file"),
                onExecute = { _, _ -> }
            )
        }

        composeRule.onNodeWithText("Show").assertExists()
        composeRule.onNodeWithText("Arguments JSON").assertDoesNotExist()
    }

    @Test
    fun `show reveals available tools and arguments fields`() {
        composeRule.setContent {
            ExecToolPanel(
                tools = listOf("read_file", "delete_file"),
                onExecute = { _, _ -> }
            )
        }

        composeRule.onNodeWithText("Show").performClick()

        composeRule.onNodeWithText("read_file").assertExists()
        composeRule.onNodeWithText("Arguments JSON").assertExists()
        composeRule.onNodeWithText("Enter a JSON object, for example: {\"command\":\"pwd\"}").assertExists()
    }

    @Test
    fun `invalid arguments cannot be executed`() {
        composeRule.setContent {
            ExecToolPanel(
                tools = listOf("shell"),
                onExecute = { _, _ -> error("invalid JSON should not be sent") }
            )
        }

        composeRule.onNodeWithText("Show").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("{invalid")

        composeRule.onNodeWithText("Arguments must be valid JSON").assertExists()
        composeRule.onNodeWithText("Execute").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("{}")
        composeRule.onNodeWithText("Execute").assertIsEnabled()
    }
}
