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

    /**
     * The wording for a signature the Host refused as expired. A correct client
     * only gets that refusal when this device's clock is more than five minutes
     * from the Host's and the server's `Date` could not correct it (no header,
     * or past the 30-minute bound; see network/HostClock), so the clock is the
     * thing to fix.
     */
    const val CLOCK_WRONG =
        "The agent refused this because this device's clock is wrong. " +
            "Check the date and time in Settings (set them to automatic), then reconnect."

    fun humanize(raw: String): String {
        // Prefix, not equality: the relay appends the address it could not
        // reach ("Agent not connected: 0xc31dd64…"), which is a log detail.
        if (isAgentOffline(raw)) return AGENT_OFFLINE
        return when (raw.trim().lowercase()) {
            // ws_router/agent_io.py rejects any INPUT whose prompt is empty,
            // attachments or not — so this is what an attachment-only send hits.
            "prompt required" -> "Add a message to send with your attachment."
            // auth.py `_authenticate_signed`: the timestamp is more than 300 s
            // from the Host's clock, on a CONNECT, a command or ONBOARD_SUBMIT
            // (W16AAPPLE-90).
            "unauthorized: signature expired" -> CLOCK_WRONG
            // ws_router/mode.py and session/mode.py: the Host's own answers to
            // a mode_change it could not commit (1.7+, host-managed modes).
            "session is busy" -> MODE_BUSY
            "permission mode is not available" -> "This agent doesn't offer that mode."
            "mode change is unavailable" -> "This agent doesn't let you change mode."
            "unable to change session mode" -> "The agent couldn't change mode. Try again."
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
    /** The Host refuses a mode change while a turn is running; it can be tried again once idle. */
    const val MODE_BUSY = "The agent is working. Change mode when this turn finishes."

    /**
     * The Host refusing a `mode_change`, in its raw wording. Exact messages rather than the
     * JSON-RPC codes the refusals carry (-32000, -32602), which other commands reuse. A legacy
     * Host's plugin path names the frame itself ("mode_change") instead.
     */
    fun isModeRefusal(raw: String): Boolean {
        val m = raw.trim().lowercase()
        return "mode_change" in m || m in MODE_REFUSALS
    }

    private val MODE_REFUSALS = setOf(
        "session is busy",
        "permission mode is not available",
        "mode change is unavailable",
        "unable to change session mode"
    )

    fun isOfflineVerdict(text: String): Boolean =
        isAgentOffline(text) || text.trim() == AGENT_OFFLINE

    /**
     * The agent refused this app's OIP version (ERROR -32010). Names both
     * versions when the Host said which it speaks, since that is what tells
     * the user which side needs the update.
     *
     * @param hostProtocol the Host's version as "oip/0.2", or null if unknown.
     * @param clientProtocol this app's version, same form. Passed in rather
     *   than read from the wire layer, which domain/ must not import.
     */
    fun incompatibleProtocol(hostProtocol: String?, clientProtocol: String): String {
        val mismatch = if (hostProtocol != null) {
            "This agent uses a different version of the chat protocol ($hostProtocol) than this app ($clientProtocol)."
        } else {
            "This agent doesn't support this app's version of the chat protocol ($clientProtocol)."
        }
        return "$mismatch Update the app or the agent, then reconnect."
    }
}
