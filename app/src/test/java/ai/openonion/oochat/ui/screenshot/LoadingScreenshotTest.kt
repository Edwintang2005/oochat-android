package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.ui.loading.LoadingContent
import ai.openonion.oochat.ui.loading.LoadingOutcome
import ai.openonion.oochat.ui.loading.LoadingUiState
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LoadingScreen had no baseline of any kind, which is how the long wait went
 * unnoticed: for up to ~60s the bar sat at 0.85 with nothing else moving, and
 * no capture existed to show it.
 *
 * The states are rendered from a plain [LoadingUiState] rather than reached
 * through the ViewModel — the long wait would otherwise need a clock, and
 * LoadingViewModelTest already owns proving it is reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(name: String, palette: Palette, uiState: LoadingUiState) =
        composeRule.captureThemed(name, palette) {
            LoadingContent(uiState = uiState, onCancel = {}, onContinueToOnboarding = {})
        }

    private val connecting = LoadingUiState(
        statusMessage = "Connecting to relay.connectonion.com...",
        progress = 0.85f
    )

    /** What the screen said for the whole wait before: one frozen bar, nothing else. */
    private fun captureConnecting(palette: Palette) =
        capture("loading_connecting", palette, connecting)

    /** Past the threshold — the counter is the only thing here that moves. */
    private fun captureLongWait(palette: Palette) =
        capture("loading_long_wait", palette, connecting.copy(waitingSeconds = 42))

    private fun captureConnected(palette: Palette) =
        capture(
            "loading_connected",
            palette,
            LoadingUiState(statusMessage = "Connected!", progress = 1f, outcome = LoadingOutcome.CONNECTED)
        )

    @Test
    fun `loading connecting - light`() = captureConnecting(Palette.Light)

    @Test
    fun `loading connecting - dark`() = captureConnecting(Palette.Dark)

    @Test
    fun `loading long wait - light`() = captureLongWait(Palette.Light)

    @Test
    fun `loading long wait - dark`() = captureLongWait(Palette.Dark)

    @Test
    fun `loading connected - light`() = captureConnected(Palette.Light)

    @Test
    fun `loading connected - dark`() = captureConnected(Palette.Dark)
}
