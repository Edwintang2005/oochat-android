package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The browser side of the bridge on a real WebView: the production parent page and `host.js`,
 * the real [ControlCenterPage] and [ControlCenterHost], and a fake app
 * (`androidTest/assets/control-center-test/app.html`) at an https origin of its own. Only the
 * chat is faked. The Robolectric tests run no page, so this is where `host.js`, `ochatNative`,
 * the iframe's `ready` and the transferred `MessagePort` are exercised.
 */
class ControlCenterBridgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val reports = ConcurrentLinkedQueue<Pair<String, String>>()
    private val fromPage = ConcurrentLinkedQueue<String>()
    private val pageOrigins = ConcurrentLinkedQueue<String>()
    private val sent = ConcurrentLinkedQueue<Pair<String, ControlCenterConversation>>()
    @Volatile private var unavailable = false

    private val actions = object : ControlCenterActions {
        override suspend fun sendMessage(message: String, conversation: ControlCenterConversation): String {
            sent += message to conversation
            return SESSION
        }

        override suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation) =
            error("not used")
    }

    private val snapshot = ControlCenterSnapshot(
        sessionId = null,
        agentAddress = "0xagent",
        status = ControlCenterAgentStatus.IDLE,
        connectionState = ControlCenterConnection.CONNECTED,
        skills = emptyList()
    )

    @Before
    fun needsMessageListeners() {
        assumeTrue("this WebView cannot host the app", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    }

    /** Serves the fake app, and records what it reports. */
    private fun serveFakeApp(url: Uri): WebResourceResponse? {
        if (ControlCenterApp.originOf(url.toString()) != APP_ORIGIN) return null
        return when (url.path) {
            "/index.html", "/wrong-revision.html" -> WebResourceResponse(
                "text/html",
                "utf-8",
                InstrumentationRegistry.getInstrumentation().context.assets.open("control-center-test/app.html")
            )
            "/report" -> {
                reports += url.getQueryParameter("step").orEmpty() to url.getQueryParameter("detail").orEmpty()
                WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArray(0).inputStream())
            }
            else -> null
        }
    }

    private fun show(path: String) {
        val app = ControlCenterApp(url = APP_ORIGIN + path, revision = REVISION)
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterWebView(
                    app,
                    snapshot,
                    actions,
                    onNavigationBlocked = {},
                    onStopped = {},
                    onUnavailable = { unavailable = true },
                    // The real listener, with each message from our page recorded on its way in.
                    addMessageListener = { webView, listener ->
                        addPageListener(webView) { view, message, sourceOrigin, isMainFrame, proxy ->
                            pageOrigins += sourceOrigin.toString()
                            message.data?.let { fromPage += Json.parseToJsonElement(it).jsonObject["kind"]?.jsonPrimitive?.content.orEmpty() }
                            listener.onPostMessage(view, message, sourceOrigin, isMainFrame, proxy)
                        }
                    },
                    interceptAppRequest = ::serveFakeApp
                )
            }
        }
    }

    private fun steps() = reports.map { it.first }

    private fun detail(step: String) = reports.first { it.first == step }.second

    private fun await(what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { condition() }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for $what. App reported $reports; page sent $fromPage", e)
        }
    }

    @Test
    fun anAppIsConnectedThroughTheRealParentPageAndItsRequestAnsweredByTheChat() {
        show("/index.html")

        // 1. The app asks to connect, and the page passes that on.
        await("the app's ready") { "ready" in steps() }
        await("the page to pass ready to native") { "app-ready" in fromPage }
        assertFalse("the message channel could not be set up", unavailable)
        assertTrue("messages came from $pageOrigins", pageOrigins.all(ControlCenterPage::isPageOrigin))

        // 2. The page hands over a port scoped to this revision and load.
        await("the connect message") { "connect" in steps() }
        val connect = Json.parseToJsonElement(detail("connect")).jsonObject
        assertTrue("connect came from another window", connect["fromParent"]!!.jsonPrimitive.boolean)
        assertEquals(ControlCenterPage.PAGE_ORIGIN, connect["origin"]!!.jsonPrimitive.content)
        assertEquals(1, connect["version"]!!.jsonPrimitive.int)
        assertEquals(REVISION, connect["revision"]!!.jsonPrimitive.content)
        assertTrue(connect["epoch"]!!.jsonPrimitive.content.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")))
        assertEquals(1, connect["ports"]!!.jsonPrimitive.int)

        // 3 and 4. A send_message on that port reaches the chat.
        await("the request to reach the chat") { sent.isNotEmpty() }
        assertEquals(listOf("hi from app" to ControlCenterConversation.CURRENT), sent.toList())

        // 5. The chat's session comes back to the app.
        await("the app's answer") { "response" in steps() }
        assertEquals("""{"ok":true,"sessionId":"$SESSION"}""", detail("response"))
    }

    @Test
    fun anAppAskingForAnotherRevisionIsNeverGivenAPort() {
        show("/wrong-revision.html")

        await("the app's ready") { "ready" in steps() }
        // The app asks four times a second; give the page several chances to answer.
        Thread.sleep(2_000)

        assertFalse("the page passed on a ready for another revision", "app-ready" in fromPage)
        assertFalse("the app was connected", "connect" in steps())
        assertTrue(sent.isEmpty())
    }

    private companion object {
        const val APP_ORIGIN = "https://app.control-center.test"
        val REVISION = "sha256:" + "c".repeat(64)
        const val SESSION = "conv-42"
        const val TIMEOUT_MS = 15_000L
    }
}
