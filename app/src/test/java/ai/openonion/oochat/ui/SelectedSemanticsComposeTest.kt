package ai.openonion.oochat.ui

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.agent.AgentItem
import ai.openonion.oochat.ui.agent.components.ModeToggleButton
import ai.openonion.oochat.ui.chat.components.ApprovalModeSheet
import ai.openonion.oochat.ui.settings.components.ThemeModeRow
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for fix(a11y): these four controls looked selected but
 * never exposed the `selected` semantics property, so TalkBack never
 * announced which option was current — see the sites' own `.selectable(...)`
 * comments for why each earned [androidx.compose.ui.semantics.Role.RadioButton].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedSemanticsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ApprovalModeSheet ModeRow exposes selected on the current mode only`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(current = ApprovalMode.PLAN, onSelect = { _, _ -> }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Plan").assert(isSelected())
        composeRule.onNodeWithText("Safe").assert(isNotSelected())
        composeRule.onNodeWithText("Accept edits").assert(isNotSelected())
    }

    @Test
    fun `ModeToggleButton exposes selected on the active toggle only`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ModeToggleButton(label = "Relay Server", selected = true, onClick = {})
                ModeToggleButton(label = "Direct Connection", selected = false, onClick = {})
            }
        }

        composeRule.onNodeWithText("Relay Server").assert(isSelected())
        composeRule.onNodeWithText("Direct Connection").assert(isNotSelected())
    }

    @Test
    fun `ThemeModeRow exposes selected on the current theme only`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ThemeModeRow(current = ThemeMode.DARK, onChange = {})
            }
        }

        // clearAndSetSemantics replaces the OutlinedButton's own merged
        // "Text" property with an explicit contentDescription — see the
        // production comment on why a plain merging ancestor isn't enough.
        composeRule.onNodeWithContentDescription("Dark").assert(isSelected())
        composeRule.onNodeWithContentDescription("Light").assert(isNotSelected())
        composeRule.onNodeWithContentDescription("System").assert(isNotSelected())
    }

    @Test
    fun `AgentListScreen AgentRow exposes selected on the active agent only`() {
        val agentA = AgentProfile(
            id = "agent-a",
            address = "0xaaa",
            name = "Agent A",
            serverUrl = "wss://example.com",
            createdAt = 0L
        )
        val agentB = AgentProfile(
            id = "agent-b",
            address = "0xbbb",
            name = "Agent B",
            serverUrl = "wss://example.com",
            createdAt = 0L
        )

        composeRule.setContent {
            ConnectOnionTheme {
                AgentItem(
                    agent = agentA,
                    isSelected = true,
                    isDefault = false,
                    onSelect = {},
                    onEdit = {},
                    onDelete = {},
                    onSetDefault = {}
                )
                AgentItem(
                    agent = agentB,
                    isSelected = false,
                    isDefault = false,
                    onSelect = {},
                    onEdit = {},
                    onDelete = {},
                    onSetDefault = {}
                )
            }
        }

        composeRule.onNodeWithText("Agent A").assert(isSelected())
        composeRule.onNodeWithText("Agent B").assert(isNotSelected())
    }
}
