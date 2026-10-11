package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.Constants
import ai.openonion.oochat.network.AgentDiscoveryService
import java.net.URI
import java.net.URISyntaxException

/** Where a scanned agent QR code says to connect. */
internal data class ScannedAgent(val serverUrl: String, val agentAddress: String)

/**
 * Reads what Setup's QR scanner found. Four forms name an agent:
 *
 * - A web chat link, `https://chat.openonion.ai/<address>` — what the
 *   website's Share button encodes (openonion/oo-chat `qr-share.tsx`).
 *   The site is a web page, not an agent server, so the agent is reached
 *   through the default relay, the way the website reaches it.
 * - The app's own deep link, `connectonion://agent/<address>`. Also relayed.
 * - `<serverUrl>/<address>`, for an agent on a server of its own.
 * - The address on its own, `0x…`: all a QR code needs to hold. Relayed.
 *
 * Reading the first form as the third saved the website as a direct server,
 * which has no WebSocket, so the agent never connected (W16AAPPLE-76).
 */
internal object AgentQrCode {

    /** Hosts whose `/<address>` pages are a chat with that agent. */
    private val WEB_CHAT_HOSTS = setOf("chat.openonion.ai", "oo-chat.com")

    private const val DEEP_LINK_PREFIX = "connectonion://agent/"

    /** The agent [contents] names, or null if it is not an agent QR code. */
    fun parse(contents: String): ScannedAgent? {
        val text = contents.trim()
        if (text.startsWith(DEEP_LINK_PREFIX, ignoreCase = true)) {
            return relayed(text.substring(DEEP_LINK_PREFIX.length).substringBefore('/'))
        }
        relayed(text)?.let { return it }
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            null
        }
        if (uri != null && uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host?.lowercase() in WEB_CHAT_HOSTS
        ) {
            // The first segment is the agent; a conversation link adds its
            // session after it, which the app cannot join (another identity's
            // session), so it is dropped. Other pages (/explore) name no agent.
            val firstSegment = uri.path.orEmpty().split('/').firstOrNull { it.isNotEmpty() }
            return firstSegment?.let(::relayed)
        }
        return serverAndAddress(text)
    }

    private fun relayed(address: String): ScannedAgent? =
        address.takeIf { AgentDiscoveryService.ADDRESS_REGEX.matches(it) }
            ?.let { ScannedAgent(serverUrl = Constants.DEFAULT_SERVER_URL, agentAddress = it) }

    /**
     * `<serverUrl>/<address>`, split on the last "/" since the URL itself
     * contains "//". A trailing slash is ignored rather than leaving the
     * address empty, and a bare `https://host` is not split into a server of
     * "https:/" and an address of "host". What follows the last "/" must be a
     * valid address: anything else is a page of some other site, not an agent.
     */
    private fun serverAndAddress(text: String): ScannedAgent? {
        val trimmed = text.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        val hostStart = trimmed.indexOf("://").let { if (it < 0) 0 else it + "://".length }
        if (lastSlash <= hostStart || lastSlash == trimmed.length - 1) return null
        val address = trimmed.substring(lastSlash + 1)
        if (!AgentDiscoveryService.ADDRESS_REGEX.matches(address)) return null
        return ScannedAgent(serverUrl = trimmed.substring(0, lastSlash), agentAddress = address)
    }
}
