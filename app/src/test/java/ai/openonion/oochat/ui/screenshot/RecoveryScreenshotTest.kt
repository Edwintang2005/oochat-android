package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.ui.recovery.RecoveryScreen
import ai.openonion.oochat.ui.recovery.RecoveryViewModel
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one full screen in this suite captured as itself rather than
 * reassembled: `RecoveryViewModel` takes its repository as a constructor
 * parameter, so a stub is enough to stand the screen up with no app container,
 * no Room and no network.
 *
 * It is also the suite's filled `Button` on a page-level layout.
 * `LoadingScreen` and `OnboardingScreen` share that chrome but are not
 * captured: both start real work as they compose, and LoadingScreen's outcome
 * is an `AnimatedContent` over a delay-driven progress value — a baseline of
 * it would be a baseline of one arbitrary animation frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(palette: Palette) = composeRule.captureThemed("recovery_screen", palette) {
        RecoveryScreen(
            errorMessage = "Relay refused the connection: handshake timed out after 30s.",
            onRetry = {},
            onEditConfiguration = {},
            viewModel = RecoveryViewModel(
                application = ApplicationProvider.getApplicationContext(),
                configRepository = StubConfigRepository,
            ),
        )
    }

    @Test
    fun `recovery screen - light`() = capture(Palette.Light)

    @Test
    fun `recovery screen - dark`() = capture(Palette.Dark)

    private object StubConfigRepository : ConnectionConfigRepository {
        private val config = ConnectionConfig(
            serverUrl = "https://oo.openonion.ai",
            agentAddress = "0x4f3a9c2b8e1d7a6f5c4b3a2918d7e6f5c4b3a291",
        )

        override suspend fun getConfig(): ConnectionConfig = config
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) = Unit
        override suspend fun deleteConfig() = Unit
        override suspend fun hasConfig(): Boolean = true
        override suspend fun updateLastConnected(timestamp: Long) = Unit
        override suspend fun updateAgentAddress(address: String?) = Unit
    }
}
