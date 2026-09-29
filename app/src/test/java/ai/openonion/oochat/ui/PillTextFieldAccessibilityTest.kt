package ai.openonion.oochat.ui

import ai.openonion.oochat.ui.agent.components.AdvancedSettingsSection
import ai.openonion.oochat.ui.agent.components.AgentAddressField
import ai.openonion.oochat.ui.components.PillTextField
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for fix(a11y): PillTextField had zero semantics, so the
 * Server URL / Agent Address / API Key fields were announced by TalkBack
 * with no name at all — a sibling [ai.openonion.oochat.ui.agent.components.FieldLabel]
 * displayed the name but sat outside the field's own semantics node.
 *
 * Each test asserts the exact shape of the fix: one semantics node carries
 * both the field's name (`contentDescription`) and the editable value
 * (`hasSetTextAction`) — not two separate nodes — and that typing still
 * works and the typed text is still readable from that same node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PillTextFieldAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `PillTextField name and editable value share one semantics node`() {
        composeRule.setContent {
            ConnectOnionTheme {
                var text by remember { mutableStateOf("") }
                PillTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "https://oo.openonion.ai",
                    label = "Server URL"
                )
            }
        }

        val field = composeRule.onNode(hasContentDescription("Server URL") and hasSetTextAction())
        field.performTextInput("hello")
        field.assertTextEquals("hello")
    }

    @Test
    fun `AgentAddressField name and editable value share one semantics node`() {
        composeRule.setContent {
            ConnectOnionTheme {
                var address by remember { mutableStateOf("") }
                // useDirectConnection = true skips the ExposedDropdownMenuBox
                // branch — irrelevant to this fix, and awkward under Robolectric.
                AgentAddressField(
                    value = address,
                    onValueChange = { address = it },
                    savedAgents = emptyList(),
                    useDirectConnection = true
                )
            }
        }

        val field = composeRule.onNode(hasContentDescription("Agent Address") and hasSetTextAction())
        field.performTextInput("0xabc123")
        field.assertTextEquals("0xabc123")
    }

    @Test
    fun `Advanced Settings API Key field name and editable value share one semantics node`() {
        composeRule.setContent {
            ConnectOnionTheme {
                var apiKey by remember { mutableStateOf("") }
                AdvancedSettingsSection(apiKey = apiKey, onApiKeyChange = { apiKey = it })
            }
        }

        // The field is collapsed under "Advanced Settings" until expanded.
        composeRule.onNodeWithText("Advanced Settings").performClick()

        val field = composeRule.onNode(hasContentDescription("API Key") and hasSetTextAction())
        field.performTextInput("secret-key")

        // PasswordVisualTransformation masks EditableText with dots by
        // design, so the typed value is read back from InputText — the
        // property autofill/password fields expose instead — rather than
        // assertTextEquals.
        val inputText = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText)
        assertEquals("secret-key", inputText?.text)
    }
}
