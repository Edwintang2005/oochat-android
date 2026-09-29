package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.ui.chat.components.ONBOARD_VERIFY_TIMEOUT_MS
import ai.openonion.oochat.ui.chat.components.OnboardGateCard
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The invite-code gate had no baseline at all, which is how it shipped with a
 * "Verifying…" state that hid the field and the button with no way back.
 *
 * Three resting states are captured; "Verifying…" is not. It owns a
 * `CircularProgressIndicator` that animates forever, and per [ScreenshotTestBase]
 * that makes a baseline of one arbitrary frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardGateScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun captureInput(palette: Palette) =
        composeRule.captureThemed("onboard_gate_input", palette) {
            OnboardGateCard(
                title = "Onboarding Required",
                subtitle = "This agent requires an invite code to activate your session.",
                onOnboard = { _, _, _ -> },
            )
        }

    private fun captureTimedOut(palette: Palette) =
        composeRule.captureThemed(
            name = "onboard_gate_timed_out",
            palette = palette,
            interact = {
                // Virtual clock only — the state is reached by outwaiting the
                // gate's own timeout, not by sleeping.
                mainClock.autoAdvance = false
                onNode(hasSetTextAction()).performTextInput("INVITE-4F3A")
                onNode(hasSetTextAction()).performImeAction()
                mainClock.advanceTimeBy(ONBOARD_VERIFY_TIMEOUT_MS + 500)
            },
        ) {
            OnboardGateCard(
                title = "Onboarding Required",
                subtitle = "This agent requires an invite code to activate your session.",
                onOnboard = { _, _, _ -> },
            )
        }

    private fun captureRejected(palette: Palette) =
        composeRule.captureThemed("onboard_gate_rejected", palette) {
            OnboardGateCard(
                title = "Onboarding Rejected",
                subtitle = "Try again with a different invite code.",
                errorReason = "Invalid invite code",
                onOnboard = { _, _, _ -> },
            )
        }

    @Test
    fun `onboard gate input - light`() = captureInput(Palette.Light)

    @Test
    fun `onboard gate input - dark`() = captureInput(Palette.Dark)

    @Test
    fun `onboard gate timed out - light`() = captureTimedOut(Palette.Light)

    @Test
    fun `onboard gate timed out - dark`() = captureTimedOut(Palette.Dark)

    @Test
    fun `onboard gate rejected - light`() = captureRejected(Palette.Light)

    @Test
    fun `onboard gate rejected - dark`() = captureRejected(Palette.Dark)
}
