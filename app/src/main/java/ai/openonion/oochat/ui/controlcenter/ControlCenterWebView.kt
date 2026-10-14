package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.dashboard.toCssHex
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Whether this device's WebView can host the app; without it, the legacy Home page is used. */
fun controlCenterSupported(): Boolean =
    runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

/**
 * Hosts the agent's Control Center app: our parent page ([ControlCenterPage.hostDocument] and
 * `assets/control-center/host.js`) at an https origin, with the app in its iframe.
 *
 * Unlike the legacy dashboard, the app is reviewed code that needs scripts, storage and network
 * of its own origin. What it cannot do: reach native code (the message channel is injected only
 * into our page's origin), leave its origin (our page's CSP frames only the app's origin, and its
 * sandbox stops top navigation), open windows, or use device features (the iframe is given none,
 * and no WebChromeClient is set, so permission requests are refused).
 *
 * @param onStopped The WebView's renderer died; this WebView is unusable and must leave composition.
 * @param onUnavailable The message channel could not be set up, so the app could never connect.
 */
@Composable
fun ControlCenterWebView(
    app: ControlCenterApp,
    snapshot: ControlCenterSnapshot,
    actions: ControlCenterActions,
    onNavigationBlocked: () -> Unit,
    onStopped: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    addMessageListener: (WebView, WebViewCompat.WebMessageListener) -> Unit = ::addPageListener
) {
    val scope = rememberCoroutineScope()
    val surface = MaterialTheme.colorScheme.surface
    val background by rememberUpdatedState(surface.toCssHex())
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentBlocked by rememberUpdatedState(onNavigationBlocked)
    val currentStopped by rememberUpdatedState(onStopped)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val view = remember { ViewRefs() }
    val page = remember(app) {
        ControlCenterPage(
            app,
            { currentSnapshot },
            actions,
            scope,
            toPage = { view.proxy?.postMessage(it) },
            onBlocked = { currentBlocked() }
        )
    }
    val currentPage by rememberUpdatedState(page)

    DisposableEffect(page) { onDispose { page.dispose() } }
    LaunchedEffect(page, snapshot) { page.publish(snapshot) }
    PausedInBackground(view)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                view.webView = this
                configureForApp()
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler(ControlCenterPage.PAGE_PATH, PageHandler(context.assets) { currentPage to background })
                    .build()
                webViewClient = AppClient(
                    loader,
                    page = { currentPage },
                    onBlocked = { currentBlocked() },
                    onRendererGone = {
                        view.proxy = null
                        currentPage.dispose()
                        currentStopped()
                    }
                )
                runCatching {
                    addMessageListener(this) { _, message, sourceOrigin, isMainFrame, proxy ->
                        if (isMainFrame && ControlCenterPage.isPageOrigin(sourceOrigin.toString())) {
                            view.proxy = proxy
                            message.data?.let { currentPage.onPageMessage(it) }
                        }
                    }
                }.onFailure {
                    FileLogger.e(LogTags.CONTROL_CENTER, "Message listener unavailable: ${it.message}")
                    currentUnavailable()
                }
            }
        },
        update = { webView ->
            // Painted before host.html loads, so a dark theme does not flash white.
            webView.setBackgroundColor(surface.toArgb())
            // Only for a new app; reloading on every recomposition would restart it.
            if (webView.tag !== page) {
                webView.tag = page
                webView.loadUrl(ControlCenterPage.PAGE_URL)
            }
        },
        onRelease = { webView ->
            view.proxy = null
            view.webView = null
            view.resumeTimers(webView)
            webView.destroy()
        }
    )
}

/** Injects [ControlCenterPage.NATIVE_OBJECT] into our page's origin only. */
internal fun addPageListener(webView: WebView, listener: WebViewCompat.WebMessageListener) =
    WebViewCompat.addWebMessageListener(webView, ControlCenterPage.NATIVE_OBJECT, setOf(ControlCenterPage.PAGE_ORIGIN), listener)

private class ViewRefs {
    var webView: WebView? = null
    var proxy: JavaScriptReplyProxy? = null
    var timersPaused = false

    /** pauseTimers is process-wide, so it must not outlive this screen. */
    fun resumeTimers(webView: WebView) {
        if (timersPaused) {
            timersPaused = false
            webView.resumeTimers()
        }
    }
}

/** Stops the app's scripts and timers while O Chat is in the background, as a browser tab would. */
@Composable
private fun PausedInBackground(view: ViewRefs) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val webView = view.webView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!view.timersPaused) {
                    view.timersPaused = true
                    webView.onPause()
                    webView.pauseTimers()
                }
                Lifecycle.Event.ON_START -> if (view.timersPaused) {
                    webView.onResume()
                    view.resumeTimers(webView)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.webView?.let(view::resumeTimers)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForApp() {
    settings.apply {
        javaScriptEnabled = true
        // The app has storage of its own origin, as it does in the web O Chat.
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
    }
}

/** Serves the parent page (built for this app's origin) and host.js. */
private class PageHandler(
    private val assets: AssetManager,
    private val current: () -> Pair<ControlCenterPage, String>
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = when (path) {
        "host.html" -> {
            val (page, background) = current()
            page.appOrigin?.let { origin ->
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ControlCenterPage.hostDocument(origin, background).byteInputStream()
                )
            }
        }
        "host.js" -> WebResourceResponse("text/javascript", "utf-8", assets.open("control-center/host.js"))
        else -> null
    }
}

/** Applies [ControlCenterPage.allowsNavigation]. Nothing is handed to an external browser. */
private class AppClient(
    private val loader: WebViewAssetLoader,
    private val page: () -> ControlCenterPage,
    private val onBlocked: () -> Unit,
    private val onRendererGone: () -> Unit
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        // Our origin never reaches the network, even for a path nothing serves.
        return loader.shouldInterceptRequest(url)
            ?: notFound().takeIf { ControlCenterPage.isPageOrigin(url.toString()) }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return true
        val allowed = page().allowsNavigation(url.toString(), request.isForMainFrame)
        if (!allowed) blocked(url.scheme)
        return !allowed
    }

    /** Catches main-frame loads WebView does not ask about above, such as a form POST. */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (view == null || url == null || page().allowsNavigation(url, mainFrame = true)) return
        view.stopLoading()
        blocked(Uri.parse(url).scheme)
        view.post { view.loadUrl(ControlCenterPage.PAGE_URL) }
    }

    /** Without this, a renderer killed for memory would take O Chat down with it. */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        FileLogger.w(LogTags.CONTROL_CENTER, "Control Center renderer gone (crashed=${detail?.didCrash()})")
        onRendererGone()
        return true
    }

    private fun blocked(scheme: String?) {
        FileLogger.w(LogTags.CONTROL_CENTER, "Blocked Control Center navigation to $scheme URL")
        onBlocked()
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArray(0).inputStream())
}
