package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The intro block alone — the whole screen needs a live discovery call to
 * compose, and this copy is the part with a decision behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingIntroComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the accuracy disclaimer lives in the supporting line, not on its own`() {
        // Moved here from under the chat composer. It has to be *in* the
        // paragraph that already sets expectations, not appended below it —
        // a line of its own is the shape it had before, and was ignored in.
        composeRule.setContent { ConnectOnionTheme { OnboardingIntro() } }

        composeRule.onNodeWithText("AI-generated responses may be inaccurate.")
            .assertDoesNotExist()
        composeRule.onNodeWithText(
            "nothing of yours kept on our servers. AI-generated responses may be inaccurate.",
            substring = true
        ).assertExists()
    }
}
