package ai.openonion.oochat.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The agent's published Control Center app, from the Host's `CONTROL_CENTER_APP` frame
 * (W16AAPPLE-29). Only ProtocolParser builds one, after checking the Host approved it.
 */
data class ControlCenterApp(
    /** Loaded as given; only the bridge's `#co-parent` and `#co-revision` are appended. */
    val url: String,
    /** `sha256:` and 64 hex characters. Every bridge message is scoped to it. */
    val revision: String,
    /** Browser features the app declared. None are granted yet. */
    val capabilities: List<String> = emptyList()
) {
    companion object {
        private val ORIGIN = Regex("^https://[a-z0-9.-]+(:[0-9]{1,5})?$")

        /**
         * `https://host[:port]`, lower-case and without the default port, as `event.origin`
         * reads; null when [url] has no origin the parent page can pin its frame to.
         */
        fun originOf(url: String): String? {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return "https://$host$port".takeIf { uri.scheme == "https" && ORIGIN.matches(it) }
        }

        fun isOrigin(text: String): Boolean = ORIGIN.matches(text)
    }
}

/** Shared default for fakes. */
val NO_CONTROL_CENTER_APP: StateFlow<ControlCenterApp?> =
    MutableStateFlow<ControlCenterApp?>(null).asStateFlow()
