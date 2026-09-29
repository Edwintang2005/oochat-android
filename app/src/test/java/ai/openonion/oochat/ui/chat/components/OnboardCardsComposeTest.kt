package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On-device bugs this guards against: the invite-code field ignored the IME
 * Done key (only the button submitted), a rejection swapped the card to its
 * error variant with the soft keyboard still up over the new input, and
 * "Verifying…" ran forever when no verdict ever came back.
 *
 * Time is driven by the Compose test clock throughout — no wall-clock waits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardCardsComposeTest {

    /** A few frames — enough to settle a recomposition, negligible against the timeout. */
    private val FRAME_SETTLE_MS = 100L

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var hideCount = 0
        override fun show() = Unit
        override fun hide() {
            hideCount++
        }
    }

    /** [liveConnection] is read inside composition so tests can flip it mid-verify. */
    private fun setGate(
        keyboard: RecordingKeyboard,
        errorReason: String? = null,
        liveConnection: () -> Boolean = { true },
        onOnboard: (String, String?, Double?) -> Unit = { _, _, _ -> }
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    OnboardGateCard(
                        title = "Onboarding Required",
                        subtitle = "This agent requires an invite code.",
                        onOnboard = onOnboard,
                        errorReason = errorReason,
                        hasLiveConnection = liveConnection()
                    )
                }
            }
        }
    }

    /** Trailing advance renders the result: with autoAdvance off nothing recomposes on its own. */
    private fun submitCode(code: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(code)
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
    }

    @Test
    fun `IME Done submits the invite code`() {
        var submittedCode: String? = null
        var submittedMethod: String? = null
        setGate(RecordingKeyboard()) { method, code, _ ->
            submittedMethod = method
            submittedCode = code
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("  code-123  ")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertEquals("invite_code", submittedMethod)
        assertEquals("IME Done must submit the trimmed code, same as the button", "code-123", submittedCode)
    }

    @Test
    fun `IME Done takes the keyboard down and shows the verifying state`() {
        val keyboard = RecordingKeyboard()
        setGate(keyboard)

        composeRule.onNode(hasSetTextAction()).performTextInput("code-123")
        assertEquals("nothing should dismiss the keyboard while typing", 0, keyboard.hideCount)

        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertEquals("IME Done must dismiss the soft keyboard", 1, keyboard.hideCount)
        composeRule.onNodeWithText("Verifying…").assertExists()
    }

    @Test
    fun `IME Done on a blank field submits nothing`() {
        var calls = 0
        val keyboard = RecordingKeyboard()
        setGate(keyboard) { _, _, _ -> calls++ }

        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertEquals("a blank code must not be submitted", 0, calls)
        assertEquals(0, keyboard.hideCount)
    }

    @Test
    fun `swapping the gate card to its rejected variant takes the keyboard down`() {
        val keyboard = RecordingKeyboard()
        var item: ChatItem by mutableStateOf(
            ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        )
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ChatItemBubble(
                        item = item,
                        timestamp = null,
                        onRespond = {},
                        onApprove = { _, _, _, _ -> },
                        onOnboard = { _, _, _ -> },
                        onPlanReviewResponse = {},
                        onUlwResponse = { _, _, _ -> }
                    )
                }
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("wrong-code")
        assertEquals("the pending card must not fight the keyboard", 0, keyboard.hideCount)

        // Same id — the reducer's in-place swap, which reuses this row.
        composeRule.runOnIdle {
            item = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Invalid invite code").assertExists()
        assertEquals("the swap to the rejected card must dismiss the keyboard", 1, keyboard.hideCount)
    }

    @Test
    fun `the rejected card lets the user type again without re-dismissing`() {
        val keyboard = RecordingKeyboard()
        setGate(keyboard, errorReason = "Invalid invite code")

        assertEquals("the rejected card dismisses once on arrival", 1, keyboard.hideCount)

        composeRule.onNode(hasSetTextAction()).performTextInput("second-try")

        assertEquals(
            "recomposing on every keystroke must not keep closing the keyboard",
            1,
            keyboard.hideCount
        )
    }

    /** Renders one chat item through the real dispatch, so branch identity is under test too. */
    private fun setBubble(keyboard: RecordingKeyboard, item: () -> ChatItem) {
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ChatItemBubble(
                        item = item(),
                        timestamp = null,
                        onRespond = {},
                        onApprove = { _, _, _, _ -> },
                        onOnboard = { _, _, _ -> },
                        onPlanReviewResponse = {},
                        onUlwResponse = { _, _, _ -> }
                    )
                }
            }
        }
    }

    @Test
    fun `the gate card is not rebuilt by the rejection round trip`() {
        // Robolectric cannot see the real IME, so the keyboard question is
        // asked as an instance question: the visibility toggle is
        // rememberSaveable and nothing resets it, so it surviving
        // required → failed → required proves the card — and with it the
        // focused field the keyboard is attached to — was never rebuilt.
        var item: ChatItem by mutableStateOf(
            ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        )
        setBubble(RecordingKeyboard()) { item }

        composeRule.onNodeWithContentDescription("Show invite code").performClick()
        composeRule.onNodeWithContentDescription("Hide invite code").assertExists()

        item = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        composeRule.waitForIdle()
        item = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Hide invite code")
            .assertExists()
    }

    @Test
    fun `a rejection clears the typed code so the retry starts from an empty field`() {
        // The rejected code is worthless — the user has to type a different
        // one — so the field must be empty whether or not the card survived.
        // Read through the Redeem button, which is disabled on a blank field:
        // the field itself is password-masked.
        var item: ChatItem by mutableStateOf(
            ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        )
        setBubble(RecordingKeyboard()) { item }

        composeRule.onNode(hasSetTextAction()).performTextInput("wrong-code")
        composeRule.onNodeWithText("Redeem code").assertIsEnabled()

        item = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        composeRule.waitForIdle()
        item = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Redeem code").assertIsNotEnabled()
    }

    @Test
    fun `silence past the timeout returns the card to its input state`() {
        composeRule.mainClock.autoAdvance = false
        setGate(RecordingKeyboard())

        submitCode("code-123")
        composeRule.onNodeWithText("Verifying…").assertExists()

        composeRule.mainClock.advanceTimeBy(ONBOARD_VERIFY_TIMEOUT_MS + 500)

        composeRule.onNodeWithText("Verifying…").assertDoesNotExist()
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertExists()
        composeRule.onNodeWithText("Redeem code").assertIsEnabled()
    }

    @Test
    fun `the timed-out card keeps the typed code so a retry needs no retyping`() {
        composeRule.mainClock.autoAdvance = false
        val codes = mutableListOf<String?>()
        setGate(RecordingKeyboard()) { _, code, _ -> codes.add(code) }

        submitCode("code-123")
        composeRule.mainClock.advanceTimeBy(ONBOARD_VERIFY_TIMEOUT_MS + 500)
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertExists()

        // Retry without touching the (password-masked) field at all.
        composeRule.onNodeWithText("Redeem code").performClick()
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)

        assertEquals(
            "the retry must resubmit the code the user already typed",
            listOf("code-123", "code-123"),
            codes
        )
    }

    @Test
    fun `a socket that dies while verifying says so instead of timing out`() {
        composeRule.mainClock.autoAdvance = false
        var live by mutableStateOf(true)
        setGate(RecordingKeyboard(), liveConnection = { live })

        submitCode("code-123")
        composeRule.onNodeWithText("Verifying…").assertExists()

        live = false
        // No clock advance: the drop must be reported on its own, well inside
        // the timeout window, and with its own copy.
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)

        composeRule.onNodeWithText("Verifying…").assertDoesNotExist()
        composeRule.onNodeWithText(ONBOARD_CONNECTION_LOST).assertExists()
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertDoesNotExist()
    }

    @Test
    fun `an explicit rejection keeps the server reason and never shows the timeout copy`() {
        composeRule.mainClock.autoAdvance = false
        val keyboard = RecordingKeyboard()
        var item: ChatItem by mutableStateOf(
            ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        )
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ChatItemBubble(
                        item = item,
                        timestamp = null,
                        onRespond = {},
                        onApprove = { _, _, _, _ -> },
                        onOnboard = { _, _, _ -> },
                        onPlanReviewResponse = {},
                        onUlwResponse = { _, _, _ -> }
                    )
                }
            }
        }

        submitCode("wrong-code")
        item = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)

        composeRule.onNodeWithText("Invalid invite code").assertExists()
        composeRule.onNodeWithText("Verifying…").assertDoesNotExist()

        // The verdict cancelled the wait; the timer must not fire behind it.
        composeRule.mainClock.advanceTimeBy(ONBOARD_VERIFY_TIMEOUT_MS * 2)

        composeRule.onNodeWithText("Invalid invite code").assertExists()
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertDoesNotExist()
    }

    @Test
    fun `a success inside the window never shows the timeout copy`() {
        composeRule.mainClock.autoAdvance = false
        val keyboard = RecordingKeyboard()
        var item: ChatItem by mutableStateOf(
            ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        )
        composeRule.setContent {
            ConnectOnionTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ChatItemBubble(
                        item = item,
                        timestamp = null,
                        onRespond = {},
                        onApprove = { _, _, _, _ -> },
                        onOnboard = { _, _, _ -> },
                        onPlanReviewResponse = {},
                        onUlwResponse = { _, _, _ -> }
                    )
                }
            }
        }

        submitCode("code-123")
        // Well inside the window — the real round trip is ~200 ms.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Verifying…").assertExists()
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertDoesNotExist()

        item = ChatItem.OnboardSuccess(id = "ob1", level = "verified", message = "")
        composeRule.mainClock.advanceTimeBy(ONBOARD_VERIFY_TIMEOUT_MS * 2)

        composeRule.onNodeWithText("Verified — Continuing your request").assertExists()
        composeRule.onNodeWithText(ONBOARD_NO_RESPONSE).assertDoesNotExist()
    }
}
