package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
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

    // ── Host-managed modes (W16AAPPLE-18) ──────────────────────────────

    private val hostModes = SessionModes(
        available = listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO, ApprovalMode.FULL_ACCESS),
        hostManaged = true
    )

    @Test
    fun `a full access chip shows the Host's remaining turns and offers exit`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.FULL_ACCESS,
                    enabled = true,
                    turnsLeft = 3,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithText("full access · 3 left · exit").assertExists()
    }

    @Test
    fun `the sheet offers the Host's modes, and full access with no turn field`() {
        var picked: Pair<ApprovalMode, Int?>? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { mode, turns -> picked = mode to turns },
                    onDismiss = {},
                    modes = hostModes
                )
            }
        }

        composeRule.onNodeWithText("Read only").assertExists()
        composeRule.onNodeWithText("Auto").assertExists()
        composeRule.onNodeWithText("Safe").assertDoesNotExist()
        composeRule.onNodeWithText("Ultra work").assertDoesNotExist()
        composeRule.onNodeWithText("Turns").assertDoesNotExist()

        composeRule.onNodeWithText("Start full access").performScrollTo().performClick()
        assertEquals(ApprovalMode.FULL_ACCESS to null, picked)
    }

    @Test
    fun `a Host without full access shows no unattended block`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { _, _ -> },
                    onDismiss = {},
                    modes = hostModes.copy(available = listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO))
                )
            }
        }

        composeRule.onNodeWithText("Full access").assertDoesNotExist()
        composeRule.onNodeWithText("Ultra work").assertDoesNotExist()
    }

    @Test
    fun `leaving full access returns to auto`() {
        var picked: ApprovalMode? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.FULL_ACCESS,
                    onSelect = { mode, _ -> picked = mode },
                    onDismiss = {},
                    modes = hostModes.copy(turnsLeft = 5)
                )
            }
        }

        composeRule.onNodeWithText("Exit to auto mode").performScrollTo().performClick()
        assertEquals(ApprovalMode.AUTO, picked)
    }

    @Test
    fun `the legacy sheet still offers ultra work with its turn field`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(current = ApprovalMode.SAFE, onSelect = { _, _ -> }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Ultra work").assertExists()
        composeRule.onNodeWithText("Start ultra work").assertExists()
    }
}
