package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Dictation as the composer sees it: partials arrive as [VoiceInputState]
 * updates and have to land in the field without eating what is already there.
 * [DictationMergeTest] covers the same rules without Compose; this proves they
 * are actually wired to the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarDictationComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var setVoice: (VoiceInputState) -> Unit

    @Before
    fun grantMic() {
        // Otherwise the mic tap opens the rationale dialog instead of starting
        // a dictation, and every case below would pass without one running.
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun bar() {
        composeRule.setContent {
            var voice by remember { mutableStateOf(VoiceInputState()) }
            setVoice = { voice = it }
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = voice,
                        onStartVoiceRecording = { setVoice(VoiceInputState(phase = VoiceInputPhase.LISTENING)) },
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
    }

    private fun startDictating() {
        composeRule.onNodeWithContentDescription("Dictate a message").performClick()
        composeRule.waitForIdle()
    }

    /** A composer already mid-dictation, for the cases about the recording row's own controls. */
    private fun dictating(
        onCancelVoiceRecording: () -> Unit = {},
        onFinishVoiceInput: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                        onCancelVoiceRecording = onCancelVoiceRecording,
                        onFinishVoiceInput = onFinishVoiceInput,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
    }

    private fun partial(text: String) {
        composeRule.runOnIdle { setVoice(VoiceInputState(phase = VoiceInputPhase.LISTENING, transcript = text)) }
        composeRule.waitForIdle()
    }

    private fun final(text: String) {
        composeRule.runOnIdle { setVoice(VoiceInputState(phase = VoiceInputPhase.IDLE, transcript = text)) }
        composeRule.waitForIdle()
    }

    @Test
    fun `the mic survives a send and a whole agent turn`() {
        // Web disables its mic only while transcribing. Ours waited out both of
        // these, which is exactly when the next message wants dictating.
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = true,
                        isAgentWorking = true,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Dictate a message").assertIsEnabled()
    }

    @Test
    fun `the mic is out while a transcription is in flight`() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.TRANSCRIBING),
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Dictate a message").assertIsNotEnabled()
    }

    @Test
    fun `tapping the waveform finishes the dictation`() {
        var finishes = 0
        dictating(onFinishVoiceInput = { finishes++ })

        composeRule.onNodeWithContentDescription("Finish dictation").performClick()

        assertEquals(1, finishes)
    }

    @Test
    fun `the mic slot offers no finish while dictating - the waveform owns that now`() {
        dictating()

        // One label, one job: the slot starts dictations and is inert while one
        // is running, rather than swapping into a second control mid-recording.
        composeRule.onNodeWithContentDescription("Dictate a message").assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription("Finish dictation").assertCountEquals(1)
    }

    @Test
    fun `the row's cancel still discards and never finishes`() {
        var cancels = 0
        var finishes = 0
        dictating(onCancelVoiceRecording = { cancels++ }, onFinishVoiceInput = { finishes++ })

        composeRule.onNodeWithContentDescription("Cancel dictation").performClick()

        assertEquals(1, cancels)
        assertEquals(0, finishes)
    }

    /** Renders a composer stuck in PREPARING, past the point where the row admits it. */
    private fun preparing(onCancelVoiceRecording: () -> Unit = {}, onFinishVoiceInput: () -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.PREPARING),
                        onCancelVoiceRecording = onCancelVoiceRecording,
                        onFinishVoiceInput = onFinishVoiceInput,
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(PREPARING_VISIBLE_AFTER_MS + 100)
        composeRule.waitForIdle()
    }

    @Test
    fun `preparing says so instead of drawing a meter over a microphone nobody opened`() {
        preparing()

        composeRule.onNodeWithText("Preparing…").assertIsDisplayed()
        // The meter is the finish control, and both are lies before the mic is
        // open: there is nothing reacting to your voice and nothing to finish.
        composeRule.onNodeWithContentDescription("Finish dictation").assertDoesNotExist()
        // No timer either — a stopwatch here would be counting audio nobody got.
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
    }

    @Test
    fun `cancel is the one control preparing offers`() {
        var cancels = 0
        var finishes = 0
        preparing(onCancelVoiceRecording = { cancels++ }, onFinishVoiceInput = { finishes++ })

        composeRule.onNodeWithContentDescription("Cancel dictation").performClick()

        assertEquals(1, cancels)
        assertEquals(0, finishes)
    }

    @Test
    fun `a preparing window short enough to be invisible stays invisible`() {
        // On a proven device the mic opens in ~100ms. A label that appears and
        // vanishes inside that reads as a glitch, so the row waits it out.
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.PREPARING),
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(PREPARING_VISIBLE_AFTER_MS / 2)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Preparing…").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(PREPARING_VISIBLE_AFTER_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Preparing…").assertIsDisplayed()
    }

    @Test
    fun `the elapsed timer counts from the microphone opening, not from the tap`() {
        composeRule.setContent {
            var voice by remember { mutableStateOf(VoiceInputState(phase = VoiceInputPhase.PREPARING)) }
            setVoice = { voice = it }
            ConnectOnionTheme {
                Column {
                    InputBar(isConnected = true, isSending = false, voiceInput = voice, onSend = { _, _, _ -> })
                }
            }
        }

        // Three seconds spent choosing a path — the probe on an unproven device
        // spends 2.5s of it — none of which reached a microphone.
        advanceClocks(3_000)
        composeRule.runOnIdle { setVoice(VoiceInputState(phase = VoiceInputPhase.LISTENING)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("00:00").assertIsDisplayed()

        advanceClocks(3_000)

        // Non-vacuous: the wall clock has to be moving for the assertion above
        // to mean anything.
        composeRule.onNodeWithText("00:03").assertIsDisplayed()
    }

    /**
     * The timer samples SystemClock and ticks off the frame clock, so a test
     * that moves only one of the two proves nothing.
     */
    private fun advanceClocks(millis: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    @Test
    fun `the mic slot is out while a dictation is still being prepared`() {
        // Busy from the tap, not from the mic opening: a second tap in the gap
        // would start a second dictation over the first.
        preparing()

        composeRule.onNodeWithContentDescription("Dictate a message").assertIsNotEnabled()
    }

    @Test
    fun `the waveform stops being a target once the transcription is in flight`() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.TRANSCRIBING),
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Finish dictation").assertDoesNotExist()
        composeRule.onNodeWithText("Transcribing…").assertIsDisplayed()
    }

    @Test
    fun `the fallback notice captions the recording row instead of splitting the composer`() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(
                            phase = VoiceInputPhase.LISTENING,
                            notice = "Using server transcription."
                        ),
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        val notice = composeRule.onNodeWithText("Using server transcription.")
            .fetchSemanticsNode().boundsInRoot.top
        val row = composeRule.onNodeWithContentDescription("Finish dictation")
            .fetchSemanticsNode().boundsInRoot.top
        val field = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top

        assertTrue("notice at $notice was not above the recording row at $row", notice < row)
        assertTrue("recording row at $row was not above the field at $field", row < field)
    }

    @Test
    fun `staged attachments survive a dictation - voice and attachments are not alternatives`() {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = true,
                        isSending = false,
                        voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }

        // The field is still there to dictate into, rather than replaced by a
        // full-width recording row that also hid the attachment strip.
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open attachment menu").assertIsDisplayed()
    }

    @Test
    fun `text typed before recording survives the dictation`() {
        bar()
        composeRule.onNode(hasSetTextAction()).performTextInput("Reminder:")

        startDictating()
        final("book a table")

        composeRule.onNodeWithText("Reminder: book a table").assertIsDisplayed()
    }

    @Test
    fun `repeated partials do not duplicate text in the field`() {
        bar()

        startDictating()
        partial("book")
        partial("book a")
        partial("book a table")
        final("book a table")

        composeRule.onNodeWithText("book a table").assertIsDisplayed()
    }

    @Test
    fun `an edit made mid-stream is not overwritten by a later partial`() {
        bar()

        startDictating()
        partial("book a tabel")

        // The whole point of the preview: the recognizer got a word wrong and
        // the fix is one tap away rather than a re-record.
        composeRule.onNode(hasSetTextAction()).performTextReplacement("book a table")
        partial("book a tabel for two")

        composeRule.onNodeWithText("book a table").assertIsDisplayed()
    }

    @Test
    fun `a final result after an edit is dropped too`() {
        bar()

        startDictating()
        partial("book a tabel")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("book a table")
        final("book a tabel for two")

        composeRule.onNodeWithText("book a table").assertIsDisplayed()
    }
}
