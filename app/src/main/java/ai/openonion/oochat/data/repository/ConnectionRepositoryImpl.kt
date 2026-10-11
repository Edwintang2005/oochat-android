package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.OipProtocol
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.protocol.toDomain
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ExecRequest
import ai.openonion.oochat.domain.model.HostModeState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import ai.openonion.oochat.domain.model.PlanEntry
import ai.openonion.oochat.domain.model.ServerSessionStatus
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.domain.usecase.ServerErrorText
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.InMemoryPendingMessageSink
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.ProtocolParser
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Implementation of ConnectionRepository.
 *
 * Manages AgentConnection lifecycle and provides
 * connection state to ViewModels.
 *
 * @param keyManager Key manager for Ed25519 signing
 * @param relayUrl Relay server URL
 * @param sessionStore Optional store for persisting session state across restarts
 */
class ConnectionRepositoryImpl(
    // Nullable only so tests can exercise observeEvents()/lifecycle without a real Android-backed KeyManager; connect() is the only place it's dereferenced, and only ConnectToAgentUseCase passes non-null in production.
    private val keyManager: KeyManager? = null,
    private val relayUrl: String = AgentConnection.DEFAULT_RELAY,
    private val sessionStore: SessionStore? = null,
    // Defaults to in-memory, matching the pre-outbox behaviour, so existing tests construct this unchanged.
    private val pendingMessageSink: PendingMessageSink = InMemoryPendingMessageSink(),
    // Factory, not a shared instance, so tests can inject an AgentConnection backed by a fake WebSocketFactory (see AgentConnectionRobolectricTest) instead of opening a real WebSocket.
    // Whether the app is on screen; see AgentConnection's isAppVisible.
    private val isAppVisible: () -> Boolean = { true },
    private val agentConnectionFactory: (KeyManager, String) -> AgentConnection = { km, url ->
        AgentConnection(km, url, pendingMessageSink = pendingMessageSink, isAppVisible = isAppVisible)
    },
    // Reads the relay's directory for the agent's published skills. Defaults to
    // null so the existing tests construct this unchanged and make no HTTP call.
    private val agentDiscovery: AgentDiscoveryService? = null
) : ConnectionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: AgentConnection? = null
    private var currentAgentAddress: String? = null

    // The conversation whose server session this connection is on, and the
    // key every session write is filed under.
    @Volatile private var currentConversationId: String? = null

    // ── session persistence ──────────────────────────────────────────
    //
    // Every write re-encodes the WHOLE transcript and rewrites the row
    // (SessionStoreImpl, @Insert REPLACE). `session_sync` streams after each
    // trace entry, so a turn with 20 tool calls used to pay that 20 times on
    // this collector — the same coroutine that has to handle the next inbound
    // frame. The frame-driven writes are therefore conflated into one trailing
    // write per window; the two that mark a boundary (CONNECTED, OUTPUT) still
    // write inline, since they are rare and are what resume actually needs.
    // Losing the last window of transcript costs nothing: the server replays
    // its own session on the next CONNECT, so the row is a cache, not a record.

    private val sessionSaveLock = Any()
    private var pendingSessionSave: Pair<String, SessionState>? = null
    private var sessionSaveJob: Job? = null

    // The store writes with REPLACE, so overlapping writes could land out of
    // order and persist the older session. Every write goes through here.
    private val sessionWriteMutex = Mutex()

    /**
     * Persist [session] under the conversation on screen, now. Supersedes
     * anything [scheduleSessionSave] is still holding — the caller always has
     * the newest session, and leaving the older one queued would let it land
     * afterwards and overwrite this one.
     */
    private suspend fun saveSessionForCurrentConversation(session: SessionState) {
        val conversationId = currentConversationId ?: return
        synchronized(sessionSaveLock) { pendingSessionSave = null }
        sessionWriteMutex.withLock { sessionStore?.saveSession(conversationId, session) }
    }

    /**
     * Queue [session] to be persisted within [SESSION_SAVE_WINDOW_MS], keyed
     * to the conversation as it is *now* so a switch mid-window cannot misfile
     * it. Conflating, not resetting: a continuous stream of frames still gets
     * a write every window rather than being starved to the end of the turn.
     */
    private fun scheduleSessionSave(session: SessionState) {
        val conversationId = currentConversationId ?: return
        if (sessionStore == null) return
        synchronized(sessionSaveLock) {
            pendingSessionSave = conversationId to session
            if (sessionSaveJob?.isActive == true) return
            sessionSaveJob = scope.launch {
                while (true) {
                    delay(SESSION_SAVE_WINDOW_MS)
                    // Taking the slot and clearing the job handle happen under
                    // the same lock the scheduler checks, so a frame arriving
                    // as this loop exits either finds a live job (and is picked
                    // up next turn) or starts a new one — never stranded.
                    val queued = synchronized(sessionSaveLock) {
                        pendingSessionSave.also { pendingSessionSave = null }
                            ?: run { sessionSaveJob = null; null }
                    } ?: break
                    sessionWriteMutex.withLock { sessionStore.saveSession(queued.first, queued.second) }
                }
            }
        }
    }

    // Reset at the top of every connect() call — distinguishes a genuine connect-time failure from a business-logic ERROR on an already-established session. See the ConnectionEvent.ConnectionError branch below for the full rationale.
    private var hasConnectedThisAttempt = false

    // Guards the teardown-then-rebuild section of connect() below — without
    // it, two near-simultaneous calls (e.g. a double-tapped Reconnect banner
    // before recomposition disables it) could interleave their reads/writes
    // of `connection`/`eventCollectionJob`.
    private val connectMutex = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Connection-scoped, not chat-scoped — see ConnectionRepository.agentProfile's
    // doc. Reset to null at the top of connect() (below) so a reconnect to a
    // different (or the same, freshly-restarted) agent can't leave the
    // previous agent's tools/skills visible until the new AGENT_PROFILE lands.
    private val _agentProfile = MutableStateFlow<AgentLiveProfile?>(null)
    override val agentProfile: StateFlow<AgentLiveProfile?> = _agentProfile.asStateFlow()

    // Same connection-scoped lifetime as _agentProfile, reset alongside it in
    // connect(). Two writers: setMode() (optimistic, so the chip moves on tap)
    // and every inbound mode_changed frame (authoritative — the agent can also
    // switch on its own, and its notification is what confirms or corrects
    // ours). Last write wins, which is right in both directions: the server's
    // reply always arrives after our optimistic write.
    private val _approvalMode = MutableStateFlow(ApprovalMode.DEFAULT)
    override val approvalMode: StateFlow<ApprovalMode> = _approvalMode.asStateFlow()

    // The rollback target for a rejected mode_change — see the ConnectionError
    // branch below. Only a confirmation (mode_changed, or connect()'s own
    // reset) may move this; setMode()'s optimistic write must not, or a
    // rejected change would "roll back" to the very value the server just
    // refused.
    private var confirmedApprovalMode = ApprovalMode.DEFAULT

    private val _modePending = MutableStateFlow(false)
    override val modePending: StateFlow<Boolean> = _modePending.asStateFlow()

    // Who owns the mode and what it may be; see ConnectionRepository.sessionModes.
    // Reset in connect(), kept across switchSessionTo() (same Host).
    private val _sessionModes = MutableStateFlow(SessionModes.LEGACY)
    override val sessionModes: StateFlow<SessionModes> = _sessionModes.asStateFlow()

    // Display-only twin of ConnectionState.Connected.status that is never
    // released; see ConnectionRepository.hostSessionStatus.
    private val _hostSessionStatus = MutableStateFlow(ServerSessionStatus.UNKNOWN)
    override val hostSessionStatus: StateFlow<ServerSessionStatus> = _hostSessionStatus.asStateFlow()

    // Same lifetime and the same reset as _agentProfile above — a Home page
    // and the skill list its buttons are validated against have to belong to
    // the same connection, or a button would be checked against the wrong
    // agent's allowlist.
    private val _dashboardHtml = MutableStateFlow<String?>(null)
    override val dashboardHtml: StateFlow<String?> = _dashboardHtml.asStateFlow()

    // Server-session state like the mode chip, with the same resets: cleared
    // on every connect and conversation switch, so one conversation's Todo
    // List never shows over another's.
    private val _plan = MutableStateFlow<List<PlanEntry>>(emptyList())
    override val plan: StateFlow<List<PlanEntry>> = _plan.asStateFlow()

    // Long-lived broadcast bus for chat events. ChatViewModel.init subscribes to observeEvents() BEFORE connect() establishes the connection, so this hot bus survives the "not yet connected" window and forwards every event once a connection exists.
    private val _chatEvents = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = AgentConnection.EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Sticky (not fired-and-forgotten into _chatEvents): with one shared
    // connection a ViewModel routinely subscribes *after* CONNECTED landed,
    // and this is what conversation-resume reconciles against. Replay is
    // safe — merging a transcript the local copy already has is a no-op.
    private val _serverTranscript = MutableStateFlow<ChatEvent.ServerTranscriptReceived?>(null)

    private var eventCollectionJob: Job? = null

    /**
     * Completed once the collector below has actually subscribed to
     * `conn.events` — or once its job ends without getting that far, so no
     * waiter can be stranded. [connect] awaits this before opening the socket.
     */
    private var eventCollectionStarted: CompletableDeferred<Unit>? = null

    private fun startCollectingEvents() {
        eventCollectionJob?.cancel()

        val started = CompletableDeferred<Unit>()
        eventCollectionStarted = started

        eventCollectionJob = scope.launch {
            val conn = connection
            if (conn == null) {
                FileLogger.e(LogTags.CONN_REPO, "Connection null when starting event collection")
                return@launch
            }
            conn.events.onSubscription { started.complete(Unit) }.collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> {
                        FileLogger.i(LogTags.CONN_REPO, "→ Connected: ${event.address.take(16)}")
                        hasConnectedThisAttempt = true
                        fetchPublishedProfile()
                        // Before the state below: a screen reacting to Connected
                        // must already see the Host's mode, not the reset default.
                        event.modes?.let { applyHostModes(it) }
                        _hostSessionStatus.value = event.status
                        _connectionState.value = ConnectionState.Connected(
                            address = event.address,
                            session = event.session?.toDomain(),
                            status = event.status,
                            serverNewer = event.serverNewer
                        )
                        event.session?.let { session ->
                            // Inline, not debounced: CONNECTED is once per
                            // connection and carries the id resume depends on,
                            // so it is worth the one write it costs.
                            saveSessionForCurrentConversation(session)
                            // Wire -> domain mapping of the replayed transcript
                            // happens here, at the same boundary toDomain() sits
                            // on. Assistant entries only; the session is this
                            // conversation's alone, so the whole transcript
                            // belongs to it and needs no further attribution.
                            _serverTranscript.value = ChatEvent.ServerTranscriptReceived(
                                entries = session.messages.orEmpty().mapNotNull { msg ->
                                    msg.content?.takeIf { msg.role.equals("assistant", ignoreCase = true) }
                                        ?.let { ServerTranscriptEntry(content = it) }
                                },
                                turn = session.turn
                            )
                        }
                    }
                    is ConnectionEvent.ConnectionError -> {
                        // Post-Connected ERROR is non-fatal (matches web client): a business-logic rejection like "Insufficient ConnectOnion Credits"; the server keeps the socket open (verified via PING/PONG). Pre-Connected ERROR must flip state to Error, or LoadingScreen hangs on "Connecting…".
                        // Logs keep the server's raw wording; only what the user reads goes through the mapper.
                        val shown = ServerErrorText.humanize(event.message)
                        if (hasConnectedThisAttempt && ServerErrorText.isAgentOffline(event.message)) {
                            // The relay answering a live socket after the Host dropped off
                            // (W16AAPPLE-93). It drops the socket straight after, and that
                            // reconnect is the status the user sees, so this is no reply:
                            // the web client never adds an ERROR to the conversation either.
                            FileLogger.eRepeating(LogTags.CONN_REPO, "→ Error (agent offline, reconnect follows): ${event.message}")
                        } else if (hasConnectedThisAttempt) {
                            FileLogger.eRepeating(LogTags.CONN_REPO, "→ Error (non-fatal, socket stays open): ${event.message}")
                            // The two windows a mode_change frame can miss its
                            // mark (session.py's active_io gate, or the
                            // before_iteration poll) both surface as this ERROR
                            // naming mode_change — see ConnectionRepository.modePending's
                            // doc. Roll the chip back rather than leave it
                            // claiming a mode the agent never adopted.
                            // A Host that manages modes answers at once, with one of
                            // the refusals ServerErrorText.isModeRefusal names (busy,
                            // unavailable mode). Either way the chip goes back.
                            if (_modePending.value && ServerErrorText.isModeRefusal(event.message)) {
                                _approvalMode.value = confirmedApprovalMode
                                _modePending.value = false
                            } else if (_hostSessionStatus.value == ServerSessionStatus.RUNNING) {
                                settleRunningStatusAfterError(event.message)
                            }
                            _chatEvents.tryEmit(ChatEvent.ConnectionErrorOccurred(shown))
                        } else {
                            // Rate-limited: the reconnect ladder replays the
                            // same rejection once per rung.
                            FileLogger.eRepeating(LogTags.CONN_REPO, "→ Error (never connected this attempt): ${event.message}")
                            _connectionState.value = ConnectionState.Error(message = shown)
                        }
                    }
                    is ConnectionEvent.ProtocolRejected -> {
                        // Fatal whether or not this attempt connected before:
                        // an auto-reconnect after a Host upgrade can be the
                        // CONNECT that is refused. retryable = false is what
                        // stops ConnectToAgentUseCase spending its second attempt.
                        FileLogger.e(LogTags.CONN_REPO, "→ Error (OIP version rejected, host=${event.hostProtocol ?: "<undeclared>"})")
                        _connectionState.value = ConnectionState.Error(
                            message = ServerErrorText.incompatibleProtocol(
                                hostProtocol = event.hostProtocol,
                                clientProtocol = "${OipProtocol.NAME}/${OipProtocol.VERSION}"
                            ),
                            retryable = false
                        )
                    }
                    is ConnectionEvent.ReconnectRefused -> {
                        // Fatal although this attempt connected before: it is
                        // the reconnect that was refused, so the session it
                        // was resuming is gone and only the user can try again
                        // (W16AAPPLE-82). Retryable, so Reconnect is offered.
                        FileLogger.e(LogTags.CONN_REPO, "→ Error (reconnect refused): ${event.message}")
                        _connectionState.value = ConnectionState.Error(message = ServerErrorText.humanize(event.message))
                    }
                    is ConnectionEvent.Disconnected -> {
                        FileLogger.i(LogTags.CONN_REPO, "→ Disconnected")
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    is ConnectionEvent.Reconnecting -> {
                        FileLogger.i(LogTags.CONN_REPO, "→ Reconnecting")
                        _connectionState.value = ConnectionState.Reconnecting
                    }
                    // State events (Connected/Error/Disconnected/Reconnecting) are intentionally not forwarded.
                    is ConnectionEvent.SessionUpdated -> {
                        // The flood: every session_sync in a turn carries a
                        // session, and each one is the whole transcript again.
                        // Conflated rather than written per frame — see the
                        // session-persistence block above.
                        scheduleSessionSave(event.session)
                        // CONNECTED, session_sync and OUTPUT all carry the
                        // Host's plan, which is how a reconnect gets it back
                        // without waiting for the next `plan` frame. A snapshot
                        // with no plan, or a malformed one, keeps what is shown.
                        ProtocolParser.decodePlan(event.session.plan)?.let { _plan.value = it }
                    }
                    is ConnectionEvent.PlanUpdated -> {
                        _plan.value = event.entries
                    }
                    is ConnectionEvent.ChatItemReceived -> {
                        // The one frame that is both a chat entry and connection
                        // state. Applied for replayed snapshot items too: the
                        // server's session mode survives a dropped socket, so
                        // the last mode_changed in a replayed transcript is the
                        // mode we are actually resuming into.
                        (event.item as? ChatItem.ModeChangedItem)?.let { changed ->
                            ApprovalMode.fromWire(changed.mode)?.let { mode ->
                                _approvalMode.value = mode
                                confirmedApprovalMode = mode
                                _modePending.value = false
                                // turns_left belongs to full access alone; any
                                // other mode ends a budget the chip was showing.
                                val turnsLeft = changed.turnsLeft?.takeIf { mode.isUnattended && it > 0 }
                                _sessionModes.update { it.copy(turnsLeft = turnsLeft) }
                            }
                        }
                        _chatEvents.tryEmit(ChatEvent.ChatItemReceived(
                            event.item,
                            event.fromSessionSnapshot,
                            event.answeredQuestion
                        ))
                    }
                    is ConnectionEvent.ChatItemUpdated -> {
                        _chatEvents.tryEmit(ChatEvent.ChatItemUpdated(event.item))
                    }
                    is ConnectionEvent.OutputReceived -> {
                        // A finished turn ends whatever CONNECT said was running. Before the emit
                        // below, not after it: a screen that sees the OUTPUT must not read "running".
                        releaseSessionStatus()
                        _hostSessionStatus.value = ServerSessionStatus.CONNECTED
                        // Inline for the same reason as CONNECTED: OUTPUT ends
                        // a turn, so it is the durable checkpoint worth paying
                        // a write for, and there is one per turn.
                        event.session?.let { session -> saveSessionForCurrentConversation(session) }
                        _chatEvents.tryEmit(ChatEvent.OutputReceived(event.result, event.session?.toDomain()))
                    }
                    is ConnectionEvent.Waiting -> {
                        _chatEvents.tryEmit(ChatEvent.Waiting)
                    }
                    is ConnectionEvent.AgentProfileReceived -> {
                        FileLogger.i(LogTags.CONN_REPO, "→ AgentProfile: model=${event.profile.model}, tools=${event.profile.tools.size}, skills=${event.profile.skills.size}")
                        _agentProfile.value = event.profile
                    }
                    is ConnectionEvent.DashboardSnapshotReceived -> {
                        FileLogger.i(LogTags.CONN_REPO, "→ DashboardSnapshot: ${event.html.length} chars")
                        _dashboardHtml.value = event.html
                    }
                    is ConnectionEvent.SessionStatusReceived -> {
                        // Shown only when it is about the session on screen. The Host echoes the
                        // id it was asked about, so a reply to a query sent before a conversation
                        // switch names the session that was left.
                        val current = connection?.currentSession?.sessionId
                        if (event.sessionId == null || event.sessionId == current) {
                            _hostSessionStatus.value = ServerSessionStatus.fromWire(event.status)
                        }
                        _chatEvents.tryEmit(ChatEvent.SessionStatusReceived(event.sessionId, event.status))
                    }
                    is ConnectionEvent.ModeBudgetChanged -> {
                        if (event.mode == _approvalMode.value) {
                            _sessionModes.update { it.copy(turnsLeft = event.turnsLeft?.takeIf { n -> n > 0 }) }
                        }
                    }
                    is ConnectionEvent.RuntimeInputAcknowledged -> {
                        _chatEvents.tryEmit(ChatEvent.RuntimeInputAcknowledged(event.sessionId))
                    }
                    is ConnectionEvent.RuntimeInputRejected -> {
                        _chatEvents.tryEmit(ChatEvent.RuntimeInputRejected(event.sessionId, event.retryable))
                    }
                    else -> { }
                }
            }
        }.also { job ->
            // A job that ends before subscribing (null connection, cancelled
            // scope) must still release connect(); completing twice is a no-op.
            job.invokeOnCompletion { started.complete(Unit) }
        }
    }

    override fun observeEvents(): Flow<ChatEvent> =
        merge(_chatEvents.asSharedFlow(), _serverTranscript.filterNotNull())

    override fun releaseServerTranscript() {
        // The whole transcript, held only so a subscriber that joined after
        // CONNECTED can still reconcile. Once one has, the entries are in Room
        // and nothing else will ever ask for them — keeping the copy alive for
        // the connection's life was pure retention. A ViewModel recreated later
        // reads the merged rows back from Room, not from here.
        _serverTranscript.value = null
    }

    override fun releaseSessionStatus() {
        // CONNECTED said it once, but this state stays Connected for the life of the socket, and a
        // screen created later starts from it and cannot tell a stale answer from a fresh one.
        _connectionState.update { state ->
            if (state is ConnectionState.Connected) {
                state.copy(status = ServerSessionStatus.UNKNOWN, serverNewer = false)
            } else {
                state
            }
        }
    }

    override suspend fun switchConversation(conversationId: String?) {
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                if (conversationId == currentConversationId) return@withLock
                // Flush what the conversation being left has accumulated;
                // nothing else writes it once the key moves.
                connection?.currentSession?.let { saveSessionForCurrentConversation(it) }
                switchSessionTo(conversationId)
            }
        }
    }

    /** Caller holds [connectMutex]. */
    private suspend fun switchSessionTo(conversationId: String?) {
        // Already talking on exactly this session — a conversation that adopted
        // the id an unnamed connect was handed. Take the key and keep the
        // socket: there is no session to move to, only one to start filing under.
        if (conversationId != null &&
            connection?.isConnected() == true &&
            conversationId == connection?.currentSession?.sessionId
        ) {
            FileLogger.i(LogTags.CONN_REPO, "switchSession: already on $conversationId, adopting it as the key")
            currentConversationId = conversationId
            connection?.currentSession?.let { saveSessionForCurrentConversation(it) }
            return
        }
        currentConversationId = conversationId
        // Belongs to the conversation being left — a late subscriber must not
        // reconcile the new conversation against the previous one's history.
        _serverTranscript.value = null
        // So does the Host's answer to the CONNECT being left: it described that session, and the
        // new one's own CONNECTED has yet to arrive.
        releaseSessionStatus()
        // Mode is a property of the server session, so switching conversation
        // makes it unknown again for the same reason connect() does: a
        // security-relevant control must not keep showing the mode of the
        // conversation we just left, and a mode_change still awaiting
        // confirmation there is never going to be answered here.
        // The catalogue stays (same Host), so the default is that Host's own.
        _sessionModes.update { it.copy(turnsLeft = null) }
        _approvalMode.value = _sessionModes.value.default
        confirmedApprovalMode = _sessionModes.value.default
        _modePending.value = false
        // What the Host said was about the session being left.
        _hostSessionStatus.value = ServerSessionStatus.UNKNOWN
        // Agent profile and dashboard deliberately survive: same agent, only
        // a different conversation with it.
        val session = sessionFor(conversationId)
        // The plan is the left conversation's. The one coming in is its stored
        // plan: an idle session's CONNECTED carries no snapshot of its own.
        showStoredPlan(session)
        connection?.switchSession(session)
    }

    /**
     * The wire session for [conversationId]: its stored one, or a bare
     * session under the conversation's own id when nothing is stored yet.
     *
     * The conversation id IS the session id — the server allocates under the
     * id it is given (websocket-protocol.md's state table, "Provided / not
     * found / allocate new session (same id)"). So a conversation's session
     * is recoverable from its id alone, and the store only adds the
     * transcript and turn count on top.
     */
    /** The plan [session] was saved with, or none — see SessionStateEntity.planJson. */
    private fun showStoredPlan(session: SessionState?) {
        _plan.value = ProtocolParser.decodePlan(session?.plan).orEmpty()
    }

    private suspend fun sessionFor(conversationId: String?): SessionState? {
        if (conversationId == null) return null
        val stored = conversationId.let { sessionStore?.getSession(it) }
        FileLogger.i(
            LogTags.CONN_REPO,
            "Conversation $conversationId: sid=$conversationId (${if (stored != null) "stored" else "fresh"})"
        )
        return stored ?: SessionState(sessionId = conversationId)
    }

    override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?) {
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                // One socket per agent. The Loading probe and ChatViewModel now
                // drive this same repository, and rebuilding a live connection
                // would hand the server a second session for the same agent.
                if (agentAddress == currentAgentAddress &&
                    connection?.isConnected() == true &&
                    _connectionState.value is ConnectionState.Connected
                ) {
                    FileLogger.i(LogTags.CONN_REPO, "connect: joining live connection to ${agentAddress.take(16)}")
                    // The socket is shared but the session is not: joining for
                    // a different conversation still has to move onto that
                    // conversation's own server session.
                    if (conversationId != currentConversationId) switchSessionTo(conversationId)
                    return@withLock
                }

                // Flush the outgoing conversation's live session before the
                // key moves. connect() tears the socket down through
                // AgentConnection.disconnect(), which never reaches the save
                // in this class's own disconnect().
                connection?.currentSession?.let { saveSessionForCurrentConversation(it) }

                _connectionState.value = ConnectionState.Connecting
                currentAgentAddress = agentAddress
                hasConnectedThisAttempt = false
                // Belongs to the connection being replaced; a late subscriber
                // must not be handed the previous agent's transcript.
                _serverTranscript.value = null
                // Same reasoning: a reconnect (same agent restarting, or a
                // switch to a different one) must not leave the previous
                // AGENT_PROFILE's tools/skills on screen until the new
                // CONNECT's own AGENT_PROFILE frame arrives.
                _agentProfile.value = null
                // And the mode chip: it is a security-relevant control, so a
                // reconnect must not keep showing "accept edits" from the
                // agent we just left. Back to the server's own DEFAULT_MODE
                // until this connection's first mode_changed says otherwise.
                _approvalMode.value = ApprovalMode.DEFAULT
                confirmedApprovalMode = ApprovalMode.DEFAULT
                _modePending.value = false
                // A different Host, or the same one upgraded: what it manages
                // is unknown again until its CONNECTED says.
                _sessionModes.value = SessionModes.LEGACY
                _hostSessionStatus.value = ServerSessionStatus.UNKNOWN

                // Cleared alongside the profile, not just for staleness: the
                // dashboard's buttons are validated against _agentProfile's
                // skills, and leaving one behind without the other would
                // point the previous agent's Home at the new agent's
                // allowlist.
                _dashboardHtml.value = null
                _plan.value = emptyList()

                runCatchingCancellable {
                    connection?.disconnect()
                    eventCollectionJob?.cancel()

                    // Rows whose conversation never materialised or has since
                    // been deleted; connect() is a natural, infrequent
                    // boundary to clear them at — see SessionStateEntity.
                    sessionStore?.deleteOrphanedSessions()

                    // This conversation's own session, named by its own id —
                    // see sessionFor.
                    currentConversationId = conversationId
                    val restoredSession = sessionFor(conversationId)
                    // Shown before the socket opens: an idle session's CONNECTED
                    // carries no snapshot, so the stored plan is the only one.
                    showStoredPlan(restoredSession)

                    val newConnection = agentConnectionFactory(
                        keyManager ?: error("keyManager is required to connect"),
                        relayUrl
                    ).also { it.setSession(restoredSession) }
                    connection = newConnection

                    // Subscribe first, and wait until the subscription is
                    // live. events is replay=0, so a CONNECTED that lands
                    // before the collector attaches is gone for good — the
                    // session is never persisted and the state stays on
                    // Connecting. Opening the socket used to come first.
                    startCollectingEvents()
                    eventCollectionStarted?.await()

                    newConnection.connect(agentAddress, directUrl)
                }.onFailure { e ->
                    FileLogger.e(LogTags.CONN_REPO, "Connect failed: ${e.message}")
                    _connectionState.value = ConnectionState.Error.fromException(e)
                }
            }
        }
    }

    override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<OutgoingFileAttachment>?) {
        withContext(Dispatchers.IO) {
            // Domain -> wire-protocol mapping happens here, not in domain/
            // usecase — see OutgoingFileAttachment's doc.
            val wireFiles = files?.map { FileAttachment(name = it.name, data = it.data) }
            connection?.sendMessage(content, agentAddress, images, wireFiles)
        }
    }

    override suspend fun executeTool(request: ExecRequest): ExecOutcome =
        withContext(Dispatchers.IO) {
            val activeConnection = connection
                ?: return@withContext ExecOutcome.NotSent(
                    "There is no active agent connection"
                )

            if (request.agentAddress != currentAgentAddress) {
                return@withContext ExecOutcome.NotSent(
                    "The EXEC target is not the connected agent"
                )
            }

            if (request.execId.isBlank()) {
                return@withContext ExecOutcome.NotSent(
                    "The EXEC id cannot be blank"
                )
            }

            if (request.tool.isBlank()) {
                return@withContext ExecOutcome.NotSent(
                    "The EXEC tool cannot be blank"
                )
            }

            val parsedArgs = runCatching {
                Json.parseToJsonElement(request.argsJson)
            }.getOrNull()

            val args = parsedArgs as? JsonObject
                ?: return@withContext ExecOutcome.NotSent(
                    "EXEC arguments must be a JSON object"
                )

            activeConnection.executeTool(
                execId = request.execId,
                tool = request.tool,
                args = args,
                recipient = request.agentAddress
            )
        }

    override suspend fun interrupt() {
        withContext(Dispatchers.IO) {
            connection?.interrupt()
        }
    }

    override suspend fun respond(answer: String) {
        withContext(Dispatchers.IO) {
            connection?.respond(answer)
        }
    }

    override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {
        withContext(Dispatchers.IO) {
            connection?.respondToApproval(approved, scope, mode, feedback)
        }
    }

    override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {
        withContext(Dispatchers.IO) {
            connection?.respondToOnboard(method, inviteCode, payment)
        }
    }

    override suspend fun respondToPlanReview(message: String) {
        withContext(Dispatchers.IO) {
            connection?.respondToPlanReview(message)
        }
    }

    override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {
        withContext(Dispatchers.IO) {
            connection?.respondToUlwTurnsReached(action, turns, mode)
        }
    }

    override suspend fun setMode(mode: ApprovalMode, turns: Int?) {
        withContext(Dispatchers.IO) {
            val modes = _sessionModes.value
            // A Host that manages modes has no alias for the legacy names, and
            // a legacy agent knows nothing of the new ones: either would come
            // back as a refusal. Only what this connection offers goes out.
            if (mode !in modes.available) {
                FileLogger.w(LogTags.CONN_REPO, "setMode: ${mode.wire} is not offered here, not sent")
                return@withContext
            }
            // Optimistic before the send, not after the server confirms: a
            // trust control that lags a round trip behind the tap reads as
            // broken, and the inbound mode_changed corrects us either way.
            _approvalMode.value = mode
            _modePending.value = true
            // The Host grants full access's budget; no client field can.
            connection?.setMode(mode.wire, if (modes.hostManaged) null else turns)
        }
    }

    /**
     * An `ERROR` arrived while the Host was last known to be running a turn. A failed turn ends
     * with an `ERROR` instead of an `OUTPUT`, and the status line would otherwise say "Running"
     * until the next reconnect. The Host's own turn-ending errors ([ServerErrorText.endsTurn])
     * settle it to idle at once. Any `ERROR` here also asks `SESSION_STATUS`, whose reply has the
     * last word, so an error in a wording this client does not know still settles it, and one
     * that left the turn running (the reply says `running`) changes nothing.
     */
    private fun settleRunningStatusAfterError(message: String) {
        if (ServerErrorText.endsTurn(message)) {
            _hostSessionStatus.value = ServerSessionStatus.CONNECTED
        }
        val sessionId = connection?.currentSession?.sessionId ?: return
        connection?.querySessionStatus(sessionId)
    }

    /**
     * Adopt `session_modes` from CONNECTED as the confirmed truth: the catalogue, the current
     * mode and its budget. Clears a pending request, which that CONNECT outlived. An empty or
     * wholly unknown catalogue keeps the current mode as the only option rather than none.
     */
    private fun applyHostModes(state: HostModeState) {
        val current = state.current ?: ApprovalMode.AUTO
        _sessionModes.value = SessionModes(
            available = state.available.ifEmpty { listOf(current) },
            hostManaged = true,
            turnsLeft = state.turnsLeft?.takeIf { current.isUnattended }
        )
        _approvalMode.value = current
        confirmedApprovalMode = current
        _modePending.value = false
    }

    override fun retryNow() {
        connection?.retryNow()
    }

    override suspend fun querySessionStatus(sessionId: String) {
        withContext(Dispatchers.IO) {
            connection?.querySessionStatus(sessionId)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            connection?.currentSession?.let { saveSessionForCurrentConversation(it) }

            // Stop collecting before tearing down. Without this the collector
            // outlives the disconnect, and an event already in flight — the
            // socket's own Connected, say — lands afterwards and puts the
            // state back to Connected on a connection that no longer exists.
            // connect() has always cancelled it; this path never did.
            eventCollectionJob?.cancel()
            eventCollectionJob = null

            connection?.disconnect()
            connection = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun isConnected(): Boolean {
        return connection?.isConnected() == true
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            currentConversationId?.let { sessionStore?.deleteSessionByConversation(it) }
            connection?.reset()
        }
    }

    override suspend fun peekPersistedSessionId(conversationId: String): String? = withContext(Dispatchers.IO) {
        sessionStore?.getSession(conversationId)?.sessionId
    }

    override fun liveSessionIdFor(agentAddress: String): String? {
        if (agentAddress != currentAgentAddress || connection?.isConnected() != true) return null
        return connection?.currentSession?.sessionId
    }

    override fun getCurrentSession(): SessionState? {
        return connection?.currentSession
    }

    /**
     * Fills [agentProfile] from the relay's directory, because nothing ever
     * pushes an `AGENT_PROFILE` frame — see [AgentDiscoveryService.fetchPublishedProfile].
     *
     * A pushed frame still wins: this only writes when the slot is empty or
     * holds a profile with no skills, so if the relay ever starts sending one
     * this becomes a harmless prefetch rather than something to unwind.
     */
    private fun fetchPublishedProfile() {
        val discovery = agentDiscovery ?: return
        // Deliberately not ConnectionEvent.Connected.address: that field carries
        // the identity the relay authenticated — this device's own address, not
        // the agent's. Looking that up returns our own directory row, whose
        // profile is null, and the palette stays empty for a second reason.
        val address = currentAgentAddress ?: return
        scope.launch {
            val published = discovery.fetchPublishedProfile(address, relayUrl) ?: return@launch
            val existing = _agentProfile.value
            if (existing != null && existing.skills.isNotEmpty()) return@launch

            _agentProfile.value = AgentLiveProfile(
                sessionId = existing?.sessionId,
                name = existing?.name ?: published.alias,
                address = address,
                model = existing?.model ?: published.model,
                tools = existing?.tools?.takeIf { it.isNotEmpty() } ?: published.tools,
                skills = published.skills
                    .filter { it.name.isNotBlank() }
                    .map { AgentSkill(it.name, it.description?.trim()?.takeIf(String::isNotEmpty)) },
                balanceUsd = existing?.balanceUsd
            )
            FileLogger.i(
                LogTags.CONN_REPO,
                "→ Published profile: ${published.skills.size} skill(s) from the relay directory"
            )
        }
    }

    companion object {
        /**
         * How long frame-driven session writes are conflated for. Long enough
         * that a turn's session_sync burst collapses to a handful of writes,
         * short enough that the row is never far behind what is on screen.
         */
        internal const val SESSION_SAVE_WINDOW_MS = 1_000L
    }
}
