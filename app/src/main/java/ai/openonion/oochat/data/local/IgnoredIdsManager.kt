package ai.openonion.oochat.data.local

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags

/**
 * Per-agent "ignored ids" bookkeeping, extracted from
 * [ai.openonion.oochat.ui.chat.ChatViewModel]. ChatItems the user
 * has chosen to discard (typically via `clearChat`) land in the set of the
 * agent that produced them. Subsequent replays of the same items — same
 * agent, same content, same id — are filtered by [ignoredIdsFor] before
 * being appended to the visible chat list. Scoping by agent makes a clear
 * in one agent never bleed into another.
 *
 * Persisted via [storage] so a process kill between a clear and the next
 * chat session still honours it.
 */
class IgnoredIdsManager(private val storage: IgnoredIdsStorage) {

    private var ignoredByAgent: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Hydrates from disk. Call once, before the first event can arrive. */
    suspend fun loadAll() {
        runCatching {
            ignoredByAgent = storage.loadAll()
                .mapValues { it.value.toMutableSet() }
                .toMutableMap()
        }.onFailure { FileLogger.e(LogTags.IGNORED_IDS_MGR, "load failed: ${it.message}") }
    }

    fun ignoredIdsFor(agentAddress: String): Set<String> =
        ignoredByAgent[agentAddress] ?: emptySet()

    suspend fun ignoreAll(agentAddress: String, ids: Collection<String>) {
        val set = ignoredByAgent.getOrPut(agentAddress) { mutableSetOf() }
        set.addAll(ids)
        runCatching { storage.saveForAgent(agentAddress, set) }
            .onFailure { FileLogger.e(LogTags.IGNORED_IDS_MGR, "save failed: ${it.message}") }
    }
}
