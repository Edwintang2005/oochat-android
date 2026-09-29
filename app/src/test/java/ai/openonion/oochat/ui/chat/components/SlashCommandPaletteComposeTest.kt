package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The palette wired into the composer: what opens it, what closes it, and
 * where it sits. The ranking itself is covered without Compose in
 * [SlashCommandMatcherTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlashCommandPaletteComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val skills = listOf(
        AgentSkill("linkedin-engagement", "Engage LinkedIn feed posts using verified browser workflows"),
        AgentSkill("web-scraping", "Read a web page and extract structured text from it"),
        // Description-less, as the live naturewill-mapping agent effectively
        // is — its "No description" placeholder normalizes to null.
        AgentSkill("pdf")
    )

    private fun bar(skills: List<AgentSkill> = this.skills, isAgentWorking: Boolean = false) {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        isAgentWorking = isAgentWorking,
                        skills = skills,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
    }

    private fun type(text: String) =
        composeRule.onNode(hasSetTextAction()).performTextInput(text)

    @Test
    fun `a slash opens the palette on the agent's published skills`() {
        bar()
        type("/")

        composeRule.onNodeWithText("/linkedin-engagement").assertIsDisplayed()
        composeRule.onNodeWithText("/web-scraping").assertIsDisplayed()
        composeRule.onNodeWithText("/pdf").assertIsDisplayed()
    }

    @Test
    fun `each command carries its description, which is the whole point of the palette`() {
        bar()
        type("/")

        composeRule.onNodeWithText("Read a web page and extract structured text from it")
            .assertIsDisplayed()
    }

    @Test
    fun `a skill with no description shows the name alone, never a filler line`() {
        bar(skills = listOf(AgentSkill("pdf")))
        type("/")

        composeRule.onNodeWithText("/pdf").assertIsDisplayed()
        composeRule.onNodeWithText("No description").assertDoesNotExist()
    }

    @Test
    fun `a paragraph-long description is capped, so one skill cannot take the palette over`() {
        // Real descriptions run this long — see the live `oo` agent's
        // explain-codebase. Two lines, whatever the agent sends.
        val long = "Map an unfamiliar repository from the ground up, locating entry points, " +
            "reconstructing the architecture, and tracing how data flows through the layers. " +
            "Use when the user asks how something works, where a feature lives, or what a " +
            "service actually does, and nobody on the team can answer from memory."
        bar(skills = listOf(AgentSkill("short", "One line"), AgentSkill("verbose", long)))
        type("/")

        val oneLine = composeRule.onNodeWithText("One line").fetchSemanticsNode().size.height
        val capped = composeRule.onNodeWithText(long).fetchSemanticsNode().size.height
        assertTrue(
            "a ${long.length}-char description rendered ${capped}px, over the two-line cap (one line is ${oneLine}px)",
            capped <= oneLine * 2.5
        )
    }

    @Test
    fun `an agent with no published skills gets no palette at all`() {
        bar(skills = emptyList())
        type("/")

        composeRule.onNodeWithText("/pdf").assertDoesNotExist()
    }

    @Test
    fun `typing narrows to the matches and drops the rest`() {
        bar()
        type("/pd")

        composeRule.onNodeWithText("/pdf").assertIsDisplayed()
        composeRule.onNodeWithText("/web-scraping").assertDoesNotExist()
    }

    @Test
    fun `a query nothing matches leaves no empty box behind`() {
        bar()
        type("/zzz")

        composeRule.onNodeWithText("/pdf").assertDoesNotExist()
        composeRule.onNodeWithText("/linkedin-engagement").assertDoesNotExist()
    }

    @Test
    fun `tapping a skill completes the command and closes the palette`() {
        bar()
        type("/link")

        composeRule.onNodeWithText("/linkedin-engagement").performClick()

        // The completed command is in the field, and the palette row that
        // carried the same label is gone — the trailing space closed it.
        composeRule.onNodeWithText("/linkedin-engagement ").assertIsDisplayed()
        composeRule.onNodeWithText("/linkedin-engagement").assertDoesNotExist()
    }

    @Test
    fun `the caret follows the completion into the arguments`() {
        bar()
        type("/link")
        composeRule.onNodeWithText("/linkedin-engagement").performClick()

        // Compose's String-valued BasicTextField would have left the caret at
        // offset 5, splicing the next keystrokes into the middle of the name.
        type("report")

        composeRule.onNodeWithText("/linkedin-engagement report").assertIsDisplayed()
    }

    @Test
    fun `a space commits the command - the palette is gone before the arguments`() {
        bar()
        type("/pdf report")

        composeRule.onNodeWithText("/pdf").assertDoesNotExist()
    }

    @Test
    fun `deleting the slash dismisses it - the phone's escape key`() {
        bar()
        type("/pd")
        composeRule.onNodeWithText("/pdf").assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextClearance()

        composeRule.onNodeWithText("/pdf").assertDoesNotExist()
    }

    @Test
    fun `the palette sits above the field, not over the transcript`() {
        bar()
        type("/")

        val row = composeRule.onNodeWithText("/pdf").fetchSemanticsNode()
        val field = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(
            "palette bottom ${row.boundsInRoot.bottom} is not above field top ${field.boundsInRoot.top}",
            row.boundsInRoot.bottom <= field.boundsInRoot.top
        )
    }

    @Test
    fun `a turn starting leaves the palette up, because the field stays usable`() {
        var working by mutableStateOf(false)
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        isAgentWorking = working,
                        skills = skills,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
        type("/pd")
        composeRule.onNodeWithText("/pdf").assertIsDisplayed()

        // A running agent no longer locks the field: an INPUT on a running
        // session is pushed to it as runtime input, so finishing the command
        // and sending it is a real thing to be doing here.
        working = true

        composeRule.onNodeWithText("/pdf").assertIsDisplayed()
    }
}
