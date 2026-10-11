package ai.openonion.oochat.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Host says about a session: the `status` of its CONNECTED reply (websocket-protocol.md,
 * "Server response based on state"), or of a SESSION_STATUS reply, which says [NOT_FOUND] where
 * CONNECTED would say [NEW].
 */
enum class ServerSessionStatus {
    /** The Host has no record of the session: the id was unknown, expired, or belongs to another caller. */
    NEW,

    /** The session exists and is idle: alive in the Host's registry, or restored from its disk after a restart. */
    CONNECTED,

    /** The agent is mid-turn on this session; the Host replays what was missed right after CONNECTED. */
    RUNNING,

    /** A SESSION_STATUS reply's "not_found": the Host has no live session under the id, or it is another caller's. */
    NOT_FOUND,

    /** No status, or one this client does not know: an older Host sends none, a session-index socket says "index". */
    UNKNOWN;

    companion object {
        fun fromWire(status: String?): ServerSessionStatus = when (status) {
            "new" -> NEW
            "connected" -> CONNECTED
            "running" -> RUNNING
            "not_found" -> NOT_FOUND
            else -> UNKNOWN
        }
    }
}

/** A connection the Host has said nothing about. Shared default for fakes. */
val UNKNOWN_SESSION_STATUS: StateFlow<ServerSessionStatus> =
    MutableStateFlow(ServerSessionStatus.UNKNOWN).asStateFlow()
