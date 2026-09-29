package ai.openonion.oochat

import ai.openonion.oochat.ui.chat.ChatScreen
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.app.Application
import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/** Instrumentation coverage for the app's critical chat workflows. */
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
class CriticalChatWorkflowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sendMessage_getsAgentReply_rendersInOrder() {
        ReplyingAgentServer(reply = AGENT_REPLY).use { agent ->
            val viewModel = newViewModel()
            showChat(viewModel)
            viewModel.connectToAgent(TEST_AGENT_ADDRESS, agent.httpUrl)
            waitForText("Connected")

            messageInput().performTextInput(USER_MESSAGE)
            composeTestRule.onNode(hasContentDescription("Send")).performClick()

            waitForText(USER_MESSAGE)
            waitForText(AGENT_REPLY)
            assertEquals(
                "The fake agent must receive the user's transmitted prompt",
                USER_MESSAGE,
                agent.awaitPrompt()
            )

            val userTop = composeTestRule.onNode(hasText(USER_MESSAGE))
                .fetchSemanticsNode().boundsInRoot.top
            val agentTop = composeTestRule.onNode(hasText(AGENT_REPLY))
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue(
                "The user bubble must render before the agent reply",
                userTop < agentTop
            )
        }
    }

    @Test
    fun history_survivesViewModelRecreate() {
        ReplyingAgentServer(reply = AGENT_REPLY).use { agent ->
            val firstViewModel = newViewModel()
            showChat(firstViewModel)
            firstViewModel.connectToAgent(TEST_AGENT_ADDRESS, agent.httpUrl)
            waitForText("Connected")

            messageInput().performTextInput(USER_MESSAGE)
            composeTestRule.onNode(hasContentDescription("Send")).performClick()
            waitForText(AGENT_REPLY)

            val recreatedViewModel = newViewModel()
            showChat(recreatedViewModel)

            // Persistence invariant: conversation history is
            // restored after the ViewModel/UI owner is recreated.
            composeTestRule.onNode(hasText(USER_MESSAGE)).assertExists()
            composeTestRule.onNode(hasText(AGENT_REPLY)).assertExists()
        }
    }

    private fun showChat(viewModel: ChatViewModel) {
        composeTestRule.setContent {
            ConnectOnionTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }

    private fun messageInput(): SemanticsNodeInteraction = composeTestRule.onNode(
        hasText("Message…") and hasSetTextAction()
    )

    private fun waitForText(text: String, timeoutMillis: Long = 10_000L) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun newViewModel(): ChatViewModel =
        ChatViewModel(context.applicationContext as Application)

    /** Minimal local WebSocket server: accept INPUT, then emit an assistant event. */
    private class ReplyingAgentServer(
        private val reply: String
    ) : AutoCloseable {
        private val promptLatch = CountDownLatch(1)
        private val serverSocket = ServerSocket(0)
        private val serverThread = Thread { serve() }

        @Volatile
        private var receivedPrompt: String? = null

        val httpUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            serverThread.start()
        }

        fun awaitPrompt(): String? {
            promptLatch.await(5, TimeUnit.SECONDS)
            return receivedPrompt
        }

        private fun serve() {
            runCatching {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    writeHandshake(input, output)
                    val request = readClientTextFrame(input)
                    receivedPrompt = PROMPT_PATTERN.find(request)
                        ?.groupValues
                        ?.get(1)
                        ?.replace("\\\"", "\"")
                    promptLatch.countDown()
                    writeServerTextFrame(
                        output,
                        """{"type":"assistant","id":"agent-reply-1","content":"$reply"}"""
                    )
                    while (!serverSocket.isClosed) {
                        Thread.sleep(25)
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

        private fun readClientTextFrame(input: InputStream): String {
            input.read()
            val second = input.read()
            var length = second and 0x7F
            if (length == 126) {
                length = (input.read() shl 8) or input.read()
            } else if (length == 127) {
                var longLength = 0L
                repeat(8) { longLength = (longLength shl 8) or input.read().toLong() }
                length = longLength.toInt()
            }
            val mask = ByteArray(4) { input.read().toByte() }
            val payload = ByteArray(length)
            repeat(length) { index ->
                payload[index] = (input.read() xor mask[index % 4].toInt()).toByte()
            }
            return payload.toString(Charsets.UTF_8)
        }

        private fun writeServerTextFrame(output: OutputStream, text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            output.write(0x81)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> error("Test payload is unexpectedly large")
            }
            output.write(payload)
            output.flush()
        }

        override fun close() {
            serverSocket.close()
            serverThread.interrupt()
            serverThread.join(1_000)
        }

        private companion object {
            val PROMPT_PATTERN = Regex("\\\"prompt\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"")
            const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        }
    }

    private companion object {
        const val TEST_AGENT_ADDRESS = "sprint2-test-agent"
        const val USER_MESSAGE = "hello from critical workflow"
        const val AGENT_REPLY = "hello from fake agent"
    }
}
