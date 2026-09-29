package ai.openonion.oochat.domain.model

/**
 * Sealed class representing agent status.
 * Does NOT duplicate connection state - only tracks agent-level status.
 */
sealed class AgentStatus {
    data object Active : AgentStatus()
    data object Connecting : AgentStatus()
    data object Connected : AgentStatus()
    data class Error(val message: String) : AgentStatus()
    data object Disabled : AgentStatus()

    fun isAvailable(): Boolean = this is Active
    fun isConnected(): Boolean = this is Connected
    fun isError(): Boolean = this is Error
    fun errorMessage(): String? = when (this) {
        is Error -> message
        else -> null
    }
}
