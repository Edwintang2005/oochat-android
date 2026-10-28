package ai.openonion.oochat.ui.dashboard

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWebView

/** Verifies the Android half of the untrusted dashboard sandbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardWebViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var blocked = 0

    private fun showWebView(document: String = "<h1>Dashboard</h1>") {
        composeRule.setContent {
            DashboardWebView(
                document = document,
                onRunSkill = { _, _ -> },
                onNavigationBlocked = { blocked++ }
            )
        }
        composeRule.waitForIdle()
    }

    private fun webView(): WebView {
        val found = mutableListOf<WebView>()
        fun walk(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(composeRule.activity.window.decorView)
        return found.single()
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `untrusted content cannot use WebView file content storage or window capabilities`() {
        showWebView()

        val view = webView()
        val settings = view.settings
        assertTrue("the nonced bridge requires JavaScript", settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.supportMultipleWindows())
        assertNull("permission and dialog requests must not be surfaced", shadowOf(view).webChromeClient)
    }

    @Test
    fun `the snapshot is loaded with an opaque origin`() {
        val document = "<h1>Dashboard</h1>"
        showWebView(document)

        val load = shadowOf(webView()).lastLoadDataWithBaseURL
        assertNotNull(load)
        assertNull(load.baseUrl)
        assertEquals(document, load.data)
        assertEquals("text/html", load.mimeType)
        assertEquals("utf-8", load.encoding)
        assertNull(load.historyUrl)
    }

    @Test
    fun `only the defined skill bridge is exposed to JavaScript`() {
        showWebView()

        val shadow = shadowOf(webView())
        val interfaces = registeredInterfaces(shadow)
        assertEquals(setOf(DashboardDocument.BRIDGE_NAME), interfaces.keys)

        val bridge = interfaces.getValue(DashboardDocument.BRIDGE_NAME)
        val callable = bridge.javaClass.declaredMethods.filter {
            it.isAnnotationPresent(JavascriptInterface::class.java)
        }
        assertEquals(listOf("runSkill"), callable.map { it.name })
        assertEquals(listOf(String::class.java, String::class.java), callable.single().parameterTypes.toList())
    }

    @Test
    fun `every attempted navigation is refused and reported`() {
        showWebView()

        val view = webView()
        val client = shadowOf(view).webViewClient
        val destinations = listOf(
            "https://example.test/collect",
            "file:///data/local/tmp/secret",
            "content://ai.openonion.oochat/secret",
            "intent://dashboard#Intent;scheme=https;end"
        )

        destinations.forEach { destination ->
            assertTrue(destination, client.shouldOverrideUrlLoading(view, request(destination)))
        }
        assertEquals(destinations.size, blocked)
    }

    /** Robolectric records every addJavascriptInterface call but exposes lookups only by name. */
    @Suppress("UNCHECKED_CAST")
    private fun registeredInterfaces(shadow: ShadowWebView): Map<String, Any> {
        val field = ShadowWebView::class.java.getDeclaredField("javascriptInterfaces")
        field.isAccessible = true
        return field.get(shadow) as Map<String, Any>
    }
}
