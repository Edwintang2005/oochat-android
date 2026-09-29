package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.rules.ExternalResource
import java.util.Locale
import java.util.TimeZone

/**
 * Where the checked-in baselines live — under `src/`, not `build/`, so a clean
 * checkout has something to diff against.
 *
 * Record with `./gradlew :app:recordRoborazziDebug`, check with
 * `./gradlew :app:verifyRoborazziDebug`. A plain `testDebugUnitTest` (what CI
 * runs) leaves capture switched off entirely.
 */
internal const val SCREENSHOT_DIR = "src/test/screenshots"

/**
 * Every case is captured twice. Most of what a material3 bump moves is a color
 * role, and a role that only shifted in dark is invisible to a light baseline.
 */
internal enum class Palette(val themeMode: ThemeMode) {
    Light(ThemeMode.LIGHT),
    Dark(ThemeMode.DARK),
}

/**
 * Pins the two ambient values a snapshot silently absorbs: the default zone
 * (bubble timestamps are formatted in it) and the locale (which decides "AM"
 * and the month names in a date divider). Without this, a baseline recorded in
 * Sydney fails in CI's UTC.
 */
class DeterministicLocaleRule : ExternalResource() {
    private lateinit var timeZone: TimeZone
    private lateinit var locale: Locale

    override fun before() {
        timeZone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    override fun after() {
        TimeZone.setDefault(timeZone)
        Locale.setDefault(locale)
    }
}

/**
 * Renders [content] under the app theme, runs [interact] (open a menu, open a
 * sheet), and writes/compares `screenshots/<name>_<palette>.png`.
 *
 * `fontScale` is pinned rather than left at its default, which reads the user's
 * persisted "Font size" setting out of DataStore. Repeatability otherwise rests
 * on the Compose test clock being virtual — what it can't rescue is a fixture
 * whose own content moves, so the cases pass fixed timestamps and stay off the
 * states that animate forever (`AgentStatus.Connecting`).
 */
@OptIn(ExperimentalRoborazziApi::class) // captureScreenRoboImage
internal fun ComposeContentTestRule.captureThemed(
    name: String,
    palette: Palette,
    interact: (ComposeContentTestRule.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    setContent {
        ConnectOnionTheme(themeMode = palette.themeMode, fontScale = 1f) {
            content()
        }
    }
    waitForIdle()
    interact?.invoke(this)
    waitForIdle()

    // A ModalBottomSheet and a DropdownMenu each own a window, so each adds a
    // semantics root — which makes onRoot() ambiguous and would have framed
    // only the screen behind them. Compositing the device screen is the one
    // capture that sees them; everything else stays on the tighter root shot.
    val path = "$SCREENSHOT_DIR/${name}_$palette.png"
    if (onAllNodes(isRoot()).fetchSemanticsNodes().size > 1) {
        captureScreenRoboImage(path)
    } else {
        onRoot().captureRoboImage(path)
    }
}
