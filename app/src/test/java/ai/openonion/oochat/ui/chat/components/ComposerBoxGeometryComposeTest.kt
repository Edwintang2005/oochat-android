package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The dictation row and the composer pill are two rounded boxes stacked in the
 * same gutter, so they have to start at the same inset and be the same width.
 * The row used to carry no horizontal outer padding and a 14dp inner start, so
 * it rendered wider than the pill it sits above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerBoxGeometryComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dictatingComposer() {
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = true,
                    isSending = false,
                    voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                    onSend = { _, _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun screenWidth(): Dp = composeRule.onRoot().getUnclippedBoundsInRoot().right

    private fun insetFromLeft(description: String): Dp =
        composeRule.onNodeWithContentDescription(description).getUnclippedBoundsInRoot().left

    private fun insetFromRight(description: String): Dp =
        screenWidth() - composeRule.onNodeWithContentDescription(description).getUnclippedBoundsInRoot().right

    private fun assertSameInset(what: String, a: Dp, b: Dp) {
        assertTrue("$what: $a vs $b", abs((a - b).value) < 0.5f)
    }

    /**
     * Measured on the two leading controls, which have the same internal
     * structure — ✕ and the attach "+" are both a 48dp IconButton sitting first
     * in its box — so the only thing between them is the padding under test.
     */
    @Test
    fun `the dictation row sits in the same gutter as the pill`() {
        dictatingComposer()

        assertSameInset(
            "leading inset",
            insetFromLeft("Cancel dictation"),
            insetFromLeft("Open attachment menu")
        )
    }

    /**
     * The trailing edge, from the other side. Unlike the pill, this row ends in
     * a decorative pulse indicator with no semantics of its own, so the check is
     * that the last addressable control stays inside the pill's right gutter
     * rather than lining up flush with it.
     */
    @Test
    fun `the dictation row does not overhang the pill on the right`() {
        dictatingComposer()

        val waveform = insetFromRight("Finish dictation")
        val send = insetFromRight("Send")
        assertTrue("waveform right inset $waveform < send's $send", waveform >= send)
    }

    /** The two leading controls are the most visible tell: they stack vertically. */
    @Test
    fun `the cancel button lines up under the pill's attach button`() {
        dictatingComposer()

        val attach = composeRule.onNodeWithContentDescription("Open attachment menu")
            .getUnclippedBoundsInRoot()
        val cancel = composeRule.onNodeWithContentDescription("Cancel dictation")
            .getUnclippedBoundsInRoot()
        val attachCentre = (attach.left + attach.right) / 2f
        val cancelCentre = (cancel.left + cancel.right) / 2f
        assertSameInset("leading control centre", attachCentre, cancelCentre)
    }
}
