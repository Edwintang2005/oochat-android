package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stock material3 controls this app leans on, in every state it renders
 * them in, with no app-supplied `colors` — so the capture is of the library's
 * defaults rather than of anything this codebase chose.
 *
 * That is deliberately the narrowest and most sensitive case in the suite: a
 * material3 version bump that only re-tones a default container or content
 * role would leave every screen-level baseline visually "the same" while
 * moving these. It also stands in for the filled buttons on `LoadingScreen`
 * and `OnboardingScreen`, neither of which can be captured as a screen (see
 * [RecoveryScreenshotTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlChromeScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(palette: Palette) =
        composeRule.captureThemed("control_chrome", palette) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Filled — enabled") }

                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Filled — disabled") }

                // No `colors`: this is the shape IdentitySheets' reveal gate
                // takes, and the label tone is the value the upgrade review
                // flagged as one that moves.
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Outlined — default label color") }

                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Outlined — disabled") }

                TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text("Text — enabled")
                }

                TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Text — disabled")
                }

                Switch(checked = true, onCheckedChange = {})
                Switch(checked = false, onCheckedChange = {})
                Switch(checked = true, enabled = false, onCheckedChange = {})
                Switch(checked = false, enabled = false, onCheckedChange = {})
            }
        }

    @Test
    fun `control chrome - light`() = capture(Palette.Light)

    @Test
    fun `control chrome - dark`() = capture(Palette.Dark)
}
