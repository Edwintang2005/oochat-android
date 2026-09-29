package ai.openonion.oochat

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.EncryptedPreferencesConnectionConfigRepository
import ai.openonion.oochat.ui.chat.ChatScreen
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.onboarding.OnboardingScreen
import ai.openonion.oochat.ui.recovery.RecoveryScreen
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.app.Application
import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DISABLED 2026-08-02. This class has never once executed successfully.
 *
 * Written 2026-06-30 through 2026-07-30, it could not even be dexed until
 * today: DEX below version 040 rejects spaces in a SimpleName, and every test
 * here was named with backticks. Nothing in CI runs instrumented tests, so
 * `compileDebugAndroidTestKotlin` passing was mistaken for the suite passing,
 * and the file was edited several times after that without ever running.
 *
 * Renaming the methods got it onto a device, which is how the rest surfaced.
 * Two problems are confirmed and one is not:
 *
 * Confirmed — the selectors. `hasText("Server URL") and hasSetTextAction()`
 * matches nothing, because the caption is a static FieldLabel sitting beside
 * the editable PillTextField rather than inside it. Fixing that let the form
 * fill and submit.
 *
 * Confirmed — the fake server only performs the transport handshake (HTTP 101).
 * It never answers the app's CONNECT frame with CONNECTED, and treats that
 * first frame as if it were the user's prompt.
 *
 * NOT explained — teaching the server to send CONNECTED did *not* make these
 * pass. They still time out at waitForText("Connected"), and the reason is
 * unknown: it may be the hand-rolled frame reader/writer here, it may be
 * device-side scheduling (this hardware killed the test process once
 * mid-run), or it may be something in the app. Nobody has seen inside
 * AgentConnection during these runs, because FileLogger writes only to a file
 * and is never initialised when a test drives Compose directly instead of
 * launching MainActivity.
 *
 * So: do not re-enable by assuming the handshake is the whole story. Start by
 * giving FileLogger a logcat mirror so the connection is observable, then work
 * from what it actually shows.
 */
@Ignore("Fake agent server never completes the app-layer handshake; see the class doc.")
@RunWith(AndroidJUnit4::class)
class ChatLinkSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var configRepository: EncryptedPreferencesConnectionConfigRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        configRepository = EncryptedPreferencesConnectionConfigRepository(context)
        clearSavedConfig()
    }

    @After
    fun tearDown() {
        clearSavedConfig()
    }

    @Test
    fun newUser_canSubmitConfig_andReachChat() {
        LocalAgentServer().use { agent ->
            val chatViewModel = newChatViewModel()

            composeTestRule.setContent {
                var showChat by remember { mutableStateOf(false) }

                ConnectOnionTheme {
                    if (showChat) {
                        ChatScreen(viewModel = chatViewModel)
                    } else {
                        OnboardingScreen(
                            onProceedToConnect = {
                                chatViewModel.connectToAgent(
                                    address = TEST_AGENT_ADDRESS,
                                    directUrl = agent.httpUrl
                                )
                                showChat = true
                            }
                        )
                    }
                }
            }

            // OnboardingScreen leads with an auto-scanning discovery panel, and
            // the manual Server URL/Agent Address fields sit right below it
            // rather than behind a collapsed toggle — so this flow goes
            // straight to the Direct Connection mode switch.
            composeTestRule.onNode(hasText("Direct Connection")).performClick()
            serverUrlField().performTextInput(agent.httpUrl)
            agentAddressField().performTextInput(TEST_AGENT_ADDRESS)
            composeTestRule.onNode(hasText("Connect")).performClick()

            waitForText("Connected")

            composeTestRule.onNode(hasText("Message…") and hasSetTextAction())
                .performTextInput("hello from androidTest")
            composeTestRule.onNode(hasContentDescription("Send")).performClick()

            composeTestRule.onNode(hasText("hello from androidTest")).assertExists()
            assertTrue(
                "The local agent should receive the transmitted prompt",
                agent.awaitMessageContaining("hello from androidTest")
            )
        }
    }

    @Test
    fun existingUser_autoReconnects_onLaunch() {
        LocalAgentServer().use { agent ->
            runBlocking {
                configRepository.saveConfig(
                    ConnectionConfig(
                        serverUrl = agent.httpUrl,
                        agentAddress = TEST_AGENT_ADDRESS,
                        connectionTimeout = 5_000L
                    )
                )
            }

            val chatViewModel = newChatViewModel()

            composeTestRule.setContent {
                ConnectOnionTheme {
                    LaunchedEffect(Unit) {
                        chatViewModel.connectToAgent(TEST_AGENT_ADDRESS, agent.httpUrl)
                    }
                    ChatScreen(viewModel = chatViewModel)
                }
            }

            waitForText("Connected")
            composeTestRule.onNode(hasText("No messages yet", substring = true)).assertExists()
        }
    }

    @Test
    fun networkDrop_showsRecoveryScreen() {
        LocalAgentServer(closeAfterHandshake = true).use { agent ->
            runBlocking {
                configRepository.saveConfig(
                    ConnectionConfig(
                        serverUrl = agent.httpUrl,
                        agentAddress = TEST_AGENT_ADDRESS,
                        connectionTimeout = 1_000L
                    )
                )
            }

            composeTestRule.setContent {
                ConnectOnionTheme {
                    RecoveryScreen(
                        onRetry = {},
                        onEditConfiguration = {}
                    )
                }
            }

            composeTestRule.onNode(hasText("Connection Failed")).assertExists()
            composeTestRule.onNode(hasText("Retry Connection")).performClick()
            waitForText(
                "Unable to connect to the agent. Check your network and the saved configuration, then retry."
            )
        }
    }

    // Verified against a real semantics dump, not reasoned about: the editable
    // node carries `EditableText = ''` and nothing else, with "Server URL" and
    // the placeholder beside it as their own Text nodes. Any
    // `hasText(...) and hasSetTextAction()` therefore matches nothing, whatever
    // string is used — the original selector was not merely pointing at the
    // wrong text. Selecting by position is the only option until the fields
    // carry a testTag; in Direct Connection mode the panel renders server URL
    // first, agent address second.
    //
    // This gets past the field lookup. Whether the rest of the test then
    // passes is unknown — it still fails later, and this hardware was observed
    // hibernating the process mid-run ("AppFastHibernation ... for visible
    // pkg"), so the remaining failure has not been isolated.
    private fun serverUrlField(): SemanticsNodeInteraction =
        composeTestRule.onAllNodes(hasSetTextAction())[0]

    private fun agentAddressField(): SemanticsNodeInteraction =
        composeTestRule.onAllNodes(hasSetTextAction())[1]
    private fun waitForText(text: String, timeoutMillis: Long = 10_000L) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun newChatViewModel(): ChatViewModel {
        return ChatViewModel(context.applicationContext as Application)
    }

    private fun clearSavedConfig() {
        runBlocking {
            configRepository.deleteConfig()
        }
    }

    private class LocalAgentServer(
        private val closeAfterHandshake: Boolean = false
    ) : AutoCloseable {
        private val receivedText = AtomicReference("")
        private val messageLatch = CountDownLatch(1)
        private val serverSocket = ServerSocket(0)
        private val serverThread = Thread { serve() }

        val httpUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            serverThread.start()
        }

        fun awaitMessageContaining(text: String): Boolean {
            val received = messageLatch.await(5, TimeUnit.SECONDS)
            return received && receivedText.get().contains(text)
        }

        private fun serve() {
            while (!serverSocket.isClosed) {
                runCatching {
                    serverSocket.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        writeHandshake(input, output)
                        if (closeAfterHandshake) return@use
                        val message = readFrame(input)
                        receivedText.set(message)
                        messageLatch.countDown()
                    }
                }
            }
        }

        private fun writeHandshake(input: InputStream, output: OutputStream) {
            val reader = BufferedReader(InputStreamReader(input))
            var key = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(":").trim()
                }
                if (line.isEmpty()) break
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    "$key$WS_GUID".toByteArray(Charsets.ISO_8859_1)
                )
            )
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
            )
            output.flush()
        }

        private fun readFrame(input: InputStream): String {
            input.read()
            val second = input.read()
            var length = second and 0x7F
            if (length == 126) {
                length = (input.read() shl 8) or input.read()
            }
            val mask = ByteArray(4)
            repeat(4) { mask[it] = input.read().toByte() }
            val payload = ByteArray(length)
            repeat(length) { index ->
                payload[index] = (input.read() xor mask[index % 4].toInt()).toByte()
            }
            return payload.toString(Charsets.UTF_8)
        }

        override fun close() {
            serverSocket.close()
            serverThread.join(1_000)
        }
    }

    private companion object {
        private const val TEST_AGENT_ADDRESS = "test-agent-address"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
