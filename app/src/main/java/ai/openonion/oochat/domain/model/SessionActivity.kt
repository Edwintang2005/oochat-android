package ai.openonion.oochat.domain.model

/**
 * What the agent is doing on the session on screen, as the chat top bar says it. Only shown
 * while connected: the status dot and the connection banner already own every other state.
 */
enum class SessionActivity {
    /** A turn is running: this app sent an INPUT not yet answered, or the Host said `running`. */
    RUNNING,

    /** The run is parked on a card only the user can answer (approval, question, plan review…). */
    AWAITING_USER,

    /** Connected and idle: ready for the next message. */
    IDLE,

    /** A SESSION_STATUS reply said `not_found`: the Host holds no live session under this id. */
    NOT_FOUND;

    companion object {
        /**
         * Combine what this screen knows with what the Host last said. Local knowledge wins
         * where it is fresher: a gate on screen is waiting whatever the Host said, and a turn
         * this app is running is running. Of the Host's answers only `running` and `not_found`
         * change the line; `new`, `connected` and silence all read as idle.
         *
         * @param connected [ConnectionState.Connected]; null is returned otherwise.
         * @param turnRunning A turn is in flight here (an unanswered INPUT, or in-progress items).
         * @param awaitsUser A gate on screen is waiting for the user's answer.
         * @param host [ai.openonion.oochat.data.repository.ConnectionRepository.hostSessionStatus].
         */
        fun of(
            connected: Boolean,
            turnRunning: Boolean,
            awaitsUser: Boolean,
            host: ServerSessionStatus
        ): SessionActivity? = when {
            !connected -> null
            awaitsUser -> AWAITING_USER
            turnRunning || host == ServerSessionStatus.RUNNING -> RUNNING
            host == ServerSessionStatus.NOT_FOUND -> NOT_FOUND
            else -> IDLE
        }
    }
}
