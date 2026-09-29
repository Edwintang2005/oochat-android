package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.domain.model.stableAssistantId

/**
 * Decides whether a resumed local conversation is behind the transcript the
 * server replays on connect, and what exactly it is missing.
 *
 * Identity is content-derived ([stableAssistantId]), not the server's `turn`
 * counter: `turn` is nullable on the wire, is stored per *agent address*
 * (session_states) rather than per local chat session, and advances on
 * tool-only turns that add no transcript text.
 */
object SessionFreshness {

    /**
     * Server transcript entries the local copy doesn't have, oldest first.
     * Blank entries and repeats of the same content collapse away, matching
     * how identical content becomes a single chat item downstream.
     */
    fun missingEntries(serverEntries: List<String>, localIds: Set<String>): List<String> {
        val taken = mutableSetOf<String>()
        return serverEntries.mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val id = stableAssistantId(trimmed)
            if (id in localIds || !taken.add(id)) null else trimmed
        }
    }

    /** True when the server holds transcript the local copy hasn't got. */
    fun isLocalStale(serverEntries: List<String>, localIds: Set<String>): Boolean =
        missingEntries(serverEntries, localIds).isNotEmpty()
}
