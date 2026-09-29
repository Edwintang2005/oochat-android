package ai.openonion.oochat.ui.dashboard

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts one agent-authored dashboard in a locked-down [WebView].
 *
 * The document itself — CSP, bridge, and the agent's HTML as body content — is
 * built by [DashboardDocument]; read its doc first, it carries the security
 * model. This file is the Android half: the settings that make the WebView the
 * equivalent of the web client's `sandbox="allow-scripts"` iframe, and the
 * navigation refusal that keeps the page from replacing itself.
 *
 * `javaScriptEnabled = true` is required — the bridge is a script. Everything
 * else a WebView can reach is switched off.
 */
@Composable
fun DashboardWebView(
    document: String,
    onRunSkill: (skill: String, args: String) -> Unit,
    onNavigationBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The bridge is called from a WebView thread and must see the *current*
    // handler, not the one captured when the WebView was first created.
    val currentRunSkill by rememberUpdatedState(onRunSkill)
    val currentNavigationBlocked by rememberUpdatedState(onNavigationBlocked)
    val bridge = remember { SkillBridge { skill, args -> currentRunSkill(skill, args) } }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                configureForUntrustedContent()
                addJavascriptInterface(bridge, DashboardDocument.BRIDGE_NAME)
                webViewClient = BlockAllNavigationClient { currentNavigationBlocked() }
            }
        },
        update = { webView ->
            // Only on a genuinely new snapshot. `update` runs on every
            // recomposition, and reloading the same document would restart the
            // page (losing scroll and any filter the user typed) for reasons
            // that have nothing to do with the dashboard.
            if (webView.tag !== document) {
                webView.tag = document
                // A null base URL is what makes the origin opaque: no
                // same-origin peer, no file:// resolution, no app storage. It
                // is also why the CSP has to travel inside the document as a
                // meta tag — there are no response headers to put it in.
                webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

/**
 * Everything a WebView will do for a page unless told otherwise. The agent
 * wrote this page, so all of it is off — only scripting stays on, for the
 * bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForUntrustedContent() {
    settings.apply {
        // The nonced bridge is a script; without this, skill buttons are dead.
        // The agent's own scripts are refused by the CSP, not by this switch.
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        // No localStorage/sessionStorage: a dashboard holds no state of its
        // own, and storage shared across snapshots would outlive the agent it
        // belongs to.
        domStorageEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
    }
    // No WebChromeClient is set, so window.open, alert/confirm/prompt and
    // permission requests are all ignored rather than surfaced as app dialogs.
}

/**
 * The one `@JavascriptInterface` the page can reach. A single method with two
 * string parameters — every method on this object is callable by any script
 * that runs, so the object *is* the attack surface and nothing else goes on it.
 *
 * What arrives here is an untrusted intent. It is validated outside the
 * WebView (see [DashboardSkillIntent]) and then sent through the normal chat
 * path, so the worst a forged call can produce is a visible `/skill` turn.
 */
private class SkillBridge(private val onRunSkill: (String, String) -> Unit) {
    @JavascriptInterface
    fun runSkill(skill: String?, args: String?) {
        onRunSkill(skill.orEmpty(), args.orEmpty())
    }
}

/**
 * Refuses every navigation. A dashboard is one self-contained page: the bridge
 * already cancels link clicks, and this catches what it cannot see — a
 * `<meta http-equiv="refresh">`, a redirect, a form submission.
 *
 * The URL is never handed to an external browser. Opening an agent-supplied
 * link would turn a page that cannot phone home into one that can, with the
 * user's tap as the authorisation.
 */
private class BlockAllNavigationClient(private val onBlocked: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Scheme only — the rest of an agent-supplied URL has no business in
        // a log file.
        FileLogger.w(LogTags.DASHBOARD, "Blocked dashboard navigation to ${request?.url?.scheme} URL")
        onBlocked()
        return true
    }
}
