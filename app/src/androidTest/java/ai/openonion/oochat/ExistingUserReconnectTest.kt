package ai.openonion.oochat

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.EncryptedPreferencesConnectionConfigRepository
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disabled for the same reason as [ChatLinkSmokeTest] and
 * [CriticalChatWorkflowTest], and it only started failing once the wait
 * below was made able to fail: the fake server performs the transport
 * handshake and nothing more, so the app never receives CONNECTED and never
 * leaves the loading screen. Waiting on the wordmark hid that, because
 * LoadingScreen renders it too.
 *
 * Read [ChatLinkSmokeTest]'s class doc before re-enabling any of the three.
 * Teaching the server to send CONNECTED has already been tried and did not
 * fix them; the next step named there is making the connection observable
 * from a test run at all, by giving FileLogger a logcat mirror.
 */
@Ignore("Fake agent server never completes the app-layer handshake; see ChatLinkSmokeTest's class doc.")
@RunWith(AndroidJUnit4::class)
class ExistingUserReconnectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockServer = MockWebServer()

    @Before
    fun seedConfig() {
        mockServer.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = EncryptedPreferencesConnectionConfigRepository(context)

        val wsUrl = mockServer.url("/").toString().replace("http", "ws")

        runBlocking {
            repo.deleteConfig()
            repo.saveConfig(
                ConnectionConfig(
                    serverUrl = wsUrl,
                    apiKey = "test-key",
                    agentAddress = "0xMockAgent"
                )
            )
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun existingUser_skipsOnboarding_andLandsOnChat() {
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {}
        }))
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        composeTestRule.setContent {
            ConnectOnionTheme {
                ConnectOnionApp()
            }
        }

        composeTestRule.onNodeWithText("Server URL").assertDoesNotExist()

        // Not the "ConnectOnion" wordmark this used to wait on: LoadingScreen
        // and ConnectOnionApp render that too, so an app that reached the
        // loading screen and stopped there satisfied a test named for chat.
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag("chat_top_bar").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("chat_top_bar").assertExists()
        composeTestRule.onNodeWithContentDescription("Open menu").assertExists()
    }
}
