package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ExecOutcome

/**
 * EXEC command displayed by the UI.
 *
 * A null outcome means the command was sent and is still waiting for its
 * matching EXEC_RESULT. Completed commands retain their execId so concurrent
 * commands can finish in any order without updating the wrong card.
 */
data class ExecUiItem(
    val execId: String,
    val tool: String,
    val outcome: ExecOutcome? = null
) {
    val isRunning: Boolean
        get() = outcome == null
}
