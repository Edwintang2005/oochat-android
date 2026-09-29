package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ApprovalModeChip] must read differently while [ApprovalMode] is only
 * requested versus agent-confirmed — see
 * [ai.openonion.oochat.data.repository.ConnectionRepository.modePending]'s
 * doc for the two server-side windows this covers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalModeControlsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a pending chip shows the pending indicator`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = true,
                    pending = true,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mode change pending").assertExists()
    }

    @Test
    fun `a confirmed chip shows no pending indicator`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = true,
                    pending = false,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mode change pending").assertDoesNotExist()
    }
}
