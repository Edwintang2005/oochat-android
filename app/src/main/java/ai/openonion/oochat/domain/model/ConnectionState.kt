package ai.openonion.oochat.domain.model

/**
 * Sealed class representing all possible connection states.
 *
 * State transitions:
 * - Idle → Connecting (first connection attempt)
 * - Disconnected → Connecting
 * - Connecting → Connected
 * - Connecting → Error
 * - Connected → Disconnected
 * - Connected → Error
 * - Error → Disconnected
 * - Error → Connecting (retry)
 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Reconnecting : ConnectionState()

    data class Connected(
        val address: String,
        val session: SessionSnapshot? = null
    ) : ConnectionState()

    data class Error(
        val message: String,
        val technicalDetails: String? = null,
        val cause: Throwable? = null
    ) : ConnectionState() {
        companion object {
            fun fromException(e: Throwable): Error {
                return Error(
                    message = e.message ?: "Unknown error",
                    technicalDetails = e.stackTraceToString(),
                    cause = e
                )
            }

            fun withMessage(message: String): Error {
                return Error(message = message)
            }
        }
    }

    fun isIdle(): Boolean = this is Idle
    fun isConnected(): Boolean = this is Connected
    fun isConnecting(): Boolean = this is Connecting || this is Reconnecting
    fun isError(): Boolean = this is Error
    fun isDisconnected(): Boolean = this is Disconnected

    /**
     * True whenever there's a live socket to send on — not just [Connected].
     * [Connecting] counts too: an ONBOARD_REQUIRED gate never gets the
     * CONNECTED frame that would flip [isConnected] (the server doesn't
     * send one until onboarding clears), yet the socket is fully live and
     * ready to receive ONBOARD_SUBMIT the whole time the gate is showing.
     */
    fun hasLiveConnection(): Boolean = !isDisconnected() && !isError() && !isIdle()

    fun addressOrNull(): String? = when (this) {
        is Connected -> address
        else -> null
    }

    fun errorMessageOrNull(): String? = when (this) {
        is Error -> message
        else -> null
    }
}
