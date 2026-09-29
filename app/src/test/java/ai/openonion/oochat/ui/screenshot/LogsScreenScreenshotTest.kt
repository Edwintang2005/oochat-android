package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.ui.logs.LogsScreen
import ai.openonion.oochat.util.FileLogger
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The logs console: a top bar whose Refresh/Copy icons should read identically
 * (both themed default, no borrowed teal) next to Clear's error tint, sitting
 * above a panel that stays dark in both palettes but is tinted from the app's
 * own green rather than a neutral black.
 *
 * Seeds [FileLogger]'s backing file directly with fixed-timestamp lines rather
 * than going through `FileLogger.i()`/etc, which stamp the real wall clock and
 * would make the baseline unreproducible from one run to the next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogsScreenScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Before
    fun seedLogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FileLogger.init(context)
        // init() stamps its own "Logger initialized" line with the real clock;
        // clear() drops it so the file holds only our fixed content below.
        FileLogger.clear()
        val logFile = File(File(context.filesDir, "logs"), "app.log")
        logFile.writeText(
            """
            2026-08-03 09:12:01.000 I/AgentConnection: Connected to agent
            2026-08-03 09:12:03.500 D/VoiceTranscribe: Buffered 320 frames
            2026-08-03 09:12:05.250 W/AgentConnection: Ping took 4200ms
            2026-08-03 09:12:07.010 E/AgentConnection: Socket closed unexpectedly
            """.trimIndent()
        )
    }

    private fun capture(palette: Palette) =
        composeRule.captureThemed("logs_screen", palette) {
            LogsScreen(onNavigateBack = {})
        }

    @Test
    fun `logs screen - light`() = capture(Palette.Light)

    @Test
    fun `logs screen - dark`() = capture(Palette.Dark)
}
