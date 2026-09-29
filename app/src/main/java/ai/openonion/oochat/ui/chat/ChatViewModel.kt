package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.ConnectOnionApplication
import ai.openonion.oochat.Constants
import ai.openonion.oochat.data.local.AgentReplyNotifier
import ai.openonion.oochat.data.local.IgnoredIdsManager
import ai.openonion.oochat.data.local.toConnectTarget
import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.domain.model.AppError
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatEventReducer
import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatListFlags
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.domain.model.createError
import ai.openonion.oochat.domain.model.dedupeUI
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.domain.model.next
import ai.openonion.oochat.domain.model.resolveRunningItems
import ai.openonion.oochat.domain.model.sessionUsageTotals
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.domain.usecase.SessionResume
import ai.openonion.oochat.domain.usecase.StaleSessionDetector
import ai.openonion.oochat.network.OFFLINE_GRACE_MS
import ai.openonion.oochat.network.RecognizerReadiness
import ai.openonion.oochat.network.SpeechRecognitionEvent
import ai.openonion.oochat.network.offlineSustainedFor
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import ai.openonion.oochat.ui.chat.components.VoiceInputState
import ai.openonion.oochat.ui.common.launchScoped
import ai.openonion.oochat.ui.navigation.DrawerAgentSection
import ai.openonion.oochat.ui.navigation.DrawerEntry
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogSanitizer
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long an unproven recognizer gets to say anything at all before the
 * dictation moves to server transcription. A working one answers in well
 * under a second even on a cold bind; the cost of guessing wrong is a whole
 * session on the slower path, and the cost of waiting is the opening words of
 * one dictation, paid once per process.
 */
internal const val RECOGNIZER_PROBE_TIMEOUT_MS = 2_500L

/**
 * The same wait for a recognizer that answered READY. Shorter, because one
 * that vouches for itself should answer sooner — but not much: the worst cold
 * start measured here is ~1.7s of service bring-up before the first callback,
 * and a slow recognizer that works must not be cut off mid-bind. Healthy ones
 * answer in 235-330ms, so only a lying one ever pays this.
 */
internal const val RECOGNIZER_READY_TIMEOUT_MS = 2_000L

/**
 * How many chat items stay in memory. Beyond this the oldest are dropped and
 * re-read from Room if the reader scrolls back up to them — the transcript is
 * the expensive thing here (one tool call's `result` can hold a whole file),
 * and Room, not this list, is the durable copy.
 *
 * Comfortably more than [ai.openonion.oochat.domain.usecase.CONVERSATION_PAGE_SIZE]
 * so a resumed conversation plus a page of older messages never trips it.
 */
internal const val MAX_RETAINED_CHAT_ITEMS = 500

data class ChatUiState(
    val chatItems: List<ChatItem> = emptyList(),
    val error: AppError? = null,
    val myAddress: String = ""
) {
    /**
     * Everything the derived flows below need to know about [chatItems], from
     * one walk shared between them.
     *
     * Lazy so a state nobody asks about never pays for it, and cached on the
     * instance so the four consumers of it walk the list once between them
     * rather than once each. Not a constructor property: it is derived, and
     * keeping it out means `equals` stays the cheap identity-first comparison
     * the reducer's same-list-instance contract relies on.
     */
    val listFlags: ChatListFlags by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ChatListFlags.of(chatItems)
    }
}

/**
 * @JvmOverloads so the AndroidX default
 * [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory] can still
 * locate a `(Application)` constructor via reflection. Tests construct
 * the same class with explicit fakes for each dependency.
 *
 * Deliberately never auto-connects on its own in `init` — see
 * [connectToAgentByConfig]'s doc for why that used to be here and isn't
 * anymore (a real race with an explicit [connectToAgent] call from the
 * caller, e.g. the drawer's "+ new chat with a different agent"). The
 * caller (ChatScreen's `connectToAgentId`-keyed LaunchedEffect) is the only
 * place that decides which agent this ViewModel should connect to, mirroring
 * the reference web client's URL-is-the-only-source-of-truth design
 * (`useAgentSDK({ agentAddress, sessionId })` keyed directly off the route).
 *

 * Default values build the production wiring from [application]. When a
 * test provides a fake for any parameter, the matching default expression
 * is not evaluated, so tests never hit EncryptedSharedPreferences / Room /
 * DataStore / Android Context APIs.
 */
open class ChatViewModel @JvmOverloads constructor(
    application: Application,
    // Kept as a constructor parameter so connectToAgent() below routes
    // through the same instance in production and tests. Tests pass a real
    // instance — for a valid hex address it returns synchronously, no HTTP.
    private val agentDiscovery: ai.openonion.oochat.network.AgentDiscoveryService =
        application.appContainer.agentDiscovery,
    // Default-value expressions below pull from the app-scoped AppContainer
    // instead of each re-deriving AppDatabase.getInstance(application) from
    // scratch — the container builds each repository once and hands out the
    // same instance everywhere. Tests never evaluate these defaults; they
    // pass fakes directly, so this container access never runs in tests.
    private val connectUseCase: ConnectToAgentUseCaseContract =
        application.appContainer.connectToAgentUseCase,
    private val agentRepository: ai.openonion.oochat.data.repository.AgentRepository =
        application.appContainer.agentRepository,
    private val configRepository: ai.openonion.oochat.data.repository.ConnectionConfigRepository =
        application.appContainer.configRepository,
    private val ignoredIdsManager: IgnoredIdsManager =
        IgnoredIdsManager(application.appContainer.ignoredIdsStorage),
    private val imageAttachmentStore: ai.openonion.oochat.data.local.ImageAttachmentStore =
        application.appContainer.imageAttachmentStore,
    private val fileAttachmentStore: ai.openonion.oochat.data.local.FileAttachmentStore =
        application.appContainer.fileAttachmentStore,
    private val voiceRecorderStore: ai.openonion.oochat.data.local.VoiceRecorderStore =
        application.appContainer.voiceRecorderStore,
    private val voiceTranscriptionService: ai.openonion.oochat.network.VoiceTranscriptionService =
        application.appContainer.voiceTranscriptionService,
    private val speechRecognitionService: ai.openonion.oochat.network.SpeechRecognitionService =
        application.appContainer.speechRecognitionService,
    private val appSettings: ai.openonion.oochat.data.local.AppSettings =
        application.appContainer.appSettings,
    // Only used to gate the haptics/sound "agent reply arrived" signal below
    // on genuinely-new content (see agentReplyArrived) — everything else
    // routes message persistence through conversationHistory instead.
    private val messageRepository: ai.openonion.oochat.data.repository.MessageRepository =
        application.appContainer.messageRepository,
    // Persisted conversation history (drawer session list).
    private val conversationHistory: ConversationHistoryUseCase = ConversationHistoryUseCase(
        application.appContainer.agentRepository,
        application.appContainer.sessionRepository,
        application.appContainer.messageRepository,
        application.appContainer.persistenceTransaction,
        // So a first conversation adopts the session the Loading probe's
        // connection already holds instead of minting a rival one.
        liveSessionId = { connectUseCase.liveSessionIdFor(it) }
    ),
    // Deliberately NOT defaulted to keyManager.loadOrGenerate(): a constructor
    // default is evaluated wherever the ViewModel is constructed, which for
    // Compose is the main thread during composition — and that call builds an
    // EncryptedSharedPreferences, then on a fresh install runs BIP39 PBKDF2
    // (2048 rounds) plus two encrypted writes. null means "resolve it on IO in
    // init"; tests pass a literal so they never touch KeyManager at all.
    initialMyAddress: String? = null,
    private val networkMonitor: ai.openonion.oochat.network.NetworkMonitor =
        application.appContainer.networkMonitor,
    // Posts the background "agent replied" system notification — see
    // maybeNotifyAgentReply's gating doc.
    private val agentReplyNotifier: AgentReplyNotifier =
        application.appContainer.agentReplyNotifier,
    // POST_NOTIFICATIONS is only a runtime permission from API 33 onward —
    // mirrors SettingsViewModel's own gate on the same permission.
    private val requiresNotificationPermission: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    private val hasNotificationPermission: () -> Boolean = {
        !requiresNotificationPermission ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
    // False (never notify) when application isn't a ConnectOnionApplication —
    // safer default than assuming background, and the only way tests reach
    // this constructor is with a bare Application(), which they override
    // this lambda past anyway to exercise both branches.
    private val isAppInForeground: () -> Boolean = {
        (application as? ConnectOnionApplication)?.isInForeground ?: true
    },
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ChatUiState())

    // The streaming toggle only filters in-progress items from what's
    // exposed; the wire protocol has no token-level streaming to actually
    // toggle. _uiState stays unfiltered so items reappear if re-enabled.
    // Eagerly shared because awaitingOnboardCode and tests read .value
    // synchronously.
    val uiState: StateFlow<ChatUiState> = combine(_uiState, appSettings.streamingResponses) { state, streaming ->
        // The `hasInProgress` check is not just an optimisation: with nothing
        // to filter, returning the state untouched hands the same list
        // instance downstream, which is what lets Compose skip the unchanged
        // bubbles. `filterNot` would have handed over a fresh N-element list
        // that compares equal but recomposes the lot.
        if (streaming || !state.listFlags.hasInProgress) state
        else state.copy(chatItems = state.chatItems.filterNot { it.isInProgress() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    // Display timestamps, session list, and active-session tracking now
    // live in ConversationHistoryUseCase (extracted — see its class doc);
    // exposed here as pass-through StateFlows so ChatScreen's existing
    // observers don't need to change.
    val itemTimestamps: StateFlow<Map<String, Long>> = conversationHistory.itemTimestamps
    val sessions: StateFlow<List<ChatSession>> = conversationHistory.sessions
    val activeSessionId: StateFlow<String?> = conversationHistory.activeSessionId

    /** Whether scrolling to the top of the transcript has older rows left to fetch. */
    val hasOlderMessages: StateFlow<Boolean> = conversationHistory.hasOlderMessages

    /**
     * Everything below is derived here rather than in ChatScreen's function
     * body on purpose. A single `uiState` read at that level puts the whole
     * screen in its invalidation set, so every llm_call / tool_call /
     * tool_result frame — dozens per agent turn — re-ran the entire body.
     * Handing the screen finished scalars keeps that read inside the child
     * scopes that actually need it.
     */
    val conversationTitle: StateFlow<String> = combine(sessions, activeSessionId) { list, id ->
        list.firstOrNull { it.id == id }?.title ?: "ConnectOnion"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "ConnectOnion")

    /**
     * A session whose title was already derived from a first message (i.e. no
     * longer the "New conversation" placeholder) but is now empty means the
     * user cleared it — distinct from a session that never had messages.
     */
    val wasCleared: StateFlow<Boolean> = combine(sessions, activeSessionId, uiState) { list, id, state ->
        val active = list.firstOrNull { it.id == id }
        active != null && active.title != "New conversation" && state.chatItems.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Session token/cost/context totals for the composer's usage label.
     * Derived from the public (streaming-filtered) [uiState], matching what
     * the screen used to walk — an in-flight turn's contextPercent counts
     * only while those items are actually shown.
     */
    val sessionUsage: StateFlow<SessionUsageTotals> = uiState
        .map { it.chatItems.sessionUsageTotals() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUsageTotals(0, 0.0, null))

    /**
     * Split out of [uiState] so the snackbar's `LaunchedEffect` key can be
     * read without subscribing the screen body to the chat list as well.
     */
    val error: StateFlow<AppError?> = uiState
        .map { it.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Connection state from use case (single source of truth)
    val connectionState: StateFlow<ConnectionState> = connectUseCase.connectionState

    // The connected agent's own name card (model/tools/skills) — null until
    // its AGENT_PROFILE frame arrives, and cleared again on reconnect; see
    // ConnectionRepository.agentProfile's doc.
    val agentProfile: StateFlow<AgentLiveProfile?> = connectUseCase.agentProfile

    // The live connection's tool-approval mode, driving the input bar's mode
    // chip. Connection-scoped and reset on every connect — see
    // ConnectionRepository.approvalMode's doc.
    val approvalMode: StateFlow<ApprovalMode> = connectUseCase.approvalMode

    // Whether approvalMode is confirmed by the agent yet, or still just what
    // we asked for — see ConnectionRepository.modePending's doc.
    val modePending: StateFlow<Boolean> = connectUseCase.modePending

    // The agent's own Home page, or null when it hasn't sent one for this
    // connection — which is also what gates the top bar's entry point, since
    // nothing on the wire says "this agent has no dashboard". Untrusted
    // agent-authored HTML; see ConnectionRepository.dashboardHtml.
    val dashboardHtml: StateFlow<String?> = connectUseCase.dashboardHtml

    // Settings' "Custom instructions" free text — silently prepended to
    // every outgoing prompt in sendMessage(), never to the displayed/
    // persisted content. Eagerly shared (not WhileSubscribed) so the very
    // first sendMessage() call after construction already has the current
    // value instead of racing collection start.
    private val customInstructions: StateFlow<String> = appSettings.customInstructions
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Fires once per genuinely-new agent reply (never on session_sync replay
    // of history already in Room) — ChatScreen collects this to trigger
    // haptics/sound for Settings' toggles of the same name. Buffered with
    // DROP_OLDEST so a slow/absent collector (e.g. screen not yet composed)
    // can never suspend the event-collector loop below.
    private val _agentReplyArrived = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val agentReplyArrived: kotlinx.coroutines.flow.SharedFlow<Unit> = _agentReplyArrived

    // Derived states for UI convenience
    val isConnected: StateFlow<Boolean> = connectionState
        .map { it is ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isLoading: StateFlow<Boolean> = connectionState
        .map { it is ConnectionState.Connecting || it is ConnectionState.Reconnecting }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // The device has had no network for longer than the grace period. Kept
    // separate from [connectionState] rather than folded into it: the socket
    // owns "can we reach the agent", this owns "is there a network at all",
    // and only the second is knowable while the radio is off. The banner is
    // where the two meet — see ConnectionBanner's `isOffline`.
    val isOffline: StateFlow<Boolean> = networkMonitor.isOnline
        .offlineSustainedFor(OFFLINE_GRACE_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // For OnboardGateCard's "is there a live socket to submit to" gate —
    // deliberately not [isConnected]: see ConnectionState.hasLiveConnection's
    // own doc for why the stricter Connected-only check is wrong here.
    val hasLiveConnection: StateFlow<Boolean> = connectionState
        .map { it.hasLiveConnection() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Whether the agent currently has a running turn/tool/intent/eval/
    // compact step — drives the input bar's Send↔Stop swap. Derived from
    // _uiState (not the public uiState above), which filters in-progress
    // items out entirely when "Streaming responses" is off — this must
    // reflect the real underlying work state regardless of that display
    // toggle. Eagerly (not WhileSubscribed, unlike isConnected/isLoading
    // above): interrupt() and tests both need .value to reflect the true
    // current state immediately, the same reason uiState itself is eager.
    val isAgentWorking: StateFlow<Boolean> = _uiState
        .map { it.listFlags.hasInProgress }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Chat-affecting user preferences, exposed as StateFlows so ChatScreen
    // has this ViewModel as its single state source instead of reaching into
    // appContainer.appSettings directly. Defaults mirror AppSettings' own.
    val renderMarkdown: StateFlow<Boolean> = appSettings.renderMarkdown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val fontSizeIndex: StateFlow<Int> = appSettings.fontSizeIndex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)
    val hapticFeedbackEnabled: StateFlow<Boolean> = appSettings.hapticFeedback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val soundEffectsEnabled: StateFlow<Boolean> = appSettings.soundEffects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Backing var kept for the many internal reads below (unchanged call
    // sites); mirrored into a StateFlow so NavDrawer's mini account card
    // can show which agent this session is connected to.
    private var _targetAgentAddress: String = ""
        set(value) {
            field = value
            _targetAgentAddressFlow.value = value.takeIf { it.isNotBlank() }
        }
    private val _targetAgentAddressFlow = MutableStateFlow<String?>(null)
    val targetAgentAddress: StateFlow<String?> = _targetAgentAddressFlow.asStateFlow()

    /**
     * Data source for the agent-grouped NavDrawer: every saved agent with
     * its own session list, plus a live [AgentStatus] — computed here (not
     * a new app-scoped connection registry) because this ChatViewModel is
     * the one live signal that actually exists: the agent currently being
     * talked to (if any) reflects [connectionState] in real time; every
     * other agent gets the same [AgentStatus.Active]/[AgentStatus.Disabled]
     * (from [ai.openonion.oochat.domain.model.AgentProfile.isActive])
     * AgentListScreen's own StatusBadge already falls back to, since only
     * one agent can ever be genuinely connected at a time (see
     * AppContainer.connectToAgentUseCase's doc comment).
     *
     * Emits the drawer's own [DrawerAgentSection] shape, not the Room-backed
     * [ChatSession]: every persisted message rewrites that row's updatedAt,
     * messageCount and lastMessagePreview, so a list carrying them is never
     * equal to the previous one and `stateIn`'s equality conflation can never
     * fire. Narrowed to the id + title the drawer actually draws, an ordinary
     * turn leaves this flow silent and NavDrawer keeps skipping.
     */
    val drawerAgents: StateFlow<List<DrawerAgentSection>> = combine(
        conversationHistory.observeAllAgentSessions(),
        connectionState,
        targetAgentAddress
    ) { agentSessions, state, targetAddress ->
        agentSessions.map { (agent, sessions) ->
            val status = if (targetAddress != null && agent.address == targetAddress) {
                when (state) {
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> AgentStatus.Connecting
                    is ConnectionState.Connected -> AgentStatus.Connected
                    is ConnectionState.Error -> AgentStatus.Error(state.message)
                    else -> if (agent.isActive) AgentStatus.Active else AgentStatus.Disabled
                }
            } else {
                if (agent.isActive) AgentStatus.Active else AgentStatus.Disabled
            }
            DrawerAgentSection(
                agentId = agent.id,
                agentAddress = agent.address,
                name = agent.name,
                status = status,
                sessions = sessions.map { DrawerEntry(id = it.id, title = it.title) }
            )
        }
        // Eagerly (not WhileSubscribed): must reflect the true agent/status
        // list even before ChatScreen's collectAsState() attaches — same
        // reasoning as awaitingOnboardCode above.
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Set true by markOnboardPending() (the caller already knows, e.g. from
    // LoadingScreen's own onboarding-required outcome, that this agent needs
    // a code) until a definitive Connected/Error lands. Only covers the brief
    // window before the real ONBOARD_REQUIRED event (and its chat card)
    // arrives — see [awaitingOnboardCode] for the full, ongoing signal.
    private val _awaitingOnboardCode = MutableStateFlow(false)

    /**
     * True while markOnboardPending flagged it, or an unanswered
     * OnboardRequired/OnboardingFailed card exists — keeps banner copy stable
     * through the failed→re-prompted swap, and covers agents that never send
     * CONNECTED during onboarding (so connectionState alone can't signal this).
     */
    val awaitingOnboardCode: StateFlow<Boolean> = combine(_awaitingOnboardCode, uiState) { flagged, state ->
        flagged || (state.listFlags.hasOnboardPrompt && !state.listFlags.hasOnboardSuccess)
        // Eagerly (not WhileSubscribed): must reflect the true state even
        // before ChatScreen's collectAsState() attaches (and in tests that
        // read .value directly without collecting), not just while the UI
        // happens to be actively observing it.
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The conversation is parked on something only the user can answer, so the
     * run cannot continue until they do. Read by the agent's Home page, which
     * replaces the chat entirely: a gate opened while the reader is over there
     * is otherwise invisible, and the turn simply stalls with nothing saying
     * why. Onboarding is excluded — [awaitingOnboardCode] already owns it and
     * its own card blocks the chat before a Home page can be reached.
     */
    val chatAwaitsUser: StateFlow<Boolean> = uiState
        .map { it.listFlags.hasPendingGate }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Call once from ChatScreen when navigated here with onboarding already known to be required. */
    fun markOnboardPending() {
        _awaitingOnboardCode.value = true
    }

    // Set by ChatScreen (see its own call site) synchronously, before the
    // connect attempt is even issued — read and cleared once by whichever
    // of connectToAgent's pre-socket restore or the Connected handler gets
    // there first, so it applies to exactly one connect and later reconnects
    // (blips, manual "Reconnect") resume normally instead of re-discarding
    // an active conversation. See ConversationHistoryUseCase.ensureActiveSession's
    // forceNew doc for why this is a plain field read here rather than a
    // second call racing ensureActiveSession on its own coroutine.
    private var startFreshOnNextConnect = false

    /** Call once, before connecting, to start a fresh session instead of resuming this agent's most recent one. */
    fun markStartNewSessionOnConnect() {
        startFreshOnNextConnect = true
    }

    /**
     * The in-flight connect, so a newer target supersedes an older one
     * instead of racing it.
     *
     * Both entry points ([connectToAgent] / [connectToAgentByConfig]) run on
     * [viewModelScope], not on the caller's. ChatScreen drives them from a
     * `LaunchedEffect(connectToAgentId)`, so navigating to a different agent
     * (e.g. tapping another agent's session in the drawer while a
     * resume-from-background connect is still resolving) cancels the *effect*
     * but left the previous connect running to completion. Both then reached
     * ConnectionRepositoryImpl.connect, whose `connectMutex` serializes the
     * assignment but not the socket each AgentConnection has already opened —
     * hence two live WebSockets to two different agents, one of which
     * immediately got disconnected out from under whatever it had already
     * received. Cancelling here is what makes "latest target wins" true all
     * the way down.
     */
    private var connectJob: Job? = null

    /**
     * Blocking cards the user has already answered, so [ChatEventReducer] can
     * stop pinning them to the bottom of the list.
     *
     * Lives here rather than on [ChatItem] because "did the user tap a button"
     * is session-scoped UI state, not something the server sends or that's
     * worth persisting: a gate replayed from history on a later launch is
     * already stale, and pinning only matters while the conversation is live.
     * Not cleared on session switch — ids are unique per gate, so a stale
     * entry can never match a different card.
     */
    private val resolvedGateIds = mutableSetOf<String>()

    /**
     * Called when the user taps any button on a blocking card, so the gate
     * stops being held at the bottom on the *next* append. The list is not
     * reordered retroactively — moving a card the user is currently looking
     * at would be more jarring than leaving it where it is.
     */
    fun markGateResolved(itemId: String) {
        resolvedGateIds.add(itemId)
    }

    /**
     * Ids of Thinking items [failRunningThinking] force-failed from a
     * [ConnectionState.Disconnected] transition — see its `fromDisconnect`
     * doc. Cleared once the SESSION_STATUS reply that resolves them arrives,
     * whichever way it goes. Declared here (before [init]) rather than next
     * to [failRunningThinking]/[unfailDisconnectedItems] below: the `init`
     * block's connectionState collector runs eagerly under an unconfined
     * test dispatcher and can observe this field before a later-declared
     * property's initializer would have run, reading a not-yet-initialized
     * `null` instead of the default empty set.
     */
    private var disconnectFailedIds: Set<String> = emptySet()

    /** The local conversation [disconnectFailedIds] were flipped in, so a
     * later revert doesn't apply to whatever conversation is on screen by
     * then if the user has since switched away. */
    private var disconnectFailedSessionId: String? = null

    /**
     * True from the moment an INPUT goes out until the OUTPUT that answers it.
     *
     * Not [isAgentWorking]: that tracks the in-progress items on screen, and
     * they clear at `llm_result` — measured about three seconds before the run
     * actually ended. A message released into that gap is taken as runtime
     * input, and a turn that runs no tools never picks those up.
     *
     * Declared above [init], not beside the send path that owns it: the
     * connection-state collector started there clears this on its very first
     * emission, and a property declared further down the file is still null
     * at that point.
     */
    private val serverTurnActive = MutableStateFlow(false)

    init {
        // Initial state stays ChatUiState's own default (""), so there is
        // nothing to flash or reflow when the real address lands a few
        // milliseconds later. Resolving it on IO also dodges the `by lazy`
        // SYNCHRONIZED monitor: if the splash's warm-up is still in flight,
        // this coroutine parks on an IO thread rather than the main one.
        if (initialMyAddress != null) {
            _uiState.update { it.copy(myAddress = initialMyAddress) }
        } else {
            viewModelScope.launch {
                val shortAddress = withContext(Dispatchers.IO) {
                    runCatchingCancellable {
                        getApplication<Application>().appContainer.keyManager.loadOrGenerate().shortAddress
                    }.getOrNull()
                }
                if (shortAddress != null) {
                    _uiState.update { it.copy(myAddress = shortAddress) }
                }
            }
        }

        // The network came back. Same reasoning as [onReturnedToForeground]:
        // the reason the socket failed has just gone away, so the rest of a
        // backoff computed for a flaky network is dead time the user spends
        // watching a stale banner. retryNow() is a no-op unless a reconnect is
        // already pending, so this can't start one on a healthy connection —
        // the same guard the web client puts on its own `online` listener.
        viewModelScope.launch {
            var wasOnline = true
            networkMonitor.isOnline.collect { online ->
                if (online && !wasOnline) connectUseCase.retryNow()
                wasOnline = online
            }
        }

        // Collect connection state changes ONCE
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        FileLogger.i(LogTags.CHAT_VM, "Connected to ${state.addressOrNull()?.take(16)}")
                        // The shared connection is often already Connected when
                        // this ViewModel is constructed (LoadingScreen's probe
                        // opened it), i.e. before ChatScreen has said which
                        // agent we're for — connectToAgent() runs this itself
                        // once the target is known.
                        if (_targetAgentAddress.isNotBlank()) onConnectedTo(_targetAgentAddress, null)
                        // Only fires when the previous Disconnected transition
                        // force-failed a still-in-flight Thinking item (see
                        // failRunningThinking's fromDisconnect branch) — a
                        // fresh connect has nothing to correct. The reply is
                        // handled asynchronously where ChatEvent.SessionStatusReceived
                        // arrives, below.
                        if (disconnectFailedIds.isNotEmpty()) {
                            launchScoped { connectUseCase.querySessionStatus() }
                        }
                    }
                    is ConnectionState.Error -> {
                        FileLogger.e(LogTags.CHAT_VM, "Connection error: ${state.message}")
                        // ConnectionState.Error means transport/connect failure,
                        // not a server-sent ERROR frame (those route to
                        // ChatEvent.ConnectionErrorOccurred instead).
                        // Stale-session errors auto-recovery is invisible, so
                        // skip persisting the transient error.
                        if (!StaleSessionDetector.isStaleSessionError(state.message)) {
                            if (!failRunningThinking(content = state.message)) {
                                appendStandaloneError(state.message)
                            }
                        }
                        _awaitingOnboardCode.value = false
                    }
                    // Reconnecting belongs here too. An auto-reconnect never
                    // passes through Disconnected, so a turn interrupted by a
                    // dropped socket used to reach none of this: it stayed
                    // "Thinking…" for good, and serverTurnActive — cleared
                    // only where OUTPUT arrives — held every later message
                    // behind a turn that had already ended. Observed on a
                    // Huawei that kills the socket seconds after the app is
                    // backgrounded.
                    is ConnectionState.Disconnected, is ConnectionState.Reconnecting -> {
                        inFlightTurnOwner = null
                        failRunningThinking(fromDisconnect = true)
                        // The reply owed to the old socket is not coming back
                        // on the new one. Reopening the gate can at worst send
                        // into a turn the server is still running, which the
                        // server merges; leaving it shut hangs the composer.
                        serverTurnActive.value = false
                    }
                    else -> {}
                }
            }
        }

        // Collect chat events ONCE. Event-to-chat-list folding lives in
        // ChatEventReducer (extracted — see its class doc); this collector
        // just applies the result and persists whatever it says to.
        viewModelScope.launch {
            // Load ignoredIds before we start collecting — same coroutine,
            // so this always finishes before the first event can arrive.
            ignoredIdsManager.loadAll()

            // runCatchingCancellable (not a bare try/catch(Exception)) so
            // cancelling this coroutine — e.g. the ViewModel being cleared
            // while an event is in flight — propagates instead of being
            // reported as a "Connection lost" error on a screen that's
            // already on its way out.
            runCatchingCancellable {
                connectUseCase.observeEvents().collect { event ->
                    // Every conversation has its own server session, so a
                    // session_sync replays only this conversation's history —
                    // no attribution needed. What still needs routing is the
                    // switch window: a reply to a turn issued in the chat the
                    // user has just left (see inFlightTurnOwner).
                    val owner = owningConversationOf(event)
                    // OUTPUT means this run is over. Cleared here, not on the
                    // first persistable item of the turn: a turn emits several
                    // (session_sync, llm_call, llm_result) before its answer,
                    // and clearing early left that answer with no owner at all.
                    // A turn is over once its answer lands (OUTPUT) or the
                    // server gives up on it (an ERROR frame). Cleared here, not
                    // on the first persistable item: a turn emits several
                    // (session_sync, llm_call, llm_result) before its answer,
                    // and clearing early left that answer with no owner at all.
                    if (event is ChatEvent.OutputReceived ||
                        event is ChatEvent.ConnectionErrorOccurred
                    ) {
                        inFlightTurnOwner = null
                    }
                    if (owner != null && owner != conversationHistory.activeSessionId.value) {
                        val offscreen = ChatEventReducer.reduce(
                            emptyList(),
                            event,
                            ignoredIdsManager.ignoredIdsFor(_targetAgentAddress),
                            resolvedGateIds
                        )
                        offscreen.itemToPersist?.let { item ->
                            conversationHistory.persistMessage(item, owner)
                        }
                        return@collect
                    }

                    val ignoredIds = ignoredIdsManager.ignoredIdsFor(_targetAgentAddress)
                    val result = ChatEventReducer.reduce(_uiState.value.chatItems, event, ignoredIds, resolvedGateIds)
                    if (result.chatItems !== _uiState.value.chatItems) {
                        val retained = trimToRetentionWindow(result.chatItems)
                        _uiState.update { it.copy(chatItems = retained) }
                    }
                    result.itemToPersist?.let { item ->
                        // Checked BEFORE persisting — session_sync replays
                        // the same content-bearing items on every
                        // reconnect/cold-start, and by the time an id is in
                        // Room it must no longer trigger haptics/sound.
                        val hasNewAgentContent = when (item) {
                            is ChatItem.Agent -> item.content.isNotBlank()
                            is ChatItem.Turn -> item.agent?.content?.isNotBlank() == true
                            else -> false
                        } && !messageRepository.existsById(item.id)
                        conversationHistory.persistMessage(item)
                        if (hasNewAgentContent) {
                            _agentReplyArrived.tryEmit(Unit)
                            maybeNotifyAgentReply(item)
                        }
                    }

                    // Released here, not where inFlightTurnOwner is cleared
                    // above: a message waiting on this turn wakes the moment
                    // this flips, and the row it then writes is timestamped.
                    // Flipping it before this turn's own reply was persisted
                    // dated the waiting message 32ms *ahead* of the answer it
                    // was waiting for — measured on device.
                    if (event is ChatEvent.OutputReceived ||
                        event is ChatEvent.ConnectionErrorOccurred
                    ) {
                        serverTurnActive.value = false
                    }
                    if (event is ChatEvent.ServerTranscriptReceived) {
                        // The reducer deliberately no-ops on this; only the
                        // resumed session's own rows can say what's new.
                        if (conversationHistory.activeSessionId.value == null) {
                            // Held, not dropped. The transcript is sticky and
                            // replayed to whoever subscribes, so a ViewModel
                            // joining a live connection can receive it before
                            // it has resolved which conversation it is on —
                            // and a StateFlow will not emit the same value
                            // twice, so a merge that no-ops here is the last
                            // chance this replay ever gets.
                            pendingServerTranscript = event
                        } else {
                            applyServerTranscript(event)
                        }
                    }
                    if (event is ChatEvent.SessionStatusReceived) {
                        // Only "running" means the turn we force-failed is
                        // genuinely still in flight — "connected" means the
                        // server already finished it (a lost OUTPUT is a
                        // separate recovery problem this doesn't attempt to
                        // solve) and "not_found" means the failed mark was
                        // correct. Scoped to the conversation the flip
                        // happened in: if the user has since switched away,
                        // leave the persisted record alone rather than
                        // reaching into an off-screen chat.
                        val ids = disconnectFailedIds
                        val sameConversation =
                            conversationHistory.activeSessionId.value == disconnectFailedSessionId
                        if (ids.isNotEmpty() && sameConversation) {
                            when (event.status) {
                                "running" -> unfailDisconnectedItems(ids)
                                // The run ended while the socket was away. If
                                // the replay that just merged carries its
                                // answer, the failed mark was a guess made at
                                // disconnect time and is now demonstrably
                                // wrong — the user would see "Failed" sitting
                                // directly above the reply it claims never
                                // arrived.
                                "connected" -> resolveAnsweredDisconnectedItems(ids)
                            }
                        }
                        disconnectFailedIds = emptySet()
                        disconnectFailedSessionId = null
                    }
                }
            }.onFailure { e ->
                FileLogger.e(LogTags.CHAT_VM, "Event collection failed: ${e.message}")
                _uiState.update { it.copy(error = createError("Connection lost: ${e.message}")) }
            }
        }
    }

    /**
     * Posts the background reply notification, gated on all three holding:
     * the app is backgrounded (nothing else would ever see it otherwise —
     * agentReplyArrived above already covers the foregrounded case via
     * haptics/sound), the user opted in, and the OS permission is actually
     * granted. Suspends only to read [appSettings] — no coroutine is spun up
     * for this, it runs on the same event-collector coroutine as the rest
     * of the event handling above.
     */
    private suspend fun maybeNotifyAgentReply(item: ChatItem) {
        if (isAppInForeground() || !hasNotificationPermission()) return
        if (!appSettings.pushNotificationsEnabled.first()) return
        val preview = when (item) {
            is ChatItem.Agent -> item.content
            is ChatItem.Turn -> item.agent?.content.orEmpty()
            else -> return
        }
        agentReplyNotifier.notifyAgentReply(agentProfile.value?.name, preview)
    }

    /**
     * The caller (ChatScreen's connectToAgentId-keyed effect) is the sole
     * decision point for which agent to connect to — this must never also
     * auto-connect from init, or two independent connects can race for the
     * same underlying socket.
     */
    fun connectToAgentByConfig() {
        connectJob?.cancel()
        connectJob = launchScoped {
            val config = configRepository.getConfig()
            if (config != null && config.isValid && !config.agentAddress.isNullOrBlank()) {
                val target = config.toConnectTarget()
                FileLogger.i(LogTags.CHAT_VM, "Auto-connect: agent=${target.agentAddress.take(16)}, direct=${target.isDirect}")
                connectToAgent(
                    address = target.agentAddress,
                    directUrl = target.directUrl
                )
            } else {
                // Reachable after Settings -> "Clear connection data": Reconnect
                // otherwise retried this silently forever with no feedback.
                _uiState.update {
                    it.copy(error = createError("No agent configured. Add one in Settings to connect."))
                }
            }
        }
    }

    /**
     * Connect to an agent by address or URL. Falls back to the per-agent
     * saved serverUrl (not just the default relay) when directUrl isn't
     * passed, mirroring toConnectTarget's default-vs-direct check.
     */
    fun connectToAgent(address: String, directUrl: String? = null) {
        if (address.isBlank()) {
            FileLogger.w(LogTags.CHAT_VM, "Connect attempt with blank address")
            return
        }

        _uiState.update { it.copy(error = null) }

        connectJob?.cancel()
        connectJob = launchScoped {
            val savedServerUrl = directUrl
                ?: agentRepository.getAgentByAddress(address)?.serverUrl
                    ?.takeIf { !Constants.isDefaultServerUrl(it) }

            // Resolve address via discovery before connecting
            val resolved = agentDiscovery.resolveAgentAddress(address)
            val previousAddress = _targetAgentAddress
            _targetAgentAddress = resolved.address
            val effectiveDirectUrl = resolved.directUrl ?: savedServerUrl
            FileLogger.i(LogTags.CHAT_VM, "Resolved: ${resolved.address.take(16)}..., direct=${effectiveDirectUrl != null}")

            // Local history first, and awaited: an unreachable server must
            // still show the previous conversation, and resolving the session
            // before the socket opens means the CONNECTED transcript is
            // reconciled against the right conversation instead of racing it.
            val forceNew = startFreshOnNextConnect
            startFreshOnNextConnect = false
            // Leaving a conversation abandons whatever turn is still running in
            // it. Connection state goes Connecting -> Connected here, never
            // Error/Disconnected, so nothing else resolves that item and the
            // persisted "Thinking…" comes back spinning when the user returns.
            if (previousAddress.isNotBlank() && (previousAddress != resolved.address || forceNew)) {
                failRunningThinking()
            }
            restoreLocalSession(resolved.address, forceNew)

            // Perform connection, on this conversation's own server session.
            // Its id is settled even before it has a Room row, so a brand-new
            // conversation still connects on a session of its own.
            val success = connectUseCase.connect(
                resolved.address,
                conversationHistory.conversationId,
                effectiveDirectUrl
            )
            if (success) {
                // Also run here, not only from the state collector: joining an
                // already-live shared connection re-emits no Connected state.
                onConnectedTo(resolved.address, effectiveDirectUrl)
            } else {
                FileLogger.e(LogTags.CHAT_VM, "Connection failed or timed out")
            }
        }
    }

    /**
     * Idempotent "we are on [agentAddress] now" work: make sure the agent has
     * a Room row, then resolve and render its conversation. Reached from both
     * the Connected transition and [connectToAgent], since with one shared
     * connection only one of the two fires on any given path.
     */
    private suspend fun onConnectedTo(agentAddress: String, directUrl: String?) {
        saveAgentIfNeeded(agentAddress, directUrl)
        val forceNew = startFreshOnNextConnect
        startFreshOnNextConnect = false
        restoreLocalSession(agentAddress, forceNew)
        _awaitingOnboardCode.value = false
    }

    /**
     * Resolve which conversation [agentAddress] is on and render its
     * persisted rows. Resuming the previous conversation is the default;
     * [forceNew] is the explicit "start fresh" action, which clears the list
     * instead. Anything already on screen is kept ahead of nothing being
     * lost when this runs a second time from the Connected handler.
     */
    private suspend fun restoreLocalSession(agentAddress: String, forceNew: Boolean) {
        when (val resume = conversationHistory.ensureActiveSession(agentAddress, viewModelScope, forceNew)) {
            is SessionResume.Unchanged -> {}
            is SessionResume.Started -> _uiState.update { it.copy(chatItems = emptyList()) }
            is SessionResume.Resumed -> {
                val merged = trimToRetentionWindow(
                    (resume.items + _uiState.value.chatItems).dedupeUI()
                )
                _uiState.update { it.copy(chatItems = merged) }
            }
        }
        // Now that a conversation is resolved, a replay that landed before it
        // finally has somewhere to go.
        if (conversationHistory.activeSessionId.value != null) {
            pendingServerTranscript?.let {
                pendingServerTranscript = null
                applyServerTranscript(it)
            }
        }
    }

    /**
     * A replayed transcript that arrived before this screen knew which
     * conversation it was on; applied by [restoreLocalSession] once it does.
     */
    private var pendingServerTranscript: ChatEvent.ServerTranscriptReceived? = null

    private var loadOlderJob: Job? = null

    /**
     * Prepend the page of history immediately before what's on screen. Driven
     * by the transcript reaching (or nearly reaching) its top.
     *
     * Re-entrant calls are dropped rather than queued: the scroll prefetch can
     * fire several times while one read is in flight, and each extra one would
     * page past what the reader asked for.
     */
    fun loadOlderMessages() {
        if (loadOlderJob?.isActive == true) return
        loadOlderJob = launchScoped {
            val older = conversationHistory.loadOlderItems()
            if (older.isEmpty()) return@launchScoped
            _uiState.update { it.copy(chatItems = (older + it.chatItems).dedupeUI()) }
        }
    }

    /**
     * Hold the visible list to [MAX_RETAINED_CHAT_ITEMS] by dropping from the
     * front, and tell [conversationHistory] how far its window moved so those
     * rows page back in if the reader scrolls up to them.
     *
     * Only applied where the list grows at the tail. Trimming a list that just
     * grew at the *front* would undo the page the reader explicitly asked for.
     */
    private fun trimToRetentionWindow(items: List<ChatItem>): List<ChatItem> {
        val overflow = items.size - MAX_RETAINED_CHAT_ITEMS
        if (overflow <= 0) return items
        conversationHistory.noteHeadDropped(overflow)
        return items.subList(overflow, items.size).toList()
    }

    /**
     * The conversation an outgoing message belongs to, with the connection
     * moved onto its session first.
     *
     * A conversation's Room row is created lazily by the message that first
     * needs it, so until this call nothing has told the connection it exists
     * — the socket is still on whatever session it last CONNECTed as, and an
     * INPUT sent now is filed there instead. Joining an already-live
     * connection is the ordinary way to arrive in that state.
     *
     * Ordering is the point, not just the switch: [switchConversation] drops
     * the connection's ready flag before it returns, so the INPUT that
     * follows is queued by AgentConnection and flushed when CONNECTED for
     * this session lands — never ahead of it. A no-op when the connection is
     * already here, which is every message after the first.
     */
    private suspend fun conversationForOutgoingMessage(): String? {
        val target = conversationHistory.resolveActiveSessionId()
        if (target != null) connectUseCase.switchConversation(target)
        return target
    }

    /** Merge a replayed transcript into the resolved conversation and render what was missing. */
    private suspend fun applyServerTranscript(event: ChatEvent.ServerTranscriptReceived) {
        val fetched = conversationHistory.mergeServerTranscript(event.entries, event.turn)
        if (fetched.isNotEmpty()) {
            _uiState.update { it.copy(chatItems = (it.chatItems + fetched).dedupeUI()) }
        }
        // Reconciled: whatever was missing is now in Room, so the connection
        // no longer has to keep a whole conversation's worth of replayed text
        // alive for a subscriber that has now arrived.
        connectUseCase.releaseServerTranscript()
    }

    /**
     * Run a synthetic chat event through the same reducer path the
     * observeEvents collector uses. Sharing the path keeps the
     * OnboardRequired → OnboardingFailed replacement atomic with the
     * rest of the chat-list mutations and avoids a second `_uiState`
     * update site that would otherwise need to duplicate reducer
     * semantics (replace-in-place for OnboardRequired/OnboardingFailed,
     * append + dedupe for everything else).
     *
     * Does NOT persist: synthetic OnboardingFailed is a transient
     * UI state machine move, not a server-emitted item we'd want
     * to write to Room and replay on next reconnect.
     */
    private fun reduceIncoming(event: ChatEvent) {
        val ignoredIds = ignoredIdsManager.ignoredIdsFor(_targetAgentAddress)
        val result = ChatEventReducer.reduce(_uiState.value.chatItems, event, ignoredIds, resolvedGateIds)
        if (result.chatItems !== _uiState.value.chatItems) {
            _uiState.update { it.copy(chatItems = result.chatItems) }
        }
    }

    /**
     * Flips any still-[ThinkingStatus.RUNNING] Thinking item (standalone or
     * nested in a [ChatItem.Turn]) to [ThinkingStatus.ERROR] when the
     * connection dies mid-request. Without this, a message sent right before
     * a [ConnectionState.Error]/[ConnectionState.Disconnected] leaves its
     * "Thinking…" bubble spinning forever — the top banner already tells the
     * user the connection is gone, but the message list itself stayed
     * silent, since nothing was ever resolving that item once no further
     * server events for it could arrive. [ThinkingStatus.ERROR] already has
     * full UI support (renders as "Failed" — see StatusBubbles/MessageTurnBubbles),
     * it just had no producer for this specific case.
     *
     * Persists each flipped item so a later app restart doesn't resurrect a
     * "Thinking…" that will never resolve.
     *
     * @param fromDisconnect true only from the [ConnectionState.Disconnected]
     *   collector branch — records [disconnectFailedIds] so a later
     *   successful reconnect can ask SESSION_STATUS whether this was a false
     *   alarm (the server thread survives a dropped socket — see
     *   [querySessionStatus]'s own reference) and, if so, undo it via
     *   [unfailDisconnectedItems]. Not set for the other call sites
     *   (startNewSession, switchToSession, connectToAgent's leaving-a-
     *   conversation guard): those are the user deliberately walking away,
     *   not a connectivity blip, so there is nothing to reconcile later.
     */
    private fun failRunningThinking(content: String? = null, persist: Boolean = true, fromDisconnect: Boolean = false): Boolean {
        val current = _uiState.value.chatItems
        val flipped = mutableListOf<ChatItem>()
        val updated = current.map { item ->
            when {
                item is ChatItem.Thinking && item.status == ThinkingStatus.RUNNING -> {
                    val failedItem = item.copy(status = ThinkingStatus.ERROR, content = content ?: item.content)
                    flipped += failedItem
                    failedItem
                }
                item is ChatItem.Turn && item.thinking?.status == ThinkingStatus.RUNNING -> {
                    val failedThinking = item.thinking.copy(
                        status = ThinkingStatus.ERROR,
                        content = content ?: item.thinking.content
                    )
                    val failedItem = item.copy(thinking = failedThinking)
                    flipped += failedItem
                    failedItem
                }
                else -> item
            }
        }
        if (flipped.isEmpty()) return false
        _uiState.update { it.copy(chatItems = updated) }
        if (fromDisconnect) {
            disconnectFailedIds = flipped.map { it.id }.toSet()
            disconnectFailedSessionId = conversationHistory.activeSessionId.value
        }
        // Merely walking away from a conversation is not a failure: the turn
        // is still running on the shared connection and its reply will be
        // filed into the chat that asked (see inFlightTurnOwner). Writing a
        // "Failed" row here would leave that answer sitting next to a marker
        // saying it never arrived.
        if (!persist) return true
        // Pin the session now rather than letting persistMessage default to
        // whatever is active when each launch runs: the switch-away caller
        // below re-resolves the session immediately afterwards, which would
        // file these rows under the conversation being switched *to*.
        val owningSessionId = conversationHistory.activeSessionId.value
        flipped.forEach { item ->
            launchScoped { conversationHistory.persistMessage(item, owningSessionId) }
        }
        return true
    }

    /**
     * Undoes [failRunningThinking]'s ERROR flip for [ids] when a post-
     * reconnect SESSION_STATUS reply says the server is still running that
     * turn. This can only run after the fact, not gate the original flip:
     * at the moment [ConnectionState.Disconnected] fires there is no live
     * socket left to ask (the reconnect ladder already exhausted itself —
     * see AgentConnection.attemptReconnect), so the only option is to
     * flag it "possibly wrong" and correct it once a socket exists again.
     */
    /**
     * Clears a force-failed mark that the server has since contradicted.
     *
     * Only when a reply actually follows it: SESSION_STATUS "connected" says
     * the run ended, not that it produced anything, and a run whose OUTPUT was
     * genuinely lost should keep saying Failed so Retry stays the obvious move.
     * Resolved to DONE rather than RUNNING — unlike the "running" case there is
     * nothing still coming.
     */
    private fun resolveAnsweredDisconnectedItems(ids: Set<String>) {
        val items = _uiState.value.chatItems
        val lastFailed = items.indexOfLast { it.id in ids }
        if (lastFailed < 0) return
        val answered = items.drop(lastFailed + 1).any { item ->
            when (item) {
                is ChatItem.Agent -> item.content.isNotBlank()
                is ChatItem.Turn -> item.agent?.content?.isNotBlank() == true
                else -> false
            }
        }
        if (!answered) return

        var changed = false
        val updated = items.map { item ->
            when {
                item is ChatItem.Thinking && item.id in ids && item.status == ThinkingStatus.ERROR -> {
                    changed = true
                    item.copy(status = ThinkingStatus.DONE)
                }
                item is ChatItem.Turn && item.id in ids && item.thinking?.status == ThinkingStatus.ERROR -> {
                    changed = true
                    item.copy(thinking = item.thinking.copy(status = ThinkingStatus.DONE))
                }
                else -> item
            }
        }
        if (!changed) return
        _uiState.update { it.copy(chatItems = updated) }
        val owningSessionId = conversationHistory.activeSessionId.value
        updated.filter { it.id in ids }.forEach { item ->
            launchScoped { conversationHistory.persistMessage(item, owningSessionId) }
        }
    }

    private fun unfailDisconnectedItems(ids: Set<String>) {
        var changed = false
        val updated = _uiState.value.chatItems.map { item ->
            when {
                item is ChatItem.Thinking && item.id in ids && item.status == ThinkingStatus.ERROR -> {
                    changed = true
                    item.copy(status = ThinkingStatus.RUNNING)
                }
                item is ChatItem.Turn && item.id in ids && item.thinking?.status == ThinkingStatus.ERROR -> {
                    changed = true
                    item.copy(thinking = item.thinking.copy(status = ThinkingStatus.RUNNING))
                }
                else -> item
            }
        }
        if (!changed) return
        _uiState.update { it.copy(chatItems = updated) }
        val owningSessionId = conversationHistory.activeSessionId.value
        updated.filter { it.id in ids }.forEach { item ->
            launchScoped { conversationHistory.persistMessage(item, owningSessionId) }
        }
    }

    /**
     * True when [event] carries an item Room already files under a *different*
     * conversation — i.e. a session_sync replay of some other chat with this
     * same agent.
     *
     * An id with no row yet (a genuinely new reply, or an in-progress item the
     * server keeps updating) is never "another conversation's", so live traffic
     * is unaffected. A null active session — a new chat nothing has been
     * written into yet — makes every already-owned id foreign, which is
     * exactly right.
     */
    /**
     * The conversation that issued the turn currently awaiting a reply, or
     * null when nothing is in flight.
     *
     * Still a single field after interjection landed: an INPUT sent mid-run is
     * absorbed by the run already in flight and answered by its one OUTPUT, so
     * a second send does not make a second turn outstanding. Set when a message
     * is sent, cleared once that turn's reply has been filed or the connection
     * gives up on it.
     */
    private var inFlightTurnOwner: String? = null

    /**
     * True for the frames that end a turn: the reply itself, or a server error
     * saying none is coming. Everything else a turn emits (session_sync
     * replays, llm_call, llm_result) is progress — the answer is still owed,
     * so [inFlightTurnOwner] has to outlive all of it.
     */
    private fun ChatEvent.endsInFlightTurn(): Boolean =
        this is ChatEvent.OutputReceived || this is ChatEvent.ConnectionErrorOccurred

    /**
     * The conversation an incoming item belongs to, or null when nothing says
     * it is anyone else's.
     *
     * Two sources, in order of how much they know:
     *
     *  1. a row already in Room -- that conversation owns the id, full stop;
     *  2. the conversation that issued the turn still awaiting an answer,
     *     for a reply that arrives on its own OUTPUT frame after the user
     *     moved on. Each conversation has its own server session, so this is
     *     the only crossing left: the switch window, before the re-CONNECT.
     *
     * Null for anything that is not part of a turn. Connection-scoped events
     * used to fall through to [inFlightTurnOwner] as well, which meant a
     * replayed transcript was routed to whichever conversation last sent a
     * message -- and since the reducer produces nothing to persist for one,
     * it was simply dropped. Leaving a turn running in A, sending in B and
     * returning to A lost A's answer that way, with the reply sitting in the
     * database the whole time.
     */
    private suspend fun owningConversationOf(event: ChatEvent): String? {
        val item = when (event) {
            is ChatEvent.ChatItemReceived -> event.item
            is ChatEvent.ChatItemUpdated -> event.item
            // A turn's own outcome — its answer, or the server giving up on
            // it — belongs to the conversation that issued it.
            is ChatEvent.OutputReceived,
            is ChatEvent.ConnectionErrorOccurred -> return inFlightTurnOwner
            // Everything else describes the connection, not a turn: a
            // replayed transcript, a session-status reply, a waiting flag.
            // The connection is already on the conversation on screen, so
            // these have no other owner — handing them to whichever chat
            // happens to have a turn in flight sent them nowhere at all.
            else -> return null
        }
        runCatchingCancellable { messageRepository.getOwningSessionId(item.id) }
            .getOrNull()?.let { return it }

        return inFlightTurnOwner
    }

    /**
     * Fallback for [failRunningThinking] when nothing was RUNNING to attach
     * [message] to (e.g. a server error arrives with no request in flight) —
     * still shown as part of the conversation rather than a banner/snackbar,
     * by synthesizing a standalone failed "Thinking" item.
     */
    private fun appendStandaloneError(message: String) {
        val item = ChatItem.Thinking(
            id = java.util.UUID.randomUUID().toString(),
            status = ThinkingStatus.ERROR,
            content = message
        )
        _uiState.update { it.copy(chatItems = it.chatItems + item) }
        launchScoped { conversationHistory.persistMessage(item) }
    }

    private suspend fun saveAgentIfNeeded(address: String, directUrl: String?) {
        runCatchingCancellable {
            val existing = agentRepository.getAgentByAddress(address)
            if (existing == null) {
                agentRepository.createAgent(
                    ai.openonion.oochat.domain.model.AgentProfile(
                        id = java.util.UUID.randomUUID().toString(),
                        address = address,
                        name = "Agent ${address.take(8)}...",
                        serverUrl = directUrl ?: Constants.DEFAULT_SERVER_URL,
                        createdAt = System.currentTimeMillis(),
                        isActive = true
                    )
                )
            }
        }.onFailure { e -> FileLogger.w(LogTags.CHAT_VM, "Failed to save agent: ${e.message}") }
    }

    /**
     * Start a fresh conversation with the currently connected agent: a new
     * persisted [ChatSession] becomes active and the visible chat list clears.
     * Driven by the drawer's "New conversation" action.
     */
    fun startNewSession() {
        launchScoped {
            // Before the list is cleared, so a turn still running in the
            // conversation being left doesn't stay RUNNING forever — see the
            // same guard in connectToAgent.
            failRunningThinking(persist = false)
            if (conversationHistory.startNewSession()) {
                _uiState.update { it.copy(chatItems = emptyList()) }
                // The id is minted here, not at the first message, so the
                // re-CONNECT already names this chat's own session.
                connectUseCase.switchConversation(conversationHistory.conversationId)
            }
        }
    }

    /**
     * Load a previously persisted conversation and make it active. Sending a
     * new message afterwards continues this thread. Driven by tapping a
     * conversation in the drawer.
     */
    fun switchToSession(sessionId: String) {
        launchScoped {
            if (conversationHistory.activeSessionId.value != sessionId) {
                failRunningThinking(persist = false)
            }
            val items = conversationHistory.switchToSession(sessionId)
            _uiState.update { current ->
                current.copy(chatItems = items)
            }
            // Rendered from Room first, then the connection moves: the
            // transcript is on screen before the re-CONNECT round trip, and a
            // message sent during it queues rather than being refused.
            connectUseCase.switchConversation(sessionId)
        }
    }

    /**
     * Permanently delete a persisted session — the drawer row's trash
     * action. Works for a session belonging to any agent shown in the
     * drawer, not just the one this ChatViewModel is connected to (see
     * ConversationHistoryUseCase.deleteSession's own doc). Clears the
     * visible chat list too, but only when the deleted session was the one
     * actually open on screen.
     */
    fun deleteSession(sessionId: String) {
        launchScoped {
            if (conversationHistory.deleteSession(sessionId)) {
                _uiState.update { it.copy(chatItems = emptyList()) }
                // Deleting the open conversation starts a fresh one in its
                // place; the connection follows it onto that one's session.
                connectUseCase.switchConversation(conversationHistory.conversationId)
            }
        }
    }

    /** Renames a persisted session — the drawer row's pencil action. */
    fun renameSession(sessionId: String, newTitle: String) {
        launchScoped {
            conversationHistory.renameSession(sessionId, newTitle)
        }
    }

    /** Moves one of the user's own bubbles to [next]. */
    private fun setUserMessageState(messageId: String, next: UserMessageState) {
        _uiState.update { state ->
            state.copy(
                chatItems = state.chatItems.map { item ->
                    if (item.id == messageId && item is ChatItem.User) item.copy(state = next) else item
                }
            )
        }
    }

    /**
     * Send a message that failed again, in place.
     *
     * Reuses the bubble's own id rather than minting a new one, so the
     * transcript keeps one entry for one message however many attempts it
     * takes — and so a second attempt cannot leave the first as an orphan the
     * user has no way to clear.
     */
    fun resendMessage(messageId: String) {
        val failed = _uiState.value.chatItems
            .filterIsInstance<ChatItem.User>()
            .firstOrNull { it.id == messageId && it.state == UserMessageState.FAILED } ?: return

        setUserMessageState(messageId, UserMessageState.QUEUED)
        sendMessage(
            content = failed.content,
            images = failed.images,
            files = failed.files?.map { it.path },
            // The bubble is already on screen and already carries this id.
            echoUserMessage = false,
            messageId = messageId
        )
    }

    /**
     * Re-issue a turn that failed, without echoing the user's message again.
     * That message is already in the transcript — "Retry" was wired straight
     * to [sendMessage], so every tap appended and persisted a second copy of
     * it. The failed marker stays where it is: that attempt really did fail,
     * and the new reply lands after it.
     */
    fun retryFailedTurn(content: String, images: List<String>? = null, files: List<String>? = null) {
        sendMessage(content, images, files, echoUserMessage = false, waitForRunningTurn = false)
    }

    fun sendMessage(
        content: String,
        images: List<String>? = null,
        files: List<String>? = null,
        // False only from [retryFailedTurn]; see its doc.
        echoUserMessage: Boolean = true,
        // A retry follows a turn that already failed, so there is no run to be
        // swallowed by and nothing to wait for — see [sendWhenTheAgentIsIdle].
        waitForRunningTurn: Boolean = true,
        // Reuses an existing bubble's id instead of minting one, so resending a
        // failed message revives that bubble rather than adding a second copy
        // of it. Also the only stable handle this client has on an outgoing
        // message: the wire's input_id is regenerated on every attempt.
        messageId: String? = null
    ) {
        if (content.isBlank() && images.isNullOrEmpty() && files.isNullOrEmpty()) return
        if (_targetAgentAddress.isBlank()) {
            FileLogger.w(LogTags.CHAT_VM, "sendMessage failed: no target address")
            return
        }
        // Deliberately no early return when the socket is down. Refusing here
        // was what made the persisted outbox almost unreachable: it is the
        // connection layer that enqueues, and this gate returned before the
        // message ever got there. The user was told "not connected" and the
        // message was dropped on the floor. It goes through now and comes to
        // rest as QUEUED, which the bubble says and the outbox flushes on the
        // next CONNECTED.
        val offline = !connectUseCase.isConnected()
        if (offline) {
            FileLogger.i(LogTags.CHAT_VM, "Not connected — the message goes to the outbox")
        }

        FileLogger.i(LogTags.CHAT_VM, "sendMessage: ${LogSanitizer.contentSummary(content)} → ${_targetAgentAddress.take(16)}")

        val userId = messageId ?: java.util.UUID.randomUUID().toString()
        // Held when a turn is already running: the bubble goes up now so the
        // send is visibly acknowledged, but it stays flagged until it is
        // actually on the wire, which is also what keeps the running turn's
        // remaining output above it. See [sendWhenTheAgentIsIdle].
        val queuedAtSend = offline || (waitForRunningTurn && serverTurnActive.value)
        val provisionalItem = ChatItem.User(
            id = userId,
            content = content,
            images = images,
            state = if (queuedAtSend) UserMessageState.QUEUED else UserMessageState.SENT
        )
        if (echoUserMessage) {
            _uiState.update {
                it.copy(chatItems = (it.chatItems + provisionalItem).dedupeUI())
            }
        }

        val instructionsSnapshot = customInstructions.value

        viewModelScope.launch {
            // Captured first thing inside the coroutine — materializing a
            // merely-pending session, and moving the connection onto it,
            // before the async gap below (image storage). Pinned here so a
            // session switch mid-flight cannot misfile the message; see
            // conversationForOutgoingMessage.
            val targetSessionId = conversationForOutgoingMessage()
            // Remembered for the whole turn: if the user moves to another
            // conversation before the reply lands, this is the chat that owns
            // it — see owningConversationOf.
            inFlightTurnOwner = targetSessionId

            // Picker URIs (content://media/picker/...) only grant a
            // transient read grant tied to this picking session and the
            // wire format needs base64 anyway, so every attached image is
            // copied into app-private storage (durable local path, used for
            // display/persistence) and base64-encoded (for the wire).
            val attemptedImages = images.orEmpty()
            val storedImages = attemptedImages.mapNotNull { uriString ->
                runCatchingCancellable { imageAttachmentStore.store(uriString) }
                    .onFailure { FileLogger.e(LogTags.CHAT_VM, "Failed to store image $uriString: ${it.message}") }
                    .getOrNull()
            }
            val localPaths = storedImages.map { it.localPath }.ifEmpty { null }
            val dataUrls = storedImages.map { it.dataUrl }.ifEmpty { null }

            // Files (SAF documents) aren't copied into app-private storage —
            // OpenDocument grants a persistable read permission, unlike the
            // photo picker's transient one — and, unlike images, there's no
            // ChatItem.User field to display them in the transcript yet, so
            // they're only converted for the outgoing wire payload.
            // storeAll, not a store() per URI: the whole selection stays in
            // memory until the send below, so the byte budget has to be spent
            // across it rather than reset for each file.
            val attemptedFiles = files.orEmpty()
            val fileOutcomes = runCatchingCancellable { fileAttachmentStore.storeAll(attemptedFiles) }
                .onFailure { FileLogger.e(LogTags.CHAT_VM, "Failed to read ${attemptedFiles.size} file(s): ${it.message}") }
                .getOrElse { attemptedFiles.map { ai.openonion.oochat.data.local.FileAttachResult.Failed(null) } }
            val fileSuccesses = fileOutcomes.filterIsInstance<ai.openonion.oochat.data.local.FileAttachResult.Success>()
            val fileAttachments = fileSuccesses.map { it.attachment }.ifEmpty { null }
            // Local copies for the ChatItem.User.files field — same shape
            // ImageAttachmentStore's localPaths patch below uses for images,
            // so a reload or a failed-message retry can find the bytes again.
            val storedFiles = fileSuccesses
                .map { ChatFileAttachment(name = it.attachment.name, path = it.localPath) }
                .ifEmpty { null }
            val tooLargeFiles = fileOutcomes.filterIsInstance<ai.openonion.oochat.data.local.FileAttachResult.TooLarge>()
            val failedFileCount = fileOutcomes.count { it is ai.openonion.oochat.data.local.FileAttachResult.Failed }

            if (content.isBlank() && localPaths == null && fileAttachments == null) {
                // Every attachment failed and there's no text — nothing
                // meaningful survived to persist or send. Drop the
                // provisional bubble instead of leaving a permanent blank
                // one, and tell the user instead of failing silently.
                _uiState.update {
                    it.copy(
                        chatItems = it.chatItems.filterNot { item -> item.id == userId },
                        error = createError(
                            attachmentErrorMessage(attemptedImages.size, tooLargeFiles, failedFileCount)
                                ?: "Failed to attach image(s)/file(s). Please try again."
                        )
                    )
                }
                return@launch
            }

            // Only patch the bubble (and pay the extra recomposition) when
            // there were images or files to begin with — a plain text send's
            // provisional item already carries its final content.
            val userItem = if (attemptedImages.isEmpty() && attemptedFiles.isEmpty()) {
                provisionalItem
            } else {
                val patched = provisionalItem.copy(images = localPaths, files = storedFiles)
                if (echoUserMessage) {
                    _uiState.update {
                        it.copy(chatItems = it.chatItems.map { item -> if (item.id == userId) patched else item })
                    }
                }
                patched
            }

            // Some (but not all) images/files failed — the message still
            // sends with whatever survived; say so instead of leaving the
            // user to notice a smaller attachment set with no explanation.
            attachmentErrorMessage(attemptedImages.size - storedImages.size, tooLargeFiles, failedFileCount)?.let { message ->
                _uiState.update { it.copy(error = createError(message)) }
            }

            val contentForAgent = if (instructionsSnapshot.isBlank()) content else "$instructionsSnapshot\n\n$content"
            val hasSomethingToSend = content.isNotBlank() || dataUrls != null || fileAttachments != null

            val put: suspend () -> Unit = {
                // Persisted here rather than when the bubble went up. The row's
                // timestamp is what orders the transcript, and stamping it at
                // the tap put a queued message *ahead of the reply it is
                // waiting on* — the pinning below keeps the live list right,
                // but the persisted order won, so the pair rendered as two
                // questions followed by two answers.
                if (echoUserMessage) conversationHistory.persistMessage(userItem, targetSessionId)
                // A throw here is the send genuinely failing — the socket was
                // closing under us, or the outbox refused the payload. The
                // bubble has to say so, because nothing else will: there is no
                // negative acknowledgement on this protocol.
                val delivered = if (hasSomethingToSend) {
                    runCatchingCancellable {
                        connectUseCase.sendMessage(contentForAgent, _targetAgentAddress, dataUrls, fileAttachments)
                    }.onFailure {
                        FileLogger.e(LogTags.CHAT_VM, "Send failed: ${it.message}")
                    }.isSuccess
                } else {
                    true
                }
                // Off the tail once it lands, so anything the new turn emits
                // appends after it the way it always did. Offline sends stay
                // QUEUED until the outbox flush; only a hard failure is FAILED.
                val settled = when {
                    !delivered -> UserMessageState.FAILED
                    offline -> UserMessageState.QUEUED
                    else -> UserMessageState.SENT
                }
                if (queuedAtSend || settled == UserMessageState.FAILED) {
                    setUserMessageState(userId, settled)
                }
            }
            if (waitForRunningTurn && hasSomethingToSend) sendWhenTheAgentIsIdle(put) else put()
        }
    }

    // One message reaches the wire at a time, and only between turns.
    private val outgoingSendLock = Mutex()


    /**
     * Puts [send] on the wire once no turn is running.
     *
     * The relay accepts a message sent mid-turn and acknowledges it with
     * `RUNTIME_INPUT_ACK`, but the agent only reads that queue at the start of
     * an iteration. A turn that calls a tool has another iteration and picks it
     * up; a plain question has exactly one and never does — measured both ways
     * on device. Waiting makes it an ordinary INPUT, which always starts its
     * own turn and is always answered.
     */
    private suspend fun sendWhenTheAgentIsIdle(send: suspend () -> Unit) {
        outgoingSendLock.withLock {
            // Either signal counts as busy: serverTurnActive covers the turn
            // this session started, isAgentWorking covers one already running
            // when the screen opened on a resumed conversation.
            if (serverTurnActive.value || isAgentWorking.value) {
                FileLogger.i(LogTags.CHAT_VM, "Send held: a turn is still running")
                val busy = combine(serverTurnActive, isAgentWorking) { own, onScreen -> own || onScreen }
                // Bounded: a turn left in progress by a dropped connection must
                // not strand the message forever. Sending late beats never.
                withTimeoutOrNull(TURN_WAIT_TIMEOUT_MS) { busy.first { !it } }
            }
            send()
            // Set here rather than when progress first shows: there is no gap
            // between the INPUT leaving and this being true, so a second queued
            // message has nothing to slip through.
            serverTurnActive.value = true
        }
    }

    /**
     * Combined error text for [sendMessage]'s image/file failures, or null
     * when nothing failed. Reused for both the "everything failed" (blocking)
     * and "some survived" (non-blocking) cases — see call sites.
     */
    private fun attachmentErrorMessage(
        imageFailCount: Int,
        tooLargeFiles: List<ai.openonion.oochat.data.local.FileAttachResult.TooLarge>,
        failedFileCount: Int
    ): String? {
        val parts = mutableListOf<String>()
        if (imageFailCount > 0) parts += "$imageFailCount image(s) failed to attach"
        if (tooLargeFiles.isNotEmpty()) {
            // TooLarge covers both caps, and the store doesn't say which one
            // was hit, so name both — otherwise a file well under 10MB gets
            // rejected against a limit it visibly doesn't exceed.
            val names = tooLargeFiles.joinToString(", ") { it.name }
            val verb = if (tooLargeFiles.size == 1) "exceeds" else "exceed"
            parts += "$names $verb the attachment size limit (10MB per file, 20MB total)"
        }
        if (failedFileCount > 0) parts += "$failedFileCount file(s) failed to attach"
        if (parts.isEmpty()) return null
        return parts.joinToString("; ") + "."
    }

    // ── Voice input ─────────────────────────────────────────────
    //
    // Voice fills the composer; it never sends. The user reads what was heard,
    // fixes the names and numbers the recognizer got wrong, and sends it like
    // any other message — ported from oo-chat-web's chat-input.tsx, whose
    // onTranscribed does nothing but append to the input field.

    private val _voiceInput = MutableStateFlow(VoiceInputState())
    val voiceInput: StateFlow<VoiceInputState> = _voiceInput.asStateFlow()

    private var dictationJob: Job? = null

    // True while the current dictation is going through SpeechRecognizer, false
    // while it is going through the record-then-POST fallback.
    private var usingRecognizer = false

    // The path change is worth saying once — after that it is just noise.
    private var fallbackNoticeShown = false

    // When the mic was tapped, for the "source live after Xms" line below. That
    // gap is the whole reason PREPARING exists, so it is worth logging.
    private var dictationTappedAtMs = 0L

    @Volatile
    private var latestVoiceLevel = 0f

    /**
     * Opens the microphone. Prefers the platform recognizer, which streams
     * partial results into the composer while the user is still speaking, and
     * falls back to recording plus [voiceTranscriptionService] where the
     * device's recognizer is missing — or registered but silent.
     *
     * Enters [VoiceInputPhase.PREPARING], not LISTENING: choosing a path can
     * take a capability query or a whole 2.5s probe, and audio spoken before a
     * source is actually open is lost.
     */
    fun startVoiceRecording() {
        dictationJob?.cancel()
        latestVoiceLevel = 0f
        // Reset rather than carry the last dictation's path: cancelling while
        // PREPARING has to be free to release a recorder either way.
        usingRecognizer = false
        dictationTappedAtMs = System.currentTimeMillis()
        _voiceInput.value = VoiceInputState(phase = VoiceInputPhase.PREPARING)

        dictationJob = viewModelScope.launch {
            when (val readiness = speechRecognitionService.readiness()) {
                RecognizerReadiness.UNAVAILABLE -> {
                    FileLogger.i(LogTags.CHAT_VM, "dictation path: server transcription")
                    startFallbackRecording()
                }
                RecognizerReadiness.READY, RecognizerReadiness.UNPROVEN -> {
                    FileLogger.i(LogTags.CHAT_VM, "dictation path: platform recognizer ($readiness)")
                    // READY is the recognizer's own account of itself, and a
                    // recognizer's account of itself is not evidence. Both live
                    // paths are watched; only the patience differs.
                    collectDictation(
                        silenceTimeoutMs = if (readiness == RecognizerReadiness.READY) {
                            RECOGNIZER_READY_TIMEOUT_MS
                        } else {
                            RECOGNIZER_PROBE_TIMEOUT_MS
                        }
                    )
                }
            }
        }
    }

    /**
     * Collects one dictation, watched. A recognizer has [silenceTimeoutMs] to
     * make any callback at all — `onReadyForSpeech` is the one that means
     * "listening now" — and the dictation moves to server transcription if
     * nothing comes back in time. Every dictation is its own probe: what the
     * recognizer claimed beforehand only sets how long it is given.
     */
    private suspend fun collectDictation(silenceTimeoutMs: Long) {
        usingRecognizer = true
        var silent = false
        coroutineScope {
            // Any callback proves the service is alive, not just Ready: the
            // failure being guarded against is total silence.
            val spoke = CompletableDeferred<Unit>()
            val collector = launch {
                speechRecognitionService.listen().collect { event ->
                    if (spoke.complete(Unit)) {
                        // The same callback settles both questions: the service
                        // answered, so it is usable and its microphone is open.
                        speechRecognitionService.recordProbeResult(usable = true)
                        markSourceLive("platform recognizer")
                    }
                    applyDictationEvent(event)
                }
            }
            val watchdog = launch {
                if (withTimeoutOrNull(silenceTimeoutMs) { spoke.await() } == null) {
                    silent = true
                    // Cancelling releases the microphone the stub is holding.
                    collector.cancel()
                }
            }
            collector.join()
            watchdog.cancel()
        }
        if (silent) {
            FileLogger.w(
                LogTags.CHAT_VM,
                "recognizer silent for ${silenceTimeoutMs}ms; falling back to server transcription"
            )
            speechRecognitionService.recordProbeResult(usable = false)
            startFallbackRecording()
        }
    }

    private fun applyDictationEvent(event: SpeechRecognitionEvent) {
        when (event) {
            is SpeechRecognitionEvent.Ready -> Unit
            is SpeechRecognitionEvent.Level -> latestVoiceLevel = event.normalized
            is SpeechRecognitionEvent.Partial ->
                _voiceInput.update { it.copy(transcript = event.text) }
            is SpeechRecognitionEvent.Final ->
                _voiceInput.value = VoiceInputState(phase = VoiceInputPhase.IDLE, transcript = event.text)
            is SpeechRecognitionEvent.Failed ->
                // Partials that already landed stay: they are what the user
                // watched arrive, and a speech-timeout after a finished
                // sentence should not erase it. The reason is only worth
                // showing when there is nothing to show instead.
                _voiceInput.update { state ->
                    state.copy(
                        phase = VoiceInputPhase.IDLE,
                        error = if (state.transcript.isBlank()) event.reason else null
                    )
                }
        }
    }

    /**
     * The moment an audio source is actually capturing. Leaving PREPARING here
     * rather than at the tap is what makes the row's timer, waveform and label
     * describe something real.
     */
    private fun markSourceLive(path: String) {
        val waited = System.currentTimeMillis() - dictationTappedAtMs
        FileLogger.i(LogTags.CHAT_VM, "dictation source live after ${waited}ms ($path)")
        _voiceInput.update {
            if (it.phase == VoiceInputPhase.PREPARING) it.copy(phase = VoiceInputPhase.LISTENING) else it
        }
    }

    /**
     * Starts the record-then-POST path. Goes to LISTENING so a fallback decided
     * mid-dictation does not interrupt the user — they keep talking into the
     * recorder instead of tapping the mic again.
     */
    private suspend fun startFallbackRecording() {
        usingRecognizer = false
        latestVoiceLevel = 0f
        if (!voiceRecorderStore.startRecording()) {
            FileLogger.w(LogTags.CHAT_VM, "startVoiceRecording failed: recorder init error")
            _voiceInput.value = VoiceInputState(error = "Couldn't start recording.")
            return
        }
        val notice = if (fallbackNoticeShown) null else "Using server transcription."
        fallbackNoticeShown = true
        // The notice lands with the meter, not before it: until the recorder is
        // open there is no path to report.
        _voiceInput.update { it.copy(phase = VoiceInputPhase.LISTENING, notice = it.notice ?: notice) }
        markSourceLive("server transcription")
    }

    /** Closes the microphone and keeps the transcript — the composer merges it in. */
    fun finishVoiceInput() {
        if (!_voiceInput.value.isBusy) return
        if (_voiceInput.value.phase == VoiceInputPhase.PREPARING) {
            // No microphone is open yet, so there is nothing to settle and no
            // audio to keep — finishing and discarding are the same outcome.
            // The row offers no finish target here; this only catches a caller
            // that raced the phase change.
            FileLogger.w(LogTags.CHAT_VM, "dictation finished before any audio source was live")
            cancelVoiceRecording()
            return
        }
        if (usingRecognizer) {
            viewModelScope.launch { speechRecognitionService.stop() }
            return
        }

        // Stopping joins the capture thread and rewrites the WAV header, so it
        // belongs off the UI callback that triggered it — the store keeps it on
        // IO, and this is the coroutine that lets it.
        dictationJob = viewModelScope.launch {
            val recorded = voiceRecorderStore.stopRecording()
            if (recorded == null) {
                _voiceInput.value = VoiceInputState()
                return@launch
            }
            _voiceInput.update { it.copy(phase = VoiceInputPhase.TRANSCRIBING) }
            val transcript = voiceTranscriptionService.transcribe(recorded.file).getOrNull()
            _voiceInput.value = VoiceInputState(
                transcript = transcript.orEmpty(),
                error = if (transcript.isNullOrBlank()) "Couldn't transcribe that. Try again." else null
            )
        }
    }

    /**
     * Discards the dictation without putting anything in the composer. Works
     * from PREPARING too: cancelling the job takes the probe watchdog and the
     * recognizer collector with it, and [usingRecognizer] starts each dictation
     * false so a recorder that did open is always released.
     */
    fun cancelVoiceRecording() {
        dictationJob?.cancel()
        dictationJob = null
        val releaseRecorder = !usingRecognizer
        latestVoiceLevel = 0f
        // State first, recorder after: the row has to go the instant the user
        // taps cancel, and releasing the recorder joins its capture thread and
        // deletes the file. Not on dictationJob — that is the job just cancelled.
        _voiceInput.value = VoiceInputState()
        if (releaseRecorder) viewModelScope.launch { voiceRecorderStore.cancelRecording() }
    }

    /**
     * The composer has taken [VoiceInputState.transcript] into the field; don't
     * hand it over twice. Any error survives — it is cleared by the next
     * [startVoiceRecording], not by the same frame that raised it.
     */
    fun consumeVoiceTranscript() {
        _voiceInput.update { if (it.isBusy) it else VoiceInputState(error = it.error) }
    }

    /** Polled by the recording UI to drive the waveform off real mic input. */
    fun currentVoiceAmplitude(): Float =
        if (usingRecognizer) latestVoiceLevel else voiceRecorderStore.currentAmplitude()

    /**
     * The app came back to the foreground. Android aborts a backgrounded
     * app's sockets, so a drop is the normal outcome of a trip to the camera
     * or a picker — and the reason for it has just gone away. Retry now
     * rather than sit out a backoff computed for a flaky network.
     *
     * Also dismisses any reply notification — the user is looking at the
     * chat again, so it would only be stale clutter in the shade now.
     */
    fun onReturnedToForeground() {
        connectUseCase.retryNow()
        agentReplyNotifier.clearReplyNotifications()
    }

    fun respond(answer: String) {
        launchScoped { connectUseCase.respond(answer) }
    }

    /**
     * Ask the agent to gracefully stop its current run — it finishes the
     * current step and returns a closing message rather than stopping
     * mid-tool-call (see [ai.openonion.oochat.data.protocol.InterruptMessage]'s
     * own doc). Optimistically freezes every running item's spinner via
     * [markRunningItemsDone] the instant this is called, rather than
     * leaving them spinning until that real closing message arrives —
     * mirrors oo-chat-web's use-agent-sdk.ts `stopRunningItems`.
     */
    fun interrupt() {
        FileLogger.i(LogTags.CHAT_VM, "interrupt: stopping current run")
        markRunningItemsDone()
        // markRunningItemsDone only freezes the spinners. Without this the
        // send gate stayed shut, so Stop looked like it had worked and the
        // next message was still held behind the turn the user just stopped.
        serverTurnActive.value = false
        launchScoped { connectUseCase.interrupt() }
    }

    /**
     * Optimistic transient rendering fix: not persisted (unlike
     * failRunningThinking's ERROR case), so items reappear if the agent's
     * real closing event overwrites them. See resolveRunningItems's doc for
     * the shared per-type logic.
     */
    private fun markRunningItemsDone() {
        _uiState.update { state -> state.copy(chatItems = state.chatItems.resolveRunningItems(closeTurns = false)) }
    }

    /**
     * Answer an approval gate. The answer is written onto the item itself, so
     * the card renders its outcome after a recomposition and after a reload.
     *
     * Re-answering an already-decided gate is dropped — a second
     * APPROVAL_RESPONSE would be acted on again by the server.
     */
    fun respondToApproval(
        itemId: String,
        approved: Boolean,
        scope: String = "once",
        mode: String? = null,
        feedback: String? = null
    ) {
        val gate = _uiState.value.chatItems
            .filterIsInstance<ChatItem.ApprovalNeeded>()
            .firstOrNull { it.id == itemId }
        if (gate == null || gate.decision != null) {
            FileLogger.w(LogTags.CHAT_VM, "Ignoring duplicate/unknown approval response for $itemId")
            return
        }

        val decided = gate.copy(decision = ApprovalDecision(approved, scope, mode, feedback))
        _uiState.update { state ->
            state.copy(chatItems = state.chatItems.map { if (it.id == itemId) decided else it })
        }
        launchScoped {
            connectUseCase.respondToApproval(approved, scope, mode, feedback)
            // Persisted only now that it's answered: an open gate replayed from
            // history would offer buttons for a question the server has closed,
            // but the record of what was approved belongs in the transcript.
            conversationHistory.persistMessage(decided)
        }
    }

    /**
     * Reply to an ONBOARD_REQUIRED gate with either an invite code or a
     * payment confirmation. Must check hasLiveConnection, not strict
     * isConnected(), because the server never sends CONNECTED during
     * onboarding (so connectionState alone can't signal this).
     */
    fun respondToOnboard(method: String, inviteCode: String? = null, payment: Double? = null) {
        val state = connectUseCase.connectionState.value
        if (!state.hasLiveConnection()) {
            FileLogger.w(LogTags.CHAT_VM, "respondToOnboard failed: not connected ($state)")
            _uiState.update { it.copy(error = createError("Not connected. Please reconnect first.")) }
            return
        }
        launchScoped {
            connectUseCase.respondToOnboard(method, inviteCode, payment)
        }
    }

    /** Reply to a plan_review checkpoint with free-text feedback. */
    fun respondToPlanReview(message: String) {
        launchScoped { connectUseCase.respondToPlanReview(message) }
    }

    /** Reply to a ulw_turns_reached checkpoint. */
    fun respondToUlwTurnsReached(action: String, turns: Int? = null, mode: String? = null) {
        launchScoped { connectUseCase.respondToUlwTurnsReached(action, turns, mode) }
    }

    /**
     * Advance the mode chip one step through [ApprovalMode.CYCLE].
     *
     * This is the *only* path a tap can take, and it cannot reach
     * [ApprovalMode.ULW] — see [ApprovalMode.CYCLE]'s doc. Entering ULW goes
     * through [setApprovalMode] with an explicit turn budget instead. From
     * ULW itself this lands on the first cycle entry, which is the exit the
     * chip offers while ULW is active.
     */
    fun cycleApprovalMode() {
        setApprovalMode(approvalMode.value.next())
    }

    /**
     * Switch to an explicitly chosen mode. [turns] is ULW's autonomous-turn
     * budget and is ignored by the server for every other mode.
     */
    fun setApprovalMode(mode: ApprovalMode, turns: Int? = null) {
        launchScoped { connectUseCase.setMode(mode, turns) }
    }

    fun disconnect() {
        launchScoped { connectUseCase.disconnect() }
    }

    fun clearChat() {
        launchScoped {
            connectUseCase.reset()
            // Snapshot current ids into the current agent's ignore
            // set, then await the DataStore write before wiping the
            // UI. Awaiting (rather than fire-and-forget) means a
            // process kill between clearChat and the next launch
            // can't leave the persistence half-done.
            val snapshot = _uiState.value.chatItems.map { it.id }
            ignoredIdsManager.ignoreAll(_targetAgentAddress, snapshot)
            // Wipe the active session's persisted log too, so the drawer's
            // preview/count for this conversation reflects the clear.
            conversationHistory.clearActiveSession()
            _uiState.update { it.copy(chatItems = emptyList()) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // No onCleared() teardown on purpose: connectUseCase is the app-scoped
    // shared connection (see AppContainer), so closing it here would kill a
    // socket the next ChatScreen is about to reuse. disconnect() is the user's.

    companion object {
        /** How long a queued send waits out the running turn — see [sendWhenTheAgentIsIdle]. */
        private const val TURN_WAIT_TIMEOUT_MS = 120_000L
    }
}
