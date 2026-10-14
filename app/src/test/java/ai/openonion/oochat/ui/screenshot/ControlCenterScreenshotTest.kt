package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.controlcenter.ControlCenterActions
import ai.openonion.oochat.ui.controlcenter.ControlCenterAgentStatus
import ai.openonion.oochat.ui.controlcenter.ControlCenterConnection
import ai.openonion.oochat.ui.controlcenter.ControlCenterConversation
import ai.openonion.oochat.ui.controlcenter.ControlCenterScreen
import ai.openonion.oochat.ui.controlcenter.ControlCenterScreenContent
import ai.openonion.oochat.ui.controlcenter.ControlCenterSnapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent's Home when it has a Control Center app. Robolectric's WebView paints nothing, so
 * the loaded case captures the chrome around the app, as DashboardScreenshotTest does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlCenterScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64))

    private val snapshot = ControlCenterSnapshot(
        sessionId = "conv-1",
        agentAddress = "0xagent",
        status = ControlCenterAgentStatus.IDLE,
        connectionState = ControlCenterConnection.CONNECTED,
        skills = emptyList()
    )

    private val actions = object : ControlCenterActions {
        override suspend fun sendMessage(message: String, conversation: ControlCenterConversation) = "conv-1"
        override suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation) = "conv-1"
    }

    private fun capture(name: String, palette: Palette, app: ControlCenterApp?, supported: Boolean = true, awaiting: Boolean = false) =
        composeRule.captureThemed(name, palette) {
            ControlCenterScreen(
                app = app,
                snapshot = snapshot,
                actions = actions,
                onNavigateBack = {},
                agentName = "research-assistant",
                chatAwaitsUser = awaiting,
                supported = supported
            )
        }

    private fun captureStopped(palette: Palette) =
        composeRule.captureThemed("control_center_screen_stopped", palette) {
            ControlCenterScreenContent(
                app = app,
                supported = true,
                stopped = true,
                onReload = {},
                content = {},
                onNavigateBack = {},
                agentName = "research-assistant",
                chatAwaitsUser = false,
                navigationBlocked = false,
                onDismissNavigationBlocked = {}
            )
        }

    @Test
    fun `control center - light`() = capture("control_center_screen", Palette.Light, app)

    @Test
    fun `control center - dark`() = capture("control_center_screen", Palette.Dark, app)

    @Test
    fun `control center awaiting the user - light`() = capture("control_center_screen_awaiting", Palette.Light, app, awaiting = true)

    @Test
    fun `control center awaiting the user - dark`() = capture("control_center_screen_awaiting", Palette.Dark, app, awaiting = true)

    @Test
    fun `control center with no app - light`() = capture("control_center_screen_empty", Palette.Light, null)

    @Test
    fun `control center with no app - dark`() = capture("control_center_screen_empty", Palette.Dark, null)

    @Test
    fun `control center unsupported - light`() = capture("control_center_screen_unsupported", Palette.Light, app, supported = false)

    @Test
    fun `control center unsupported - dark`() = capture("control_center_screen_unsupported", Palette.Dark, app, supported = false)

    @Test
    fun `control center stopped - light`() = captureStopped(Palette.Light)

    @Test
    fun `control center stopped - dark`() = captureStopped(Palette.Dark)
}
