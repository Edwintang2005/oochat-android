package ai.openonion.oochat.ui

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.agent.AgentItem
import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import ai.openonion.oochat.ui.chat.components.VoiceInputState
import ai.openonion.oochat.ui.components.ConnectionBanner
import ai.openonion.oochat.ui.settings.components.IdentityRow
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch-target regression tests for the 24 fixes made across
 * fix(chat)/fix(settings)/fix(agent)/fix(nav) — spot checks, not full
 * coverage, established so future interactive elements have somewhere to
 * add an assertion instead of relying on a manual modifier-chain read.
 *
 * Runs Compose semantics assertions on the JVM via Robolectric — no device
 * or emulator needed. [GraphicsMode.Mode.NATIVE] is required for
 * [createComposeRule] to lay out real content under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchTargetComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `InputBar attachment menu button meets 48dp`() {
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
            }
        }

        composeRule.onNodeWithContentDescription("Open attachment menu")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
    }

    @Test
    fun `InputBar mic button meets 48dp`() {
        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(isConnected = true, isSending = false, onSend = { _, _, _ -> })
            }
        }

        // Mic shows whenever there's nothing typed/attached yet — the
        // InputBar's initial state.
        // The mic is always in the pill now, alongside Send — dictation
        // appends to whatever is in the field, so they stopped being alternatives.
        composeRule.onNodeWithContentDescription("Dictate a message")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
    }

    @Test
    fun `InputBar dictation waveform meets 48dp`() {
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

        // The bars are drawn 28dp tall; the tappable box around them is a full
        // 48dp, which the row was already that tall to fit the cancel button.
        // Width comes from weight(1f), so a minimum check is enough there.
        composeRule.onNodeWithContentDescription("Finish dictation")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `AccountCards Copy button keeps 28dp visual but 48dp touch target`() {
        composeRule.setContent {
            ConnectOnionTheme {
                IdentityRow(label = "Wallet address", value = "0xabc123", onCopy = {})
            }
        }

        // The visual pill is deliberately 28dp tall (a 48dp pill would
        // dominate the row) — minimumInteractiveComponentSize() pads the
        // touch bounds out to 48dp invisibly around it. This is the
        // "visual != touch" case future readers are most likely to conflate.
        // Width has no such gap: the pill's own text content already makes
        // it wider than 48dp, so a plain minimum check is enough there.
        composeRule.onNodeWithText("Copy")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `AgentListScreen drag handle meets 48dp`() {
        val agent = AgentProfile(
            id = "agent-1",
            address = "0xabc123",
            name = "Test Agent",
            serverUrl = "wss://example.com",
            createdAt = 0L
        )

        composeRule.setContent {
            ConnectOnionTheme {
                AgentItem(
                    agent = agent,
                    isSelected = false,
                    isDefault = false,
                    onSelect = {},
                    onEdit = {},
                    onDelete = {},
                    onSetDefault = {},
                    dragHandleModifier = Modifier.testTag("dragHandle")
                )
            }
        }

        composeRule.onNodeWithTag("dragHandle", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `ConnectionBanner Stop link grows past the old 24dp without needing 48dp`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ConnectionBanner(
                    connectionState = ConnectionState.Connecting,
                    onReconnect = {},
                    onStop = {}
                )
            }
        }

        // Documents the deliberate ~40dp compromise from
        // fix(a11y): enlarge the banner's action hit area without
        // thickening the banner — grown past the old 24dp, short of the
        // full 48dp guideline on purpose (see ConnectionBanner.kt).
        val stopBounds = composeRule.onNodeWithText("Stop").getBoundsInRoot()
        val height = stopBounds.bottom - stopBounds.top
        assertTrue("Expected the Stop link to grow past its old 24dp height, was $height", height > 24.dp)
        assertTrue("Expected the Stop link to stay short of the full 48dp target, was $height", height < 48.dp)
    }
}
