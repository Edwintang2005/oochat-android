package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.model.resolveFalseFailures
import ai.openonion.oochat.domain.model.stableAssistantId
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One saved agent paired with its own persisted session list — the drawer's per-agent grouping. */
data class AgentWithSessions(
    val agent: AgentProfile,
    val sessions: List<ChatSession>
)

/**
 * What [ConversationHistoryUseCase.ensureActiveSession] resolved to, and
 * what the caller should therefore render. Returned (rather than only
 * mutating `activeSessionId`) so a resumed conversation's persisted rows
 * reach the screen even when the server never answers.
 */
sealed class SessionResume {
    /** Already resolved for this agent — the caller keeps whatever it shows. */
    data object Unchanged : SessionResume()

    /** Nothing to resume (or an explicit fresh start) — the caller clears its list. */
    data object Started : SessionResume()

    /** The previous conversation, with its persisted rows oldest-first. */
    data class Resumed(val sessionId: String, val items: List<ChatItem>) : SessionResume()
}

/**
 * A published snapshot handle over the live display-timestamp map.
 *
 * Every publish wraps the *same* backing map in a new instance, so identity —
 * which is what StateFlow conflation, `remember` keys and Compose skipping all
 * go by — changes exactly when something was added, without copying entries.
 * `equals` is deliberately left as identity for that reason: content equality
 * would compare the shared backing map against itself and suppress every
 * emission. The map only ever gains keys while a view is out, so a view read a
 * moment late is at worst missing the newest timestamp, never wrong about one.
 */
private class TimestampView(
    private val backing: Map<String, Long>
) : Map<String, Long> by backing

/**
 * How many of a conversation's newest rows are loaded when it is resumed, and
 * how many older ones each scroll-to-top page adds.
 *
 * A conversation is unbounded but a screen is not: a thousand-message chat with
 * tool calls holds megabytes of decoded payload (a single `ToolCall.result` can
 * be a whole file), all of it retained for a screen that shows a dozen rows.
 */
internal const val CONVERSATION_PAGE_SIZE = 200

/**
 * A row with the default title and nothing in it — swept by
 * [ConversationHistoryUseCase.ensureActiveSession], never resumed. Shared
 * with [ResumableConversationUseCase] so the "which conversation comes back"
 * rule has exactly one definition.
 */
internal fun ChatSession.isEmptyPlaceholder(): Boolean =
    messageCount == 0 && title == "New conversation"

/**
 * Local (Room-backed) conversation history — session + message read/write
 * and the drawer's session list.
 *
 * Does not own a [CoroutineScope] of its own; [ensureActiveSession] takes
 * one from its caller. Uses [PersistenceTransaction] for atomic writes.
 */
class ConversationHistoryUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val persistenceTransaction: PersistenceTransaction,
    /** Seam for tests; production mints a random v4 UUID per conversation. */
    private val newConversationId: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * The session id the live connection to an agent is already on, or null.
     * A brand-new conversation adopts it instead of minting — see
     * [ensureActiveSession]. Defaults to "nothing live", which mints as before.
     */
    private val liveSessionId: (String) -> String? = { null }
) {
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * The conversation on screen, whether or not it has a Room row yet —
     * unlike [activeSessionId], which stays null until the first write.
     *
     * This is also the session id the connection uses, so a brand-new
     * conversation can CONNECT on its own session before anything is
     * persisted (see ConnectionRepositoryImpl.switchSessionTo).
     */
    val conversationId: String?
        get() = when (val r = resolution) {
            is SessionResolution.Active -> r.sessionId
            is SessionResolution.Pending -> r.conversationId
            SessionResolution.Unresolved -> null
        }

    // Display timestamps keyed by ChatItem id, mirroring the moment
    // persistMessage() writes each message's ChatMessage.timestamp. Kept
    // separate from ChatItem itself (which has no timestamp field) so
    // MessageBubble can still show a real send/receive time without
    // changing that sealed class.
    //
    // Appended to in place rather than rebuilt: the map used to be published
    // as `it + (id to now)`, which copies every entry on every persisted
    // message — quadratic over a conversation, paid on the same thread the
    // arriving message is being rendered on. Concurrent because the writer is
    // a coroutine and the reader is composition.
    @Volatile
    private var timestampValues = ConcurrentHashMap<String, Long>()
    private val _itemTimestamps = MutableStateFlow<Map<String, Long>>(TimestampView(timestampValues))
    val itemTimestamps: StateFlow<Map<String, Long>> = _itemTimestamps.asStateFlow()

    /**
     * Whether the loaded window starts after the conversation's first row, i.e.
     * whether scrolling to the top has anything left to fetch. Drives the chat
     * screen's "load earlier messages" prefetch.
     */
    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    /**
     * Where the loaded window starts within the session, counted in Room rows
     * from the conversation's first message — the offset the next older page
     * reads back from. Zero means the whole conversation is loaded.
     */
    private var windowStartRow = 0
    private var windowSessionId: String? = null

    /** Local (Room) agent id backing the active session set, distinct from the agent's address. */
    private var currentSessionAgentId: String? = null

    private var sessionsWatchJob: Job? = null

    /**
     * Represents "unresolved / pending-not-yet-persisted / active" as one type
     * so both can't disagree. Prevents one call setting one state while another
     * races in and sets a different one, which was the cause of a real
     * cold-start/reconnect session bug.
     */
    private sealed class SessionResolution {
        data object Unresolved : SessionResolution()

        // Room row is deferred until the first real write, matching
        // "don't save empty chats" behavior. The id is settled now even so:
        // it is what the connection talks on, and waiting for the row would
        // leave the conversation's session unnamed until its first message.
        data class Pending(val conversationId: String) : SessionResolution()

        data class Active(val sessionId: String) : SessionResolution()
    }

    private var resolution: SessionResolution = SessionResolution.Unresolved
        set(value) {
            field = value
            _activeSessionId.value = (value as? SessionResolution.Active)?.sessionId
        }

    /**
     * Every saved agent paired with its own persisted session list — the
     * data source for the agent-grouped drawer (NavDrawer), as opposed to
     * [sessions] above, which is scoped to whichever single agent is
     * currently connected. Reactive: re-emits when the agent roster changes
     * or when any individual agent's session list changes.
     */
    fun observeAllAgentSessions(): Flow<List<AgentWithSessions>> =
        agentRepository.getAllAgents().flatMapLatest { agents ->
            if (agents.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(agents.map { agent ->
                    sessionRepository.getSessionsByAgent(agent.id).map { agent to it }
                }) { pairs -> pairs.map { (agent, sessions) -> AgentWithSessions(agent, sessions) } }
            }
        }

    /**
     * Resolve (or start) the persisted conversation for [agentAddress] and start
     * watching that agent's session list for the drawer, using [scope] for the
     * long-lived collector.
     *
     * Resuming the same agent keeps the already-active session; connecting to a
     * different agent resets tracking and resumes that agent's most recent
     * conversation, or starts a fresh one if it has none yet.
     *
     * [forceNew] always goes pending regardless of what [agentAddress] already
     * has. The decision is made synchronously by the single caller to avoid a
     * two-coroutine race.
     */
    suspend fun ensureActiveSession(
        agentAddress: String,
        scope: CoroutineScope,
        forceNew: Boolean = false
    ): SessionResume {
        val agent = agentRepository.getAgentByAddress(agentAddress) ?: return SessionResume.Unchanged
        FileLogger.i(LogTags.CONV_HISTORY, "ensureActiveSession($agentAddress) forceNew=$forceNew currentSessionAgentId=$currentSessionAgentId resolution=$resolution")

        sessionsWatchJob?.cancel()
        sessionsWatchJob = scope.launch {
            sessionRepository.getSessionsByAgent(agent.id).collect { _sessions.value = it }
        }

        if (!forceNew && currentSessionAgentId == agent.id && resolution != SessionResolution.Unresolved) {
            FileLogger.i(LogTags.CONV_HISTORY, "ensureActiveSession: already resolved for this agent, no-op")
            return SessionResume.Unchanged
        }
        // Captured before the suspensions below so the re-check can tell a
        // resolution that arrived *during* them from one that was already
        // sitting there. Switching agents leaves the previous agent's
        // resolution in place, and comparing against it is the whole point.
        val resolutionOnEntry = resolution
        currentSessionAgentId = agent.id

        val existing = sessionRepository.getSessionsByAgent(agent.id).first()

        // Sweep up empty "New conversation" rows left behind by the old
        // eager-creation behavior (or any other now-closed path) — the exact
        // title + zero-message match means this can never touch a real,
        // user-named, or non-empty session.
        val (empty, real) = existing.partition { it.isEmptyPlaceholder() }
        empty.forEach { stale ->
            runCatchingCancellable { sessionRepository.deleteSession(stale.id) }
                .onFailure { e -> FileLogger.w(LogTags.CONV_HISTORY, "Failed to sweep empty session ${stale.id}: ${e.message}") }
        }

        // Re-check after suspension in case startNewSession raced in. Compared
        // against the entry snapshot, not against Unresolved: the assignment
        // above makes the agent-id term trivially true, so testing resolution
        // for "not Unresolved" backed off whenever *any* earlier conversation
        // had resolved — including the agent we are switching away from. That
        // returned Unchanged on every agent switch, which the caller reads as
        // "keep what's on screen", so the previous agent's session stayed
        // active and swallowed the next message.
        if (!forceNew &&
            currentSessionAgentId == agent.id &&
            resolution != resolutionOnEntry &&
            resolution != SessionResolution.Unresolved
        ) {
            FileLogger.i(LogTags.CONV_HISTORY, "ensureActiveSession: resolved by someone else while suspended, backing off")
            return SessionResume.Unchanged
        }

        val session = if (forceNew) null else real.firstOrNull()
        return if (session != null) {
            // Load the rows here, not just the id: the caller has to be able
            // to render this conversation before (or without) the server ever
            // answering — see ChatViewModel.restoreLocalSession.
            val items = loadSessionItems(session.id)
            resolution = SessionResolution.Active(session.id)
                FileLogger.i(LogTags.CONV_HISTORY, "ensureActiveSession: resuming ${session.id} (${session.title}) with ${items.size} local item(s)")
            SessionResume.Resumed(session.id, items)
        } else {
            // No real conversation yet (or forceNew) — defer creating the
            // Room row until persistMessage() actually has something to
            // write (see SessionResolution.Pending's own doc).
            //
            // The id is adopted from the live connection when there is one:
            // the cold-start probe connects unnamed and the server mints a
            // session, and minting a rival id here would only move the
            // connection off it. forceNew always mints, as does startNewSession.
            val adopted = if (forceNew) null else liveSessionId(agentAddress)
            FileLogger.i(
                LogTags.CONV_HISTORY,
                "ensureActiveSession: no real session yet or forceNew, going pending " +
                    "(${if (adopted != null) "adopting $adopted" else "minting"})"
            )
            resolution = SessionResolution.Pending(adopted ?: newConversationId())
                replaceTimestamps(emptyMap())
                resetWindow()
            SessionResume.Started
        }
    }

    /**
     * Start a fresh conversation with the currently active agent. Doesn't
     * persist anything yet — see [SessionResolution.Pending]'s own doc; the
     * Room row is only created once [persistMessage] has something real to
     * write. Returns false (no-op) if no agent session has ever been
     * established yet. Caller is responsible for clearing its own visible
     * chat-item list — that's UI state this use case doesn't own.
     */
    suspend fun startNewSession(): Boolean {
        val agentId = currentSessionAgentId ?: return false
        FileLogger.i(LogTags.CONV_HISTORY, "startNewSession() agentId=$agentId, was resolution=$resolution")
        resolution = SessionResolution.Pending(newConversationId())
        replaceTimestamps(emptyMap())
        resetWindow()
        return true
    }

    /**
     * Load a previously persisted conversation and make it active. Returns
     * the chat items to render; caller applies them to its own UI state.
     * Deliberately doesn't touch [currentSessionAgentId] — this can be
     * (and is, in tests) called before any agent context is established.
     */
    suspend fun switchToSession(sessionId: String): List<ChatItem> {
        val items = loadSessionItems(sessionId)
        resolution = SessionResolution.Active(sessionId)
        return items
    }

    /**
     * Decode the newest [CONVERSATION_PAGE_SIZE] rows of a session into
     * renderable [ChatItem]s and replace (not merge with) the displayed
     * timestamp map with theirs. Shared by [switchToSession] and
     * [ensureActiveSession]'s resume path.
     *
     * Only the tail is loaded; [loadOlderItems] pages the rest back in when
     * the reader scrolls up to it. Room stays the durable copy either way.
     */
    private suspend fun loadSessionItems(sessionId: String): List<ChatItem> {
        val total = messageRepository.getMessageCount(sessionId)
        val start = maxOf(0, total - CONVERSATION_PAGE_SIZE)
        val messages = messageRepository.getMessagesPageBySession(sessionId, CONVERSATION_PAGE_SIZE, start)
        windowSessionId = sessionId
        windowStartRow = start
        _hasOlderMessages.value = start > 0
        // Heals rows written before the live recovery could correct them —
        // see resolveFalseFailures. In memory only: rewriting history on every
        // read would put a write on the load path for a display-only fix.
        val items = messages.toChatItems().resolveFalseFailures()
        // Restore each message's real persisted timestamp for display,
        // replacing (not merging with) whatever the previous session's map held.
        replaceTimestamps(messages.associate { it.id to it.timestamp })
        return items
    }

    /**
     * The page of rows immediately before the loaded window, oldest-first, for
     * the caller to prepend. Empty once the conversation's first row is on
     * screen — which is also what [hasOlderMessages] reports ahead of time.
     */
    suspend fun loadOlderItems(): List<ChatItem> {
        val sessionId = windowSessionId ?: return emptyList()
        if (windowStartRow <= 0) return emptyList()
        val start = maxOf(0, windowStartRow - CONVERSATION_PAGE_SIZE)
        val messages = messageRepository.getMessagesPageBySession(sessionId, windowStartRow - start, start)
        windowStartRow = start
        _hasOlderMessages.value = start > 0
        // Merged, not replaced: the window this extends is still on screen and
        // its rows still need their timestamps.
        addTimestamps(messages.associate { msg -> msg.id to msg.timestamp })
        return messages.toChatItems()
    }

    /**
     * Told by the caller when it has dropped [count] items off the front of the
     * visible list to stay under its own cap, so the window offset still points
     * at the first row *not* on screen and paging back up resumes there.
     *
     * Counted in items against an offset in rows: a row that fails to decode is
     * dropped by [toChatItems] and so is not counted here. That only ever
     * mis-aligns the boundary by the number of corrupt payloads, and a page that
     * overlaps re-delivers ids the caller already de-duplicates.
     */
    fun noteHeadDropped(count: Int) {
        if (count <= 0 || windowSessionId == null) return
        windowStartRow += count
        _hasOlderMessages.value = windowStartRow > 0
    }

    /**
     * Replace the whole map — a different conversation's rows. A fresh backing
     * map rather than clear-then-fill: views already handed out keep pointing
     * at the old one, so nobody ever observes the half-emptied middle.
     */
    private fun replaceTimestamps(values: Map<String, Long>) {
        timestampValues = ConcurrentHashMap(values)
        _itemTimestamps.value = TimestampView(timestampValues)
    }

    /** Add to the map in place — one entry per persisted message, one page per scroll-up. */
    private fun addTimestamps(values: Map<String, Long>) {
        if (values.isEmpty()) return
        val target = timestampValues
        target.putAll(values)
        _itemTimestamps.value = TimestampView(target)
    }

    /** Forgets the loaded window — a conversation that is gone has no older pages. */
    private fun resetWindow() {
        windowSessionId = null
        windowStartRow = 0
        _hasOlderMessages.value = false
    }

    /**
     * Transcript rows carry their shape in `payload`; plain user/assistant rows
     * carry theirs in `role`. Shared by the initial window and every older page
     * so both decode a row exactly the same way.
     */
    private fun List<ChatMessage>.toChatItems(): List<ChatItem> =
        mapNotNull { msg ->
            // Transcript rows carry their shape in `payload` with empty
            // `content`, so they decode before the role branch sees them. One
            // that fails drops out rather than rendering as an empty bubble.
            // A Turn payload annotates a real assistant row rather than
            // replacing it, so it falls through to the role branch below;
            // every other payload IS the item.
            if (msg.itemType != TranscriptItemCodec.TURN_ITEM_TYPE) {
                msg.payload?.let { raw ->
                    return@mapNotNull TranscriptItemCodec.decode(raw)
                }
            }
            when (msg.role) {
                Role.USER -> ChatItem.User(
                    id = msg.id,
                    content = msg.content,
                    images = msg.images,
                    files = msg.files
                )
                // Turn, not Agent: the live flow renders every assistant
                // reply as a Turn, and minting the same shape here is what
                // stops a reload changing how a message looks. Rows written
                // before turn metadata was stored carry no payload and so
                // come back footer-less, which is the honest rendering.
                Role.ASSISTANT, Role.SYSTEM -> ChatItem.Turn(
                    id = msg.id,
                    thinking = msg.payload?.let { TranscriptItemCodec.decodeTurnThinking(it) },
                    agent = ChatItem.Agent(id = msg.id, content = msg.content, images = msg.images)
                )
            }
        }

    /**
     * Top up the resumed conversation with whatever the server's replayed
     * transcript holds and the local rows don't, persisting each entry and
     * returning it for the caller to render. Empty when the local copy is
     * already current — the common case, so a reconnect doesn't churn Room.
     *
     * Only tops up an already-[SessionResolution.Active] conversation: a
     * server transcript alone is not reason enough to materialize a session.
     */
    suspend fun mergeServerTranscript(serverEntries: List<ServerTranscriptEntry>, serverTurn: Int?): List<ChatItem> {
        val sessionId = _activeSessionId.value ?: run {
            // The one exit with nothing to say for itself, which is why the
            // replay silently vanishing took a device trace to find.
            FileLogger.i(
                LogTags.CONV_HISTORY,
                "mergeServerTranscript: no active conversation yet, holding " +
                    "${serverEntries.size} replayed entry(s) (turn=$serverTurn)"
            )
            return emptyList()
        }
        // The session is this conversation's alone, so the whole transcript
        // is its own — no attribution needed, only a freshness check.
        // Keyed on the row id *and* its content hash: not every persisted
        // reply was minted with a content-derived id (OUTPUT-, image- and
        // pre-stableAssistantId rows weren't), and those would otherwise
        // look like transcript this conversation is missing.
        val localIds = messageRepository.getMessagesListBySession(sessionId)
            .flatMapTo(mutableSetOf()) { listOf(it.id, stableAssistantId(it.content)) }
        val missing = SessionFreshness.missingEntries(serverEntries.map { it.content }, localIds)
            // A reply already filed under a *different* conversation stays
            // there — persistMessage inserts with REPLACE, so re-persisting
            // it here would move the row out of the session that owns it.
            .filterNot { messageRepository.existsById(stableAssistantId(it)) }
        if (missing.isEmpty()) {
            FileLogger.i(LogTags.CONV_HISTORY, "mergeServerTranscript: local copy current (turn=$serverTurn, ${serverEntries.size} server entries)")
            return emptyList()
        }

        FileLogger.i(LogTags.CONV_HISTORY, "mergeServerTranscript: local copy stale, taking ${missing.size} entry(s) into $sessionId (turn=$serverTurn)")
        return missing.map { content ->
            val id = stableAssistantId(content)
            ChatItem.Turn(id = id, agent = ChatItem.Agent(id = id, content = content))
        }.onEach { persistMessage(it, sessionId) }
    }

    /**
     * Permanently delete a persisted session (drawer row trash action). If
     * it was the active session, going Pending (not Unresolved) after
     * deleting ensures a message sent right after still gets persisted.
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        sessionRepository.deleteSession(sessionId)
        if ((resolution as? SessionResolution.Active)?.sessionId != sessionId) return false
        resolution = SessionResolution.Pending(newConversationId())
        replaceTimestamps(emptyMap())
        resetWindow()
        return true
    }

    /**
     * Rename a persisted session (drawer row pencil action). Unlike
     * [deleteSession], renaming never changes which session is active, so
     * it is just the repository call.
     */
    suspend fun renameSession(sessionId: String, newTitle: String) {
        sessionRepository.renameSession(sessionId, newTitle)
    }

    /**
     * The active session id, materializing a [SessionResolution.Pending]
     * session immediately if that's the current state — unlike reading
     * [activeSessionId] directly, this always returns a concrete,
     * already-persisted id (or null if there's no session context at all).
     *
     * Exists for callers that need to pin a concrete id *before* an async
     * gap — e.g. [ai.openonion.oochat.ui.chat.ChatViewModel.sendMessage]
     * captures this first thing inside its launched coroutine, ahead of the
     * image-attachment work, so a session switch mid-flight can't misfile
     * the message. Reading `activeSessionId.value` there instead would
     * capture `null` while a session is merely pending, and by the time
     * [persistMessage] finally ran, the pending session could belong to an
     * agent the user has since switched *away* from.
     */
    suspend fun resolveActiveSessionId(): String? = _activeSessionId.value ?: materializePendingSession()

    /**
     * Persist a chat item into the active session's message log, if it carries
     * user/assistant text. No-ops for live-only item types (Thinking, ToolCall,
     * cards, etc.).
     *
     * Executes atomically: message insert, session title update, and message
     * count update all succeed together or all fail together.
     *
     * @param targetSessionId Pins the write to a specific session instead of
     *   whatever's active when this function runs. Prevents a session switch
     *   mid-flight (e.g. during image attachment) from silently writing into
     *   the new session instead.
     */
    suspend fun persistMessage(item: ChatItem, targetSessionId: String? = _activeSessionId.value) {
        val sessionId = targetSessionId ?: materializePendingSession() ?: return

        val now = System.currentTimeMillis()
        addTimestamps(mapOf(item.id to now))

        runCatchingCancellable {
            persistenceTransaction.persistMessageAtomically(
                sessionId = sessionId,
                item = item,
                onTitleUpdate = { _, _ -> },
                onPreviewUpdate = { _, _, _ -> }
            )
        }.onFailure { e -> FileLogger.e(LogTags.CONV_HISTORY, "persistMessage failed: ${e.message}") }
    }

    /**
     * Creates the deferred Room row for a [SessionResolution.Pending]
     * resolution, the moment there's actually something to persist into it.
     * Returns null (and does nothing) if there's no pending session — the
     * ordinary "nothing to persist into" case handled by [persistMessage]'s
     * own `?: return`.
     */
    private suspend fun materializePendingSession(): String? {
        val pending = resolution as? SessionResolution.Pending ?: return null
        // Always non-null here in practice — Pending is only ever set once
        // currentSessionAgentId already holds the relevant agent (see
        // SessionResolution's own doc) — except the one edge case where
        // deleteSession() re-pends a session that was made active via
        // switchToSession() before any agent context existed; degrading to
        // a no-op there matches this class's pre-refactor behavior exactly.
        val agentId = currentSessionAgentId ?: return null
        // Created under the id minted when this conversation started, so the
        // row matches the session the connection has been talking on.
        val session = sessionRepository.createSession(agentId, "New conversation", pending.conversationId)
        FileLogger.i(LogTags.CONV_HISTORY, "materializePendingSession: created ${session.id} for $agentId")
        resolution = SessionResolution.Active(session.id)
        return session.id
    }

    /** Wipes the active session's persisted log (used by `clearChat`). */
    suspend fun clearActiveSession() {
        val sessionId = _activeSessionId.value ?: return
        runCatchingCancellable {
            messageRepository.deleteMessagesBySession(sessionId)
            sessionRepository.updateMessageInfo(sessionId, 0, null)
        }.onFailure { e -> FileLogger.e(LogTags.CONV_HISTORY, "session clear failed: ${e.message}") }
        // The rows the window pointed into are gone, so there is nothing older
        // to page back in — leaving the offset would offer a "load earlier"
        // that returns an empty page forever.
        resetWindow()
    }
}
