package ai.openonion.oochat.domain.usecase

/**
 * Turns a server ERROR frame's raw `message` into something worth showing a
 * user. The relay speaks in lowercase developer strings ("prompt required")
 * that were never written to be read by anyone outside a log.
 *
 * Only codes actually observed on the wire are mapped; anything else falls
 * through unchanged, so a new server error stays visible rather than being
 * swallowed by a generic catch-all.
 *
 * Applied in ConnectionRepositoryImpl, upstream of
 * [StaleSessionDetector.isStaleSessionError] — never map a string containing
 * "session" without checking that detector still fires.
 */
object ServerErrorText {

    /** The one wording for the offline verdict, shared so the two forms can't drift apart. */
    const val AGENT_OFFLINE = "This agent is offline. Start it, then reconnect."

    fun humanize(raw: String): String {
        // Prefix, not equality: the relay appends the address it could not
        // reach ("Agent not connected: 0xc31dd64…"), which is a log detail.
        if (isAgentOffline(raw)) return AGENT_OFFLINE
        return when (raw.trim().lowercase()) {
            // ws_router/agent_io.py rejects any INPUT whose prompt is empty,
            // attachments or not — so this is what an attachment-only send hits.
            "prompt required" -> "Add a message to send with your attachment."
            else -> raw
        }
    }

    /**
     * The relay stating that the agent we asked for has no socket of its own.
     * Distinct from a transport failure: no amount of retrying fixes it by
     * itself — the agent has to come back and announce itself.
     */
    fun isAgentOffline(raw: String): Boolean =
        raw.trim().lowercase().startsWith("agent not connected")

    /**
     * The same verdict in either wording — the relay's raw text, or the line
     * [humanize] already replaced it with. Everything downstream of the mapper
     * (connection state, UI, the retry decision) only ever sees the second, so
     * a caller matching the raw prefix alone would silently never fire.
     */
    fun isOfflineVerdict(text: String): Boolean =
        isAgentOffline(text) || text.trim() == AGENT_OFFLINE
}
