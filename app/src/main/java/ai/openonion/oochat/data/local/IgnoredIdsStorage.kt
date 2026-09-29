package ai.openonion.oochat.data.local

/**
 * Persistence boundary for the per-agent "cleared chat ids" sets used
 * by [ai.openonion.oochat.ui.chat.ChatViewModel].
 *
 * Scoping ids by agent is defensive: ids happen to be content-derived
 * SHA-256 prefixes today, so cross-agent collisions are vanishingly
 * rare, but the moment we switch to UUID-per-ChatItem (a natural
 * follow-up for privacy) two agents could share ids and one user's
 * "clear history" would silently suppress the other agent's replies.
 *
 * Implementation notes:
 *  - [loadAll] returns the full map so ChatViewModel can hydrate its
 *    in-memory map once at construction. Read happens on a hot path
 *    (every ChatViewModel init) so implementations should cache.
 *  - [saveForAgent] is suspending and replaces only the entry for the
 *    given agent. Empty sets are valid input (clearChat snapshots an
 *    empty chatItems on a freshly-opened session).
 *  - Passing an empty [Set] to [saveForAgent] does NOT delete the
 *    agent entry — implementations must still keep the (empty) entry
 *    so the "this agent has been cleared" signal survives.
 */
interface IgnoredIdsStorage {
    suspend fun loadAll(): Map<String, Set<String>>
    suspend fun saveForAgent(agentAddress: String, ids: Set<String>)
}
