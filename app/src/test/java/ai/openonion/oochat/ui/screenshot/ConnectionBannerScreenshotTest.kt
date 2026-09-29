package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.components.ConnectionBanner
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The offline banner — the one state that says something no [ConnectionState]
 * can, since the socket still believes it is connected for the 15-30s its ping
 * takes to notice a radio that went away.
 *
 * Captured twice. `Connected` is the real case — the drop happened mid-session
 * and the socket has not caught up — but it matches no other branch, so it
 * cannot pin precedence. `Error` can: it is the one state where demoting
 * `isOffline` swaps the offline banner for a "Connection failed" Reconnect row.
 *
 * `Connecting` is not captured: its `CircularProgressIndicator` animates
 * forever, so any baseline is one arbitrary frame (see [ScreenshotTestBase]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionBannerScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(name: String, palette: Palette, connectionState: ConnectionState) =
        composeRule.captureThemed(name, palette) {
            ConnectionBanner(
                connectionState = connectionState,
                onReconnect = {},
                onStop = {},
                isOffline = true,
            )
        }

    private fun captureOffline(palette: Palette) = capture(
        name = "connection_banner_offline",
        palette = palette,
        connectionState = ConnectionState.Connected(address = "0x4f3a9c2b8e1d7a6f")
    )

    /** The precedence fixture: [ConnectionState.Error] matches a lower branch too. */
    private fun captureOverError(palette: Palette) = capture(
        name = "connection_banner_offline_over_error",
        palette = palette,
        connectionState = ConnectionState.Error(message = "Connection refused")
    )

    @Test
    fun `offline banner - light`() = captureOffline(Palette.Light)

    @Test
    fun `offline banner - dark`() = captureOffline(Palette.Dark)

    @Test
    fun `offline outranks error - light`() = captureOverError(Palette.Light)

    @Test
    fun `offline outranks error - dark`() = captureOverError(Palette.Dark)
}
