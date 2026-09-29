package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sending while the agent is working. The server has always accepted it — an
 * INPUT on a running session is pushed into that run as runtime input
 * (ws_router/session.py's INPUT branch) — and the composer used to be the only
 * thing forbidding it.
 *
 * Send holds the pill's trailing slot in every state, so these cases pin that
 * it is still there and still delivers mid-run, that Stop is a labelled chip on
 * the shelf instead, and that the enter key never stops being a newline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarInterjectComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var sent = mutableListOf<String>()
    private var stops = 0

    private fun workingBar() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        isAgentWorking = true,
                        onSend = { text, _, _ -> sent += text },
                        onStop = { stops++ }
                    )
                }
            }
        }
    }

    private fun idleBar() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        onSend = { text, _, _ -> sent += text },
                        onStop = { stops++ }
                    )
                }
            }
        }
    }

    @Test
    fun `the field takes text while the agent works`() {
        workingBar()

        composeRule.onNode(hasSetTextAction()).performTextInput("actually, use the staging URL")

        composeRule.onNodeWithText("actually, use the staging URL").assertIsDisplayed()
    }

    @Test
    fun `send keeps the trailing slot mid-run and delivers the interjection`() {
        workingBar()
        composeRule.onNode(hasSetTextAction()).performTextInput("actually, use the staging URL")

        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
            .performClick()

        assertEquals(listOf("actually, use the staging URL"), sent)
    }

    @Test
    fun `an empty field is not sendable mid-run either`() {
        workingBar()

        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()

        assertEquals(emptyList<String>(), sent)
    }

    /**
     * The enter key. With Send in the pill in every state nothing has to be
     * traded for it, and Compose sets IME_FLAG_NO_ENTER_ACTION on a multi-line
     * field exactly while the imeAction is Default — so the flag is what proves
     * a real IME would type a newline rather than fire an action.
     */
    @Test
    fun `the enter key is a newline while the agent works`() {
        workingBar()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()

        val info = EditorInfo()
        val connection = composeView().onCreateInputConnection(info)

        assertNotNull("No input connection — the field never took focus", connection)
        assertNotEquals(
            EditorInfo.IME_ACTION_SEND,
            info.imeOptions and EditorInfo.IME_MASK_ACTION
        )
        assertNotEquals(
            "The enter key must not carry an action",
            0,
            info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION
        )
        assertTrue(
            "The field should stay multi-line",
            info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        )
    }

    @Test
    fun `the enter key is a newline while the agent is idle too`() {
        idleBar()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()

        val info = EditorInfo()
        composeView().onCreateInputConnection(info)

        assertNotEquals(
            EditorInfo.IME_ACTION_SEND,
            info.imeOptions and EditorInfo.IME_MASK_ACTION
        )
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
    }

    /**
     * CoreTextField restarts the input connection whenever `imeOptions` changes
     * on a focused field, and a restart carries `showKeyboard = true` — so an
     * imeAction that flips when the run starts pops the keyboard back up on its
     * own. The options have to be identical before and after.
     */
    @Test
    fun `the ime options survive the run starting under a focused field`() {
        var working by mutableStateOf(false)
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        isAgentWorking = working,
                        onSend = { text, _, _ -> sent += text }
                    )
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        val before = EditorInfo().also { composeView().onCreateInputConnection(it) }

        working = true
        composeRule.waitForIdle()
        val after = EditorInfo().also { composeView().onCreateInputConnection(it) }

        assertEquals(before.imeOptions, after.imeOptions)
        assertEquals(before.inputType, after.inputType)
    }

    /**
     * Dismissing the keyboard on send hid the reply behind a relayout and made
     * the next interjection cost an extra tap, on a composer whose whole point
     * is that a second message can follow straight on.
     */
    @Test
    fun `sending leaves the keyboard where it is`() {
        val keyboard = RecordingKeyboardController()
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    Column {
                        InputBar(
                            isConnected = true,
                            isSending = false,
                            onSend = { text, _, _ -> sent += text }
                        )
                    }
                }
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("ship it")
        composeRule.onNodeWithContentDescription("Send").performClick()

        assertEquals(listOf("ship it"), sent)
        assertEquals("hide() calls", 0, keyboard.hides)
    }

    private class RecordingKeyboardController : SoftwareKeyboardController {
        var hides = 0
        override fun show() = Unit
        override fun hide() {
            hides++
        }
    }

    @Test
    fun `stop is a labelled chip on the shelf, not a glyph in the send slot`() {
        workingBar()

        // The chip carries its own label, so the icon inside it is decorative —
        // a node described "Stop" would mean an icon-only pill button is back.
        composeRule.onNodeWithContentDescription("Stop").assertDoesNotExist()
        composeRule.onNodeWithText("Stop").assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, stops)
    }

    @Test
    fun `stop is only there while there is a run to stop`() {
        idleBar()

        composeRule.onNodeWithText("Stop").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `stop stays live while dictating, now that it is the only square glyph`() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        isAgentWorking = true,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                        onSend = { _, _, _ -> },
                        onStop = { stops++ }
                    )
                }
            }
        }

        // It used to go inert here, purely because the mic slot showed a second
        // square glyph mid-dictation. That glyph is gone — finishing moved onto
        // the waveform — so the interrupt is available at the one moment the
        // user is dictating an interjection to a runaway agent.
        composeRule.onNodeWithText("Stop").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Stop").performClick()

        assertEquals(1, stops)
    }

    /** The View backing the composition, which is what the IME talks to. */
    private fun composeView(): View =
        (composeRule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
}
