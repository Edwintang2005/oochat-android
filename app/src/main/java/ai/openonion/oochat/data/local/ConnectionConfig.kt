package ai.openonion.oochat.data.local

import ai.openonion.oochat.Constants
import kotlinx.serialization.Serializable

/**
 * Single-server config, kept separate from the newer multi-agent AgentRepository/Room model and not synced with it — e.g. AgentListScreenWrapper writes a snapshot here just so LoadingScreen's auto-reconnect can read it. Unifying is a bigger job, left as-is.
 */
@Serializable
data class ConnectionConfig(
    val serverUrl: String,
    val apiKey: String? = null,
    val agentAddress: String? = null,
    val lastConnected: Long? = null,
    val connectionTimeout: Long = 30_000L // 30 seconds default
) {
    val displayUrl: String
        get() = serverUrl
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")

    val shortDisplayUrl: String
        get() {
            val url = displayUrl
            return if (url.length > 30) {
                "${url.take(15)}...${url.takeLast(12)}"
            } else {
                url
            }
        }

    val isValid: Boolean
        get() = serverUrl.isNotBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://oo.openonion.ai"
        const val DEFAULT_TIMEOUT = 30_000L

        fun default(): ConnectionConfig {
            return ConnectionConfig(
                serverUrl = DEFAULT_RELAY_URL,
                connectionTimeout = DEFAULT_TIMEOUT
            )
        }
    }
}

/**
 * Resolved connect parameters derived from a [ConnectionConfig]: which agent
 * address to dial and — only for a custom (non-default-relay) endpoint — the
 * direct URL to reach it at.
 *
 * @property agentAddress the address the WebSocket should open against.
 * @property directUrl the direct server URL, or `null` when the saved
 *   `serverUrl` is one of the known default relays (see [isDirect]).
 * @property isDirect whether the saved `serverUrl` is a custom, non-default
 *   endpoint (and therefore forwarded as [directUrl]).
 */
data class ConnectTarget(
    val agentAddress: String,
    val directUrl: String?,
    val isDirect: Boolean
)

/**
 * Derive the [ConnectTarget] for this config.
 *
 * `serverUrl` is the relay/direct *server* URL, not the agent address, so
 * passing it as the agent address would open the WebSocket at the wrong path.
 * It is therefore only forwarded as `directUrl` when it is NOT one of the
 * known default relays; the saved [ConnectionConfig.agentAddress] (falling
 * back to `serverUrl` when blank/absent) is what actually gets dialled.
 */
fun ConnectionConfig.toConnectTarget(): ConnectTarget {
    val isDirect = !Constants.isDefaultServerUrl(serverUrl)
    return ConnectTarget(
        agentAddress = agentAddress?.takeIf { it.isNotBlank() } ?: serverUrl,
        directUrl = if (isDirect) serverUrl else null,
        isDirect = isDirect
    )
}
