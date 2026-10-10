package ai.openonion.oochat.domain.model

/**
 * The chat list as rendered once each turn's reasoning is folded into one
 * bubble: only a turn's newest thought keeps a row, and the thoughts before it
 * travel with it, so the transcript is not a column of "Thought" rows while
 * the agent works yet nothing it thought is lost.
 *
 * @param visible the rows to render, in order.
 * @param groups for each visible reasoning row with earlier thoughts behind
 *   it, keyed by that row's id.
 */
data class ReasoningGroups(
    val visible: List<ChatItem>,
    val groups: Map<String, ThoughtGroup>
)

/**
 * The thoughts of one turn behind its newest one.
 *
 * @param key stable for the life of the turn — the id of its first thought —
 *   so a "show all" choice survives the newest thought being replaced by an
 *   even newer one while the agent is still working.
 * @param earlier the turn's other thoughts, oldest first.
 */
data class ThoughtGroup(
    val key: String,
    val earlier: List<ChatItem.Thinking>
)

/**
 * Folds each turn's reasoning (a [ChatItem.Thinking] with
 * [ChatItem.Thinking.isReasoning]) into its newest thought. A turn is
 * everything between two of the user's own messages. Returns this same list
 * when no turn has more than one thought, which is the common case.
 */
fun List<ChatItem>.groupReasoningByTurn(): ReasoningGroups {
    val hidden = HashSet<String>()
    val groups = HashMap<String, ThoughtGroup>()
    var turnThoughts = ArrayList<ChatItem.Thinking>()

    fun closeTurn() {
        if (turnThoughts.size > 1) {
            val newest = turnThoughts.last()
            val earlier = turnThoughts.dropLast(1)
            earlier.forEach { hidden += it.id }
            groups[newest.id] = ThoughtGroup(key = turnThoughts.first().id, earlier = earlier)
        }
        turnThoughts = ArrayList()
    }

    for (item in this) {
        when {
            item is ChatItem.User -> closeTurn()
            item is ChatItem.Thinking && item.isReasoning -> turnThoughts += item
        }
    }
    closeTurn()

    if (hidden.isEmpty()) return ReasoningGroups(this, emptyMap())
    return ReasoningGroups(filterNot { it.id in hidden }, groups)
}
