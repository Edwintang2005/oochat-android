package ai.openonion.oochat.domain.model

data class ExecRequest(
    val execId: String,
    val tool: String,
    val argsJson: String,
    val agentAddress: String
)

sealed interface ExecOutcome {

    data class Success(
        val result: String,
        val durationMs: Double? = null
    ) : ExecOutcome

    data class Error(
        val message: String,
        val durationMs: Double? = null
    ) : ExecOutcome

    data class NotSent(
        val message: String
    ) : ExecOutcome

    data class Unknown(
        val message: String
    ) : ExecOutcome
}
