package ai.openonion.oochat.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One row of the agent's current Todo List — the OIP `plan` frame's
 * `entries[]`, and the session snapshot's `plan` array.
 *
 * Session state, not transcript: every `plan` frame replaces the whole list,
 * an empty one clears it, and it carries no id of its own. It is therefore
 * held next to the chat list ([ai.openonion.oochat.data.repository.ConnectionRepository.plan])
 * rather than inside it, the way the reference web client shows it as a panel
 * above the transcript (oo-chat's current-plan-panel.tsx).
 *
 * Observational only: nothing in it grants the agent anything.
 */
data class PlanEntry(
    val content: String,
    val priority: PlanPriority,
    val status: PlanStatus
)

/** Wire values `high` | `medium` | `low` — see agent.py's `_normalized_plan`. */
enum class PlanPriority(val wire: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    companion object {
        fun fromWire(value: String?): PlanPriority? = entries.firstOrNull { it.wire == value }
    }
}

/** Wire values `pending` | `in_progress` | `completed` — see agent.py's `_normalized_plan`. */
enum class PlanStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    companion object {
        fun fromWire(value: String?): PlanStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** The plan of an agent that has none — the default for anything not wired to a connection. */
val NO_PLAN: StateFlow<List<PlanEntry>> = MutableStateFlow<List<PlanEntry>>(emptyList()).asStateFlow()
