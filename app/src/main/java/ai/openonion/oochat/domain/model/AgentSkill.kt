package ai.openonion.oochat.domain.model

/**
 * A skill the connected agent publishes, from its `AGENT_PROFILE` frame —
 * the candidate set for the composer's `/` palette, which shows [description]
 * under the name.
 *
 * [description] is null when the agent has nothing useful to say about the
 * skill: absent, empty, or the `"No description"` placeholder the host emits
 * for a skill whose file carries no summary. All three normalize to null at
 * the protocol boundary so the UI has one case to render, not four.
 */
data class AgentSkill(
    val name: String,
    val description: String? = null
)
