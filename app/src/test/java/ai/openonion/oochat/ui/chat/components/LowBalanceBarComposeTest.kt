package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top-up control, ported from the reference web client's `TopUp` /
 * `isLowBalance`. Shown only when the agent published a balance and that
 * balance is low enough to act on; at zero it names the consequence instead of
 * printing "$0.00".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LowBalanceBarComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val address = "0x1a2b3c4d5e6f"

    private fun setBar(balanceUsd: Double?, agentAddress: String? = address) {
        composeRule.setContent {
            ConnectOnionTheme {
                LowBalanceBar(balanceUsd = balanceUsd, agentAddress = agentAddress)
            }
        }
    }

    // ── The threshold ────────────────────────────────────────────────

    @Test
    fun `the threshold is web's one dollar, to the cent`() {
        assertTrue(isLowBalance(0.99))
        assertFalse(isLowBalance(1.0))
        assertFalse(isLowBalance(12.5))
        assertTrue(isLowBalance(0.0))
    }

    @Test
    fun `a healthy balance is not rendered at all`() {
        // A balance nobody needs to act on is clutter — web's reasoning, kept.
        setBar(balanceUsd = 12.5)

        composeRule.onNodeWithText("Add credits").assertDoesNotExist()
    }

    @Test
    fun `an agent that published no balance is not rendered at all`() {
        // Only co-managed-key agents publish one; the rest must not sprout a bar.
        setBar(balanceUsd = null)

        composeRule.onNodeWithText("Add credits").assertDoesNotExist()
    }

    @Test
    fun `with no address there is nothing to address a top-up to`() {
        setBar(balanceUsd = 0.2, agentAddress = null)

        composeRule.onNodeWithText("Add credits").assertDoesNotExist()
    }

    // ── What it says ─────────────────────────────────────────────────

    @Test
    fun `at zero it names the consequence instead of printing a number`() {
        setBar(balanceUsd = 0.0)

        composeRule.onNodeWithText("Out of credits", substring = true).assertExists()
        composeRule.onNodeWithText("$0.00", substring = true).assertDoesNotExist()
    }

    @Test
    fun `low but non-zero shows the figure`() {
        setBar(balanceUsd = 0.42)

        composeRule.onNodeWithText("$0.42", substring = true).assertExists()
        composeRule.onNodeWithText("Add credits").assertExists()
    }

    // ── Where it sends you ───────────────────────────────────────────

    @Test
    fun `the purchase page takes the address in key, as web's purchaseUrl does`() {
        // Any other parameter name lands the payer on an empty form.
        assertEquals(
            "https://o.openonion.ai/purchase?key=$address",
            agentPurchaseUrl(address)
        )
    }
}
