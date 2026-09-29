package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SessionUsageBar] renders (or doesn't) purely off the [SessionUsageTotals]
 * it's handed — no ViewModel, no connection state. Mirrors web's
 * status-bar.tsx `if (!hasTokenData && !showSessionState) return null`, minus
 * the session-state half this app doesn't carry (see the component's doc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionUsageBarComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `zero tokens renders nothing`() {
        composeRule.setContent {
            ConnectOnionTheme {
                SessionUsageBar(
                    usage = SessionUsageTotals(totalTokens = 0, totalCostUsd = 0.0, latestContextPercent = null)
                )
            }
        }

        composeRule.onNodeWithText("tok", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tokens present renders the summary line`() {
        composeRule.setContent {
            ConnectOnionTheme {
                SessionUsageBar(
                    usage = SessionUsageTotals(totalTokens = 1234, totalCostUsd = 0.02, latestContextPercent = 45.0)
                )
            }
        }

        composeRule.onNodeWithText("1.2k tokens · $0.02", substring = true).assertExists()
        // The reading is a ring now — no "45% ctx" text anywhere.
        composeRule.onNodeWithText("ctx", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Context: 45%").assertExists()
    }

    @Test
    fun `a cost too small to print honestly is left out`() {
        // A real turn cost $0.0000127. Rendered at four places that is
        // "$0.0000" — a number, not an admission that it is negligible. The
        // per-turn footer already gated on this; the strip did not, so the
        // zeros reached the device.
        setTotals(SessionUsageTotals(totalTokens = 52, totalCostUsd = 0.0000127, latestContextPercent = null))

        composeRule.onNodeWithText("52 tokens").assertExists()
        composeRule.onNodeWithText("$", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a cost at the floor is printed`() {
        setTotals(SessionUsageTotals(totalTokens = 52, totalCostUsd = 0.0001, latestContextPercent = null))

        composeRule.onNodeWithText("$0.0001", substring = true).assertExists()
    }

    @Test
    fun `the usage summary shares the approval-mode chip's row`() {
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = true,
                    isSending = false,
                    approvalMode = ApprovalMode.DEFAULT,
                    usage = SessionUsageTotals(
                        totalTokens = 1234,
                        totalCostUsd = 0.02,
                        latestContextPercent = 45.0
                    ),
                    onSend = { _, _, _ -> }
                )
            }
        }

        // Vertical centres within a few pixels of each other is the assertion
        // that actually fails if the summary drifts back onto its own line —
        // both nodes existing says nothing about where.
        val chip = composeRule.onNodeWithText("safe", substring = true).fetchSemanticsNode()
        val usage = composeRule.onNodeWithText("1.2k tokens", substring = true).fetchSemanticsNode()
        val delta = kotlin.math.abs(chip.boundsInRoot.center.y - usage.boundsInRoot.center.y)
        assertTrue("chip and usage are $delta px apart vertically, not on one row", delta < 8f)
    }

    private fun setTotals(totals: SessionUsageTotals) {
        composeRule.setContent {
            ConnectOnionTheme { SessionUsageBar(usage = totals) }
        }
    }

    private fun setUsage(contextPercent: Double?) {
        composeRule.setContent {
            ConnectOnionTheme {
                SessionUsageBar(
                    usage = SessionUsageTotals(
                        totalTokens = 500,
                        totalCostUsd = 0.0,
                        latestContextPercent = contextPercent
                    )
                )
            }
        }
    }

    @Test
    fun `a reading below the floor draws nothing - not even the bare track`() {
        // A real turn reported 52 tokens at 0.0041% — about 1.27M of window.
        // At that scale the arc is shorter than its own round cap, so the ring
        // would read as an empty circle for the whole session.
        setUsage(contextPercent = 0.0041)

        composeRule.onNodeWithText("500 tokens").assertExists()
        composeRule.onNodeWithContentDescription("Context: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a reading just under the floor is still nothing, and the floor itself draws`() {
        setUsage(contextPercent = (CTX_RING_MIN_PERCENT - 1).toDouble())
        composeRule.onNodeWithContentDescription("Context: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the floor itself draws`() {
        setUsage(contextPercent = CTX_RING_MIN_PERCENT.toDouble())
        composeRule.onNodeWithContentDescription("Context: $CTX_RING_MIN_PERCENT%").assertExists()
    }

    @Test
    fun `the approach to the server's compact stays legible`() {
        // 80 through 90 is the only window this control exists for — the server
        // auto-compacts at 90, and the arc turns error at 80.
        setUsage(contextPercent = 80.0)
        composeRule.onNodeWithContentDescription("Context: 80%").assertExists()
    }

    @Test
    fun `no reading at all renders no ring`() {
        // Distinct from a below-floor reading above, though they look alike:
        // nothing has been measured yet, rather than measured and too small.
        setUsage(contextPercent = null)

        composeRule.onNodeWithContentDescription("Context: ", substring = true).assertDoesNotExist()
    }
}
