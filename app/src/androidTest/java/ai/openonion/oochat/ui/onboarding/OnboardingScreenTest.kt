package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.di.FakeAgentDiscoveryRepository
import ai.openonion.oochat.di.FakeAgentRepository
import ai.openonion.oochat.di.FakeConnectionConfigRepository
import ai.openonion.oochat.di.FakeDefaultAgentRepository
import ai.openonion.oochat.ui.agent.AgentViewModel
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for [OnboardingScreen] — the radar-first EmbeddedDiscoveryPanel that leads
 * the screen, the manual Server URL/Agent Address form below it, and the
 * prefill-from-saved-[ConnectionConfig] behavior
 * [OnboardingViewModel] already had (`prefillConfig` / `isPrefillLoaded`).
 *
 * Both [OnboardingViewModel] and [AgentViewModel] are constructed directly with fakes and handed
 * in via [OnboardingScreen]'s own `onboardingViewModel`/`agentViewModel` parameters, instead of
 * going through the screen's default `appViewModel()` resolution — critically, [AgentViewModel]
 * now triggers a real discovery HTTP call the instant this screen composes (EmbeddedDiscoveryPanel
 * auto-scans on mount), so leaving it on the real on-device container would make every test in
 * this file attempt a live network request. [FakeAgentDiscoveryRepository.discoverFromRelay]
 * resolves instantly to an empty list, so the panel settles into its "No agents found" state
 * deterministically and the fallback toggle is reachable immediately.
 *
 * Uses [createAndroidComposeRule] (not the plain `createComposeRule`) since both ViewModels are
 * `AndroidViewModel`s needing a real Context/Activity scope.
 */
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun fakeAgentViewModel(configRepository: FakeConnectionConfigRepository) = AgentViewModel(
        application,
        FakeAgentRepository(),
        FakeDefaultAgentRepository(),
        FakeAgentDiscoveryRepository(),
        configRepository
    )

    @Test
    fun withNoSavedConfigTheFallbackFormStartsWithTheDefaultRelayURLAndABlankAgentAddress() {
        val repository = FakeConnectionConfigRepository(initialConfig = null)
        val viewModel = OnboardingViewModel(application, configRepository = repository)

        composeTestRule.setContent {
            OnboardingScreen(
                onProceedToConnect = {},
                onboardingViewModel = viewModel,
                agentViewModel = fakeAgentViewModel(repository)
            )
        }
        composeTestRule.waitForIdle()

        // AgentFormState()'s own default, not a saved value — confirms a
        // genuine first run doesn't show a stale/leftover address.
        composeTestRule.onNodeWithText("https://oo.openonion.ai").assertIsDisplayed()
        composeTestRule.onNodeWithText("0x1234... or pick a saved agent").assertIsDisplayed()
    }

    @Test
    fun aPreviouslySavedConfigPrefillsTheFallbackFormSServerURLAndAgentAddressFields() {
        val saved = ConnectionConfig(
            serverUrl = "https://my-custom-relay.example.com",
            agentAddress = "0xdeadbeef1234"
        )
        val repository = FakeConnectionConfigRepository(initialConfig = saved)
        val viewModel = OnboardingViewModel(application, configRepository = repository)

        composeTestRule.setContent {
            OnboardingScreen(
                onProceedToConnect = {},
                onboardingViewModel = viewModel,
                agentViewModel = fakeAgentViewModel(repository)
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("https://my-custom-relay.example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("0xdeadbeef1234").assertIsDisplayed()
    }

    @Test
    fun anInvalidServerURLSurfacesAnInlineErrorInsteadOfSilentlySaving() {
        val saved = ConnectionConfig(
            serverUrl = "https://my-custom-relay.example.com",
            agentAddress = "0xdeadbeef1234"
        )
        val repository = FakeConnectionConfigRepository(initialConfig = saved)
        val viewModel = OnboardingViewModel(application, configRepository = repository)

        composeTestRule.setContent {
            OnboardingScreen(
                onProceedToConnect = {},
                onboardingViewModel = viewModel,
                agentViewModel = fakeAgentViewModel(repository)
            )
        }
        composeTestRule.waitForIdle()

        // Both fields stay non-blank (so the Connect button remains enabled
        // — AgentFormState.isValid only checks blankness/URL-shaped address,
        // not the server URL's scheme) but the URL itself is malformed.
        composeTestRule.onNodeWithText("https://my-custom-relay.example.com")
            .performTextReplacement("not-a-url")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Please enter a valid URL (e.g., https://server.com)")
            .assertIsDisplayed()

        // And the malformed value was never persisted over the saved config.
        val stillSaved = runBlocking { repository.getConfig() }
        assertEquals("https://my-custom-relay.example.com", stillSaved?.serverUrl)
    }
}
