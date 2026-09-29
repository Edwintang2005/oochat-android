package ai.openonion.oochat.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for [InputBar] — the chat composer used by
 * [ai.openonion.oochat.ui.chat.ChatScreen].
 *
 * Exercises [InputBar] directly rather than the full ChatScreen: the
 * blank-input send gating this covers is entirely local to this composable
 * (`canSend = isConnected && !isSending && text.isNotBlank()`), whereas
 * ChatScreen itself needs a live ChatViewModel/connection/navigation graph
 * that would only add unrelated scaffolding to these assertions.
 */
class InputBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun blankInputLeavesSendDisabledWithTheMicStillThere() {
        composeTestRule.setContent {
            InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
        }

        composeTestRule.onNodeWithContentDescription("Dictate a message").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun whitespaceOnlyInputIsTreatedAsBlank() {
        composeTestRule.setContent {
            InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
        }

        composeTestRule.onNodeWithText("Message…").performTextInput("   ")

        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun enteringRealTextEnablesSendAndKeepsTheMic() {
        // The mic no longer gives way to Send: dictation appends to whatever is
        // in the field, so both belong there at once.
        composeTestRule.setContent {
            InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
        }

        composeTestRule.onNodeWithText("Message…").performTextInput("Hello agent")

        composeTestRule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Dictate a message").assertIsDisplayed()
    }

    @Test
    fun tappingSendForwardsTheTrimmedTextAndClearsTheField() {
        var sentText: String? = null
        var sentImages: List<String>? = null
        composeTestRule.setContent {
            InputBar(
                isConnected = true,
                isSending = false,
                onSend = { text, images, _ ->
                    sentText = text
                    sentImages = images
                }
            )
        }

        composeTestRule.onNodeWithText("Message…").performTextInput("  Hello agent  ")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("Hello agent", sentText)
        assertTrue(sentImages.isNullOrEmpty())
        // InputBar clears its own `text` state on send, so the placeholder
        // (only shown while the field is empty) is back.
        composeTestRule.onNodeWithText("Message…").assertIsDisplayed()
    }

    @Test
    fun disconnectedInputShowsTheConnectFirstPlaceholderAndNoSendButton() {
        composeTestRule.setContent {
            InputBar(isConnected = false, isSending = false, onSend = { _, _, _ -> })
        }

        composeTestRule.onNodeWithText("Connect to an agent first").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    // RECORD_AUDIO is not granted to the test APK by default, so tapping the
    // mic button here always hits the ungranted branch and should show the
    // rationale dialog rather than jump straight to the system prompt.
    @Test
    fun tappingMicWithoutPermissionShowsTheRationaleDialogInsteadOfTheSystemPrompt() {
        composeTestRule.setContent {
            InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
        }

        composeTestRule.onNodeWithContentDescription("Dictate a message").performClick()

        composeTestRule.onNodeWithText("Microphone access").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun dismissingTheRationaleWithNotNowDoesNotStartARecording() {
        var startedRecording = false
        composeTestRule.setContent {
            InputBar(
                isConnected = true,
                isSending = false,
                onSend = { _, _, _ -> },
                onStartVoiceRecording = { startedRecording = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Dictate a message").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()

        composeTestRule.onNodeWithText("Microphone access").assertDoesNotExist()
        assertTrue(!startedRecording)
    }
}
