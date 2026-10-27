package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The WebView's lifecycle and client, on Robolectric's WebView, which runs no page. The page and
 * the app's handshake run in the instrumented ControlCenterBridgeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlCenterWebViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64))

    private val snapshot = ControlCenterSnapshot(
        sessionId = "conv-1",
        agentAddress = "0xagent",
        status = ControlCenterAgentStatus.IDLE,
        connectionState = ControlCenterConnection.CONNECTED,
        skills = emptyList()
    )

    private val actions = object : ControlCenterActions {
        override suspend fun sendMessage(message: String, conversation: ControlCenterConversation) = error("not used")
        override suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation) =
            error("not used")
    }

    private var blocked = 0
    private var stopped = 0
    private var unavailable = 0

    /** Robolectric's WebView cannot register a message listener, so tests choose the outcome. */
    private val listens: (WebView, WebViewCompat.WebMessageListener) -> Unit = { _, _ -> }

    private fun webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        fun walk(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(composeRule.activity.window.decorView)
        return found
    }

    private fun webView() = webViews().single()

    private fun client() = shadowOf(webView()).webViewClient

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private fun showWebView(app: () -> ControlCenterApp = { this.app }, snapshot: () -> ControlCenterSnapshot = { this.snapshot }) {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterWebView(app(), snapshot(), actions, onNavigationBlocked = { blocked++ }, onStopped = { stopped++ }, onUnavailable = { unavailable++ }, addMessageListener = listens)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `our page is loaded once per app, not again when the screen recomposes`() {
        var current by mutableStateOf(app)
        var latest by mutableStateOf(snapshot)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        composeRule.setContent {
            ConnectOnionTheme(themeMode = theme) {
                ControlCenterWebView(current, latest, actions, onNavigationBlocked = {}, onStopped = {}, onUnavailable = {}, addMessageListener = listens)
            }
        }
        composeRule.waitForIdle()
        val view = webView()
        assertEquals(ControlCenterPage.PAGE_URL, shadowOf(view).lastLoadedUrl)
        val lightBackground = shadowOf(view).backgroundColor
        assertNotEquals("painted white until host.html loads", 0, lightBackground)

        view.loadUrl("about:blank")
        latest = snapshot.copy(status = ControlCenterAgentStatus.WORKING)
        theme = ThemeMode.DARK
        composeRule.waitForIdle()
        assertEquals("a recomposition reloaded the app", "about:blank", shadowOf(view).lastLoadedUrl)
        assertNotEquals("the background did not follow the theme", lightBackground, shadowOf(view).backgroundColor)

        current = app.copy(revision = "sha256:" + "b".repeat(64))
        composeRule.waitForIdle()
        assertEquals(ControlCenterPage.PAGE_URL, shadowOf(view).lastLoadedUrl)
    }

    @Test
    fun `the WebView is destroyed with the screen`() {
        var shown by mutableStateOf(true)
        composeRule.setContent {
            if (shown) ControlCenterWebView(app, snapshot, actions, onNavigationBlocked = {}, onStopped = {}, onUnavailable = {}, addMessageListener = listens)
        }
        composeRule.waitForIdle()
        val view = webView()

        shown = false
        composeRule.waitForIdle()

        assertTrue(shadowOf(view).wasDestroyCalled())
    }

    @Test
    fun `a dead renderer is reported instead of taking O Chat down with it`() {
        showWebView()

        val handled = client().onRenderProcessGone(webView(), null)

        assertTrue(handled)
        assertEquals(1, stopped)
    }

    @Test
    fun `a main-frame load the client was not asked about is stopped and our page reloaded`() {
        showWebView()
        val view = webView()
        view.loadUrl("about:blank")

        client().onPageStarted(view, "https://phish.example/login", null)
        ShadowLooper.idleMainLooper()

        assertEquals(1, blocked)
        assertEquals(ControlCenterPage.PAGE_URL, shadowOf(view).lastLoadedUrl)

        client().onPageStarted(view, ControlCenterPage.PAGE_URL, null)
        assertEquals("our own page was refused", 1, blocked)
    }

    @Test
    fun `our origin serves the parent page and host_js, and nothing else reaches the network`() {
        showWebView()
        val client = client()
        val base = ControlCenterPage.PAGE_ORIGIN + ControlCenterPage.PAGE_PATH

        val page = client.shouldInterceptRequest(webView(), request(base + "host.html"))!!
        assertTrue(page.data.bufferedReader().readText().contains("frame-src https://r-1.apps.example.net;"))
        assertEquals("text/javascript", client.shouldInterceptRequest(webView(), request(base + "host.js"))!!.mimeType)
        assertEquals(404, client.shouldInterceptRequest(webView(), request(base + "other.html"))!!.statusCode)
        assertEquals(404, client.shouldInterceptRequest(webView(), request(ControlCenterPage.PAGE_ORIGIN + "/x"))!!.statusCode)
        assertNull("the app's own requests are its own", client.shouldInterceptRequest(webView(), request(app.url)))
    }

    @Test
    fun `a test's app server is asked only for addresses outside our origin`() {
        val asked = mutableListOf<String>()
        composeRule.setContent {
            ControlCenterWebView(
                app,
                snapshot,
                actions,
                onNavigationBlocked = {},
                onStopped = {},
                onUnavailable = {},
                addMessageListener = listens,
                interceptAppRequest = { url ->
                    asked += url.toString()
                    WebResourceResponse("text/html", "utf-8", "fake app".byteInputStream())
                }
            )
        }
        composeRule.waitForIdle()
        val client = client()
        val base = ControlCenterPage.PAGE_ORIGIN + ControlCenterPage.PAGE_PATH

        assertEquals("fake app", client.shouldInterceptRequest(webView(), request(app.url))!!.data.bufferedReader().readText())
        assertTrue(client.shouldInterceptRequest(webView(), request(base + "host.html"))!!.data.bufferedReader().readText().contains("frame-src"))
        assertEquals(404, client.shouldInterceptRequest(webView(), request(base + "other.html"))!!.statusCode)
        assertEquals("it could stand in for our own page", listOf(app.url), asked)
    }

    @Test
    fun `the app is paused while O Chat is in the background`() {
        showWebView()
        val view = webView()
        assertFalse(shadowOf(view).wasOnPauseCalled())

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(shadowOf(view).wasOnPauseCalled())

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(shadowOf(view).wasOnResumeCalled())
    }

    @Test
    fun `a stopped app says so, and Reload starts a fresh WebView`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterScreen(app, snapshot, actions, onNavigateBack = {}, supported = true, addMessageListener = listens)
            }
        }
        composeRule.waitForIdle()
        val crashed = webView()

        shadowOf(crashed).webViewClient.onRenderProcessGone(crashed, null)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Control Center stopped").assertExists()
        assertTrue(shadowOf(crashed).wasDestroyCalled())
        assertTrue(webViews().isEmpty())

        composeRule.onNodeWithText("Reload").performClick()
        composeRule.waitForIdle()

        assertNotSame(crashed, webView())
        assertEquals(ControlCenterPage.PAGE_URL, shadowOf(webView()).lastLoadedUrl)
    }

    @Test
    fun `a message channel that cannot be set up says so instead of leaving a blank page`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterScreen(
                    app,
                    snapshot,
                    actions,
                    onNavigateBack = {},
                    supported = true,
                    addMessageListener = { _, _ -> throw UnsupportedOperationException("no listener") }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Control Center unavailable").assertExists()
        assertTrue(webViews().isEmpty())
    }
}
