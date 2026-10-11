package ai.openonion.oochat.domain.model

/**
 * What the Host says about the session a CONNECT asked for: the `status` of its CONNECTED reply
 * (websocket-protocol.md, "Server response based on state").
 */
enum class ServerSessionStatus {
    /** The Host has no record of the session: the id was unknown, expired, or belongs to another caller. */
    NEW,

    /** The session exists and is idle: alive in the Host's registry, or restored from its disk after a restart. */
    CONNECTED,

    /** The agent is mid-turn on this session; the Host replays what was missed right after CONNECTED. */
    RUNNING,

    /** No status, or one this client does not know: an older Host sends none, a session-index socket says "index". */
    UNKNOWN;

    companion object {
        fun fromWire(status: String?): ServerSessionStatus = when (status) {
            "new" -> NEW
            "connected" -> CONNECTED
            "running" -> RUNNING
            else -> UNKNOWN
        }
    }
}
