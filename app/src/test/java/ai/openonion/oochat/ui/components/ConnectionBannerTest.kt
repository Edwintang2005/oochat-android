package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W16AAPPLE-12: the banner over a refused protocol version must agree with the
 * chat's message instead of calling it a failed connection, and still offer
 * the user's way back once the agent or the app has been updated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: ConnectionState, onReconnect: () -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                ConnectionBanner(connectionState = state, onReconnect = onReconnect)
            }
        }
    }

    @Test
    fun `a refused protocol version is named as a version problem, not a failed connection`() {
        show(ConnectionState.Error(message = "This agent uses a different version…", retryable = false))

        composeRule.onNodeWithText("Incompatible version").assertIsDisplayed()
        composeRule.onNodeWithText("Connection failed").assertDoesNotExist()
    }

    @Test
    fun `a refused protocol version still offers Reconnect for after an update`() {
        var reconnects = 0
        show(ConnectionState.Error(message = "This agent uses a different version…", retryable = false)) { reconnects++ }

        composeRule.onNodeWithText("Reconnect").performClick()

        assertEquals(1, reconnects)
    }

    @Test
    fun `an ordinary error keeps the Connection failed banner`() {
        show(ConnectionState.Error(message = "Connection refused"))

        composeRule.onNodeWithText("Connection failed").assertIsDisplayed()
        composeRule.onNodeWithText("Incompatible version").assertDoesNotExist()
    }
}
