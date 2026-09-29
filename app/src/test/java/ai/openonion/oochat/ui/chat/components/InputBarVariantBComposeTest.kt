package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's Variant B layout: mode chip and session usage on one row
 * *below* the field, with the chip live throughout. It used to go inert on
 * focus, which took the control away exactly while composing the message that
 * needed the new mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarVariantBComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(usage: SessionUsageTotals = USAGE) {
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = true,
                    isSending = false,
                    approvalMode = ApprovalMode.DEFAULT,
                    usage = usage,
                    onSend = { _, _, _ -> }
                )
            }
        }
    }

    @Test
    fun `the chip row sits below the text field`() {
        setContent()

        val field = composeRule.onNodeWithText("Message…").fetchSemanticsNode()
        val chip = composeRule.onNodeWithText("safe", substring = true).fetchSemanticsNode()
        assertTrue(
            "chip top ${chip.boundsInRoot.top} is not below field bottom ${field.boundsInRoot.bottom}",
            chip.boundsInRoot.top >= field.boundsInRoot.bottom
        )
    }

    @Test
    fun `the chip and the usage share that one row`() {
        setContent()

        val chip = composeRule.onNodeWithText("safe", substring = true).fetchSemanticsNode()
        val usage = composeRule.onNodeWithText("1.2k tokens", substring = true).fetchSemanticsNode()
        val delta = kotlin.math.abs(chip.boundsInRoot.center.y - usage.boundsInRoot.center.y)
        assertTrue("chip and usage are $delta px apart vertically, not on one row", delta < 8f)
        assertTrue(
            "usage is not right of the chip",
            usage.boundsInRoot.left > chip.boundsInRoot.right
        )
    }

    @Test
    fun `the chip stays live once the field takes focus`() {
        setContent()

        composeRule.onNodeWithText("Message…").performClick()

        composeRule.onNodeWithText("safe", substring = true).assertIsEnabled()
        composeRule.onNodeWithContentDescription("Approval mode options").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Locked while typing").assertDoesNotExist()
    }

    @Test
    fun `the chip cycles while the message that needs the new mode is being typed`() {
        var cycles = 0
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = true,
                    isSending = false,
                    approvalMode = ApprovalMode.DEFAULT,
                    usage = USAGE,
                    onCycleApprovalMode = { cycles++ },
                    onSend = { _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Message…").performClick()
        composeRule.onNodeWithText("safe", substring = true).performClick()

        assertTrue("a focused field still blocked the mode chip", cycles == 1)
    }

    @Test
    fun `the accuracy disclaimer is gone from the composer`() {
        // It moved to onboarding, where it is read once — see OnboardingIntro.
        setContent()

        composeRule.onNodeWithText("inaccurate", substring = true).assertDoesNotExist()
    }

    private companion object {
        val USAGE = SessionUsageTotals(
            totalTokens = 1234,
            totalCostUsd = 0.02,
            latestContextPercent = 45.0
        )
    }
}
