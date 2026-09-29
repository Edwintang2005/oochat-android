package ai.openonion.oochat

import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import ai.openonion.oochat.ui.chat.components.VoiceInputState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's dictation controls on a real device. Dictation only writes
 * into the text field — nothing here touches the network, so unlike
 * CriticalChatWorkflowTest this drives [InputBar] directly with no fake server.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun bar(
        isConnected: Boolean = true,
        isSending: Boolean = false,
        isAgentWorking: Boolean = false,
        voiceInput: VoiceInputState = VoiceInputState()
    ) {
        composeTestRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = isConnected,
                    isSending = isSending,
                    isAgentWorking = isAgentWorking,
                    voiceInput = voiceInput,
                    onSend = { _, _, _ -> }
                )
            }
        }
    }

    @Test
    fun micStaysAvailableWhileTheAgentWorks() {
        // Web disables its mic only while transcribing; ours used to wait out a
        // whole agent turn, which is exactly when the next message gets dictated.
        bar(isAgentWorking = true)

        composeTestRule.onNode(hasContentDescription("Dictate a message")).assert(isEnabled())
    }

    @Test
    fun micStaysAvailableWhileSending() {
        bar(isSending = true)

        composeTestRule.onNode(hasContentDescription("Dictate a message")).assert(isEnabled())
    }

    @Test
    fun micDisabledWhileTranscribing() {
        bar(voiceInput = VoiceInputState(phase = VoiceInputPhase.TRANSCRIBING))

        composeTestRule.onNode(hasContentDescription("Dictate a message")).assert(isNotEnabled())
    }

    @Test
    fun micDisabledWhenDisconnected() {
        // Dictation writes into the field, and the field is locked with no agent.
        bar(isConnected = false)

        composeTestRule.onNode(hasContentDescription("Dictate a message"))
            .assertIsDisplayed()
            .assert(isNotEnabled())
    }

    @Test
    fun recordingOffersCancelOnItsOwnButtonAndFinishOnTheWaveform() {
        bar(voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING))

        composeTestRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Finish dictation").assertIsDisplayed()
    }
}
