package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.util.stringField
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Kotlin side of the Control Center parent page (`assets/control-center/host.js`). The page
 * loads the app in an iframe and relays its MessagePort here; this answers the handshake and
 * keeps one [ControlCenterHost] per app document.
 *
 * Page to native: `{"kind":"page-ready"}`, `{"kind":"app-ready"}`, `{"kind":"message","data":<JSON text>}`,
 * `{"kind":"blocked"}` (the page's CSP stopped the app's frame leaving the app's origin).
 * Native to page: `{"kind":"load","url","origin","revision"}`, `{"kind":"connect","epoch"}`,
 * `{"kind":"message","data":<JSON text>}`, `{"kind":"close"}`.
 *
 * Not thread-safe: the WebView delivers messages on the main thread.
 */
class ControlCenterPage(
    private val app: ControlCenterApp,
    private val snapshot: () -> ControlCenterSnapshot,
    private val actions: ControlCenterActions,
    private val scope: CoroutineScope,
    private val toPage: (String) -> Unit,
    private val onBlocked: () -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val newEpoch: () -> String = ControlCenterHost::newEpoch
) {
    /**
     * The app's origin as a browser writes it, or null when [app]'s address has none we can pin.
     * Never our own origin: a sandbox with scripts and same-origin holds only across origins.
     */
    val appOrigin: String? = appOriginOf(app.url)

    private var host: ControlCenterHost? = null
    private var connectedAtMs = 0L
    private var reloadedAfterBlock = false

    fun onPageMessage(text: String) {
        if (appOrigin == null || text.length > MAX_PAGE_MESSAGE_CHARS) return
        val message = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when (message.stringField("kind")) {
            "page-ready" -> load()
            "app-ready" -> connect()
            "message" -> message.stringField("data")?.let { host?.onMessage(it) }
            "blocked" -> blocked()
        }
    }

    /**
     * The main frame may show only our page, and a subframe only the app's origin. WebView asks
     * about subframes only for non-http(s) schemes; the page's CSP covers the rest.
     */
    fun allowsNavigation(url: String, mainFrame: Boolean): Boolean =
        if (mainFrame) url == PAGE_URL else appOrigin != null && ControlCenterApp.originOf(url) == appOrigin

    fun publish(next: ControlCenterSnapshot) {
        host?.publish(next)
    }

    fun dispose() {
        host?.dispose()
        host = null
    }

    private fun load() {
        dispose()
        send {
            put("kind", "load")
            put("url", app.url)
            put("origin", appOrigin)
            put("revision", app.revision)
        }
    }

    /** Reloads the app once, so one stray link does not leave an error page; a loop is not retried. */
    private fun blocked() {
        onBlocked()
        if (!reloadedAfterBlock) {
            reloadedAfterBlock = true
            load()
        }
    }

    private fun connect() {
        // The app repeats `ready` every second until connected, so one soon after a connect is
        // a repeat, not a reloaded app. A reloaded app keeps asking and is connected afresh.
        if (host != null && nowMs() - connectedAtMs < READY_REPEAT_MS) return
        dispose()
        val epoch = newEpoch()
        send {
            put("kind", "connect")
            put("epoch", epoch)
        }
        connectedAtMs = nowMs()
        val port = object : ControlCenterPort {
            override fun post(message: JsonObject) = send {
                put("kind", "message")
                put("data", message.toString())
            }

            override fun close() = send { put("kind", "close") }
        }
        host = ControlCenterHost(app.revision, epoch, snapshot(), port, actions, scope)
    }

    private fun send(fields: JsonObjectBuilder.() -> Unit) {
        toPage(buildJsonObject(fields).toString())
    }

    companion object {
        /** WebViewAssetLoader's default domain: an https origin that never reaches the network. */
        const val PAGE_ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE_PATH = "/control-center/"
        const val PAGE_URL = "$PAGE_ORIGIN${PAGE_PATH}host.html"

        /** The object host.js talks to native through, injected only into [PAGE_ORIGIN]. */
        const val NATIVE_OBJECT = "ochatNative"

        const val READY_REPEAT_MS = 3_000L

        /** A relayed app message is at most 2 MiB of JSON, which escaping can at most double. */
        private const val MAX_PAGE_MESSAGE_CHARS = 2 * ControlCenterHost.MAX_FRAME_BYTES + 1024

        fun appOriginOf(url: String): String? = ControlCenterApp.originOf(url)?.takeIf { it != PAGE_ORIGIN }

        /** Compared by origin, not text, so a trailing slash from the WebView cannot drop messages. */
        fun isPageOrigin(origin: String): Boolean = ControlCenterApp.originOf(origin) == PAGE_ORIGIN

        /**
         * The parent page. Its CSP names the app's origin as the only thing it may frame, so the
         * app can be shown but cannot navigate its frame anywhere else. The sandbox keeps the
         * app's own origin, scripts and forms, but not top navigation, popups or downloads.
         */
        fun hostDocument(appOrigin: String, background: String): String {
            require(ControlCenterApp.isOrigin(appOrigin)) { "Not an app origin" }
            require(Regex("^#[0-9a-f]{6}$").matches(background)) { "Not a colour" }
            return """
                |<!doctype html>
                |<html>
                |<head>
                |<meta charset="utf-8">
                |<meta name="viewport" content="width=device-width, initial-scale=1">
                |<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; frame-src $appOrigin; base-uri 'none'; form-action 'none'">
                |<style>html, body { margin: 0; height: 100%; background: $background; } iframe { border: 0; width: 100%; height: 100%; display: block; }</style>
                |<script src="host.js" defer></script>
                |</head>
                |<body><iframe id="app" title="Control Center" referrerpolicy="no-referrer" sandbox="allow-scripts allow-same-origin allow-forms"></iframe></body>
                |</html>
            """.trimMargin()
        }
    }
}
