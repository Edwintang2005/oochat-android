package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.protocol.toDomain
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.usecase.ServerErrorText
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.InMemoryPendingMessageSink
import ai.openonion.oochat.network.PendingMessageSink
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val agentConnectionFactory: (KeyManager, String) -> AgentConnection = { km, url ->
        AgentConnection(km, url, pendingMessageSink = pendingMessageSink)
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

    // Same lifetime and the same reset as _agentProfile above — a Home page
    // and the skill list its buttons are validated against have to belong to
    // the same connection, or a button would be checked against the wrong
    // agent's allowlist.
    private val _dashboardHtml = MutableStateFlow<String?>(null)
    override val dashboardHtml: StateFlow<String?> = _dashboardHtml.asStateFlow()

    // Long-lived broadcast bus for chat events. ChatViewModel.init subscribes to observeEvents() BEFORE connect() establishes the connection, so this hot bus survives the "not yet connected" window and forwards every event once a connection exists.
    private val _chatEvents = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = 64,
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
                        _connectionState.value = ConnectionState.Connected(
                            address = event.address,
                            session = event.session?.toDomain()
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
                        if (hasConnectedThisAttempt) {
                            FileLogger.eRepeating(LogTags.CONN_REPO, "→ Error (non-fatal, socket stays open): ${event.message}")
                            // The two windows a mode_change frame can miss its
                            // mark (session.py's active_io gate, or the
                            // before_iteration poll) both surface as this ERROR
                            // naming mode_change — see ConnectionRepository.modePending's
                            // doc. Roll the chip back rather than leave it
                            // claiming a mode the agent never adopted.
                            if (_modePending.value && event.message.contains("mode_change")) {
                                _approvalMode.value = confirmedApprovalMode
                                _modePending.value = false
                            }
                            _chatEvents.tryEmit(ChatEvent.ConnectionErrorOccurred(shown))
                        } else {
                            // Rate-limited: the reconnect ladder replays the
                            // same rejection once per rung.
                            FileLogger.eRepeating(LogTags.CONN_REPO, "→ Error (never connected this attempt): ${event.message}")
                            _connectionState.value = ConnectionState.Error(message = shown)
                        }
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
                    }
                    is ConnectionEvent.ChatItemReceived -> {
                        // The one frame that is both a chat entry and connection
                        // state. Applied for replayed snapshot items too: the
                        // server's session mode survives a dropped socket, so
                        // the last mode_changed in a replayed transcript is the
                        // mode we are actually resuming into.
                        (event.item as? ChatItem.ModeChangedItem)?.let { changed ->
                            ApprovalMode.fromWire(changed.mode)?.let {
                                _approvalMode.value = it
                                confirmedApprovalMode = it
                                _modePending.value = false
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
                        _chatEvents.tryEmit(ChatEvent.SessionStatusReceived(event.sessionId, event.status))
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
        // Mode is a property of the server session, so switching conversation
        // makes it unknown again for the same reason connect() does: a
        // security-relevant control must not keep showing the mode of the
        // conversation we just left, and a mode_change still awaiting
        // confirmation there is never going to be answered here.
        _approvalMode.value = ApprovalMode.DEFAULT
        confirmedApprovalMode = ApprovalMode.DEFAULT
        _modePending.value = false
        // Agent profile and dashboard deliberately survive: same agent, only
        // a different conversation with it.
        connection?.switchSession(sessionFor(conversationId))
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

                // Cleared alongside the profile, not just for staleness: the
                // dashboard's buttons are validated against _agentProfile's
                // skills, and leaving one behind without the other would
                // point the previous agent's Home at the new agent's
                // allowlist.
                _dashboardHtml.value = null

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
            // Optimistic before the send, not after the server confirms: a
            // trust control that lags a round trip behind the tap reads as
            // broken, and the inbound mode_changed corrects us either way.
            _approvalMode.value = mode
            _modePending.value = true
            connection?.setMode(mode.wire, turns)
        }
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
