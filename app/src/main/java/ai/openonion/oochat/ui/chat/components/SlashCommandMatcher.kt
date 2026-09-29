package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill

/**
 * Completion for the composer's `/skill` palette, ported from oo-chat-web's
 * `components/chat/chat-input.tsx`.
 *
 * Deliberately free of Compose: no agent we can reach publishes a skill, so
 * the end-to-end path can't be exercised on a device and this is the part
 * that has to carry the confidence. Candidates are the agent's own published
 * skills (`AgentLiveProfile.skills`) — the same list the Home page validates
 * its buttons against. Matching is on the name alone; the description rides
 * along for the palette to show.
 */
object SlashCommandMatcher {

    /**
     * The command token currently being typed, or null when the palette should
     * be closed. A space commits the command: everything after it is arguments.
     */
    fun query(text: String): String? =
        if (text.startsWith("/") && !text.contains(" ")) text.drop(1).lowercase() else null

    /**
     * [skills] matching the command token in [text], best match first. Empty
     * when the palette is closed or nothing matches — the caller renders no
     * palette at all in that case, never an empty box.
     */
    fun candidates(skills: List<AgentSkill>, text: String): List<AgentSkill> {
        val query = query(text) ?: return emptyList()
        return skills
            .map { it to rank(it.name.lowercase(), query) }
            .filter { (_, rank) -> rank >= 0 }
            // Stable, so equally-ranked skills keep the agent's own ordering.
            .sortedBy { (_, rank) -> rank }
            .map { (skill, _) -> skill }
    }

    /**
     * What tapping [skill] puts in the field. The trailing space is load-bearing:
     * it commits the command, closing the palette and starting the arguments.
     */
    fun completion(skill: AgentSkill): String = "/${skill.name} "

    /**
     * Prefix beats substring beats in-order letters, so "/linkedeng" still
     * finds "linkedin-engagement". -1 means no match.
     */
    internal fun rank(name: String, query: String): Int {
        if (name.startsWith(query)) return 0
        if (name.contains(query)) return 1
        var matched = 0
        for (c in name) {
            if (matched < query.length && c == query[matched]) matched++
        }
        return if (matched == query.length) 2 else -1
    }
}
