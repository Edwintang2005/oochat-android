package ai.openonion.oochat.ui.dashboard

/**
 * Validates what a dashboard button asks for before anything is sent.
 *
 * The bridge carries an *intent*, not a command: the HTML that produced it is
 * agent-authored, so the name is checked here — outside the WebView — against
 * the skill list the agent published in its own `AGENT_PROFILE`.
 *
 * The check **fails closed**. A missing or empty allowlist (the profile frame
 * has not landed yet) runs nothing, rather than accepting whatever name the
 * page supplies during the load window. The shape check matters for the same
 * reason: the name is interpolated into a chat message, so without it a
 * hostile dashboard could smuggle newlines and arbitrary prose into a user
 * turn.
 *
 * Ported from oo-chat-web's `components/dashboard/dashboard-pane.tsx`.
 */
object DashboardSkillIntent {

    /** Skill names come from directory names: no spaces, no newlines, no separators. */
    private val SKILL_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")

    /** Args ride on the same single `/skill` line, so they are clamped too. */
    private const val MAX_ARGS_CHARS = 500

    private val WHITESPACE = Regex("\\s+")

    /**
     * The chat message this button should produce, or null when the intent is
     * refused. Callers must send the returned string through the normal chat
     * send path — a visible user turn, never an implicit execution.
     */
    fun toChatMessage(skill: String?, args: String?, allowedSkills: List<String>?): String? {
        val name = skill.orEmpty()
        if (!SKILL_NAME.matches(name)) return null
        if (allowedSkills.isNullOrEmpty() || name !in allowedSkills) return null

        // Collapse whitespace so args can't break out of the single line.
        val cleanArgs = args.orEmpty().replace(WHITESPACE, " ").trim().take(MAX_ARGS_CHARS)
        return if (cleanArgs.isEmpty()) "/$name" else "/$name $cleanArgs"
    }
}
