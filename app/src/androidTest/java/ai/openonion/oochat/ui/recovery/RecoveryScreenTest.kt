package ai.openonion.oochat.ui.recovery

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.di.FakeConnectionConfigRepository
import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for [RecoveryScreen] — shown when automatic reconnection
 * fails. [RecoveryViewModel] is constructed directly with a
 * [FakeConnectionConfigRepository] (its `configRepository` constructor
 * param is designed to be swappable — see its class KDoc) and passed to
 * [RecoveryScreen] via its own `viewModel` parameter, so no
 * Activity/ViewModelStore scope or real on-device container is needed —
 * `createComposeRule()` is enough.
 */
class RecoveryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private val savedConfig = ConnectionConfig(
        serverUrl = "https://relay.example.com",
        agentAddress = "0xFeedFace9999"
    )

    @Test
    fun savedConfigurationIsShownReadOnlyAlongsideTheGivenFailureReason() {
        val viewModel = RecoveryViewModel(
            application,
            configRepository = FakeConnectionConfigRepository(initialConfig = savedConfig)
        )
        val failureReason = "Timed out waiting for the agent to respond."

        composeTestRule.setContent {
            RecoveryScreen(
                errorMessage = failureReason,
                onRetry = {},
                onEditConfiguration = {},
                viewModel = viewModel
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(failureReason).assertIsDisplayed()

        // The config is reference material, so it starts collapsed behind this
        // toggle — the failure reason and the Retry button are what the screen
        // leads with. Expand it before asserting on the values.
        composeTestRule.onNodeWithText("View saved config").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Server: https://relay.example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Agent: 0xFeedFace9999").assertIsDisplayed()
    }

    @Test
    fun noExplicitFailureReasonFallsBackToTheGenericConnectionErrorMessage() {
        val viewModel = RecoveryViewModel(
            application,
            configRepository = FakeConnectionConfigRepository(initialConfig = savedConfig)
        )

        composeTestRule.setContent {
            RecoveryScreen(
                errorMessage = null,
                onRetry = {},
                onEditConfiguration = {},
                viewModel = viewModel
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "Unable to connect to the agent. Check your network and the saved configuration, then retry."
        ).assertIsDisplayed()
    }

    @Test
    fun editConfigurationButtonIsReachableAndInvokesTheCallback() {
        val viewModel = RecoveryViewModel(
            application,
            configRepository = FakeConnectionConfigRepository(initialConfig = savedConfig)
        )
        var editConfigurationClicked = false

        composeTestRule.setContent {
            RecoveryScreen(
                errorMessage = "Connection refused",
                onRetry = {},
                onEditConfiguration = { editConfigurationClicked = true },
                viewModel = viewModel
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Edit configuration")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue(editConfigurationClicked)
    }
}
