package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ring is now the only context reading on screen, so the two things it
 * must not lose are the percentage (to a screen reader) and the colour switch
 * at 80% (to everyone else).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CtxRingComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the percentage stays available to a screen reader`() {
        composeRule.setContent { ConnectOnionTheme { CtxRing(percent = 45) } }

        composeRule.onNodeWithContentDescription("Context: 45%").assertExists()
    }

    @Test
    fun `a reading of a few percent still renders a gauge`() {
        // The low end is where this risks reading as "no data" — the track is
        // always drawn, so the node is there whatever the arc looks like.
        composeRule.setContent {
            ConnectOnionTheme {
                Row { CtxRing(percent = 4, modifier = Modifier.alignByBaseline()) }
            }
        }

        composeRule.onNodeWithContentDescription("Context: 4%")
            .assertExists()
            .assertHeightIsEqualTo(10.dp)
            .assertWidthIsEqualTo(10.dp)
    }

    @Test
    fun `the ring sits inside the line box of the label beside it`() {
        // "one line, not text-plus-icon": it must fit within the adjacent
        // label's line box rather than stretching the row taller.
        composeRule.setContent {
            ConnectOnionTheme {
                Row {
                    Text(
                        text = "128.0k tokens",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.alignByBaseline()
                    )
                    CtxRing(percent = 45, modifier = Modifier.alignByBaseline())
                }
            }
        }

        val label = composeRule.onNodeWithText("128.0k tokens").fetchSemanticsNode().boundsInRoot
        val ring = composeRule.onNodeWithContentDescription("Context: 45%")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("ring top ${ring.top} is above label top ${label.top}", ring.top >= label.top)
        assertTrue("ring bottom ${ring.bottom} is below label bottom ${label.bottom}", ring.bottom <= label.bottom)
        assertTrue("ring is not to the right of the label", ring.left >= label.right)
    }

    @Test
    fun `an out-of-range reading is clamped, not drawn past full`() {
        composeRule.setContent { ConnectOnionTheme { CtxRing(percent = 140) } }

        composeRule.onNodeWithContentDescription("Context: 100%").assertExists()
    }

    @Test
    fun `the arc switches from primary to error at eighty percent`() {
        var justBelow: Color? = null
        var atThreshold: Color? = null
        var primary: Color? = null
        var error: Color? = null
        composeRule.setContent {
            ConnectOnionTheme {
                justBelow = ctxRingArcColor(79)
                atThreshold = ctxRingArcColor(80)
                primary = MaterialTheme.colorScheme.primary
                error = MaterialTheme.colorScheme.error
            }
        }

        assertEquals(primary, justBelow)
        assertEquals(error, atThreshold)
    }
}
