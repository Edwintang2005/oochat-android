package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.local.IgnoredIdsManager
import ai.openonion.oochat.data.local.IgnoredIdsStorage
import ai.openonion.oochat.data.local.ImageAttachmentStore
import ai.openonion.oochat.data.local.RecordedVoice
import ai.openonion.oochat.data.local.StoredImage
import ai.openonion.oochat.data.local.VoiceRecorderStore
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.domain.model.stableAssistantId
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.OFFLINE_GRACE_MS
import ai.openonion.oochat.network.RecognizerReadiness
import ai.openonion.oochat.network.SpeechRecognitionEvent
import ai.openonion.oochat.network.SpeechRecognitionService
import ai.openonion.oochat.network.VoiceTranscriptionService
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ChatViewModel].
 *
 * ChatViewModel extends [androidx.lifecycle.AndroidViewModel], which
 * requires an [Application]. We satisfy that with the no-arg constructor
 * (`Application()`) — Android stub methods are never called because every
 * dependency is swapped for an in-memory fake, so the stub-throws-default
 * behaviour cannot fire on this code path.
 *
 * Tests focus on the ViewModel's documented contract:
 *  - `sendMessage` / `clearChat` / `respond*` forward correctly to the use case
 *  - per-agent `ignoredIds` semantics survive a clear
 *  - `ChatItemReceived` / `ChatItemUpdated` / `OutputReceived` shape the UI list
 *    and respect the ignore set
 *  - errors surface through `uiState.error` and are cleared on demand
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    // ── Test fakes ─────────────────────────────────────────────────────

    private class FakeConnectToAgentUseCase : ConnectToAgentUseCaseContract {
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState

        val _agentProfile = MutableStateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?>(null)
        override val agentProfile: StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> = _agentProfile

        // Mirrors ConnectionRepositoryImpl: setMode writes optimistically, an
        // inbound mode_changed overwrites. Tests drive the second half with
        // emitModeChanged().
        val _approvalMode = MutableStateFlow(ai.openonion.oochat.domain.model.ApprovalMode.DEFAULT)
        override val approvalMode: StateFlow<ai.openonion.oochat.domain.model.ApprovalMode> = _approvalMode

        val _modePending = MutableStateFlow(false)
        override val modePending: StateFlow<Boolean> = _modePending

        val setModeCalls = mutableListOf<Pair<ai.openonion.oochat.domain.model.ApprovalMode, Int?>>()
        override suspend fun setMode(mode: ai.openonion.oochat.domain.model.ApprovalMode, turns: Int?) {
            setModeCalls += mode to turns
            _approvalMode.value = mode
            _modePending.value = true
        }

        val _dashboardHtml = MutableStateFlow<String?>(null)
        override val dashboardHtml: StateFlow<String?> = _dashboardHtml

        private val _events = MutableSharedFlow<ChatEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        var isConnectedResult: Boolean = false
        override fun isConnected(): Boolean = isConnectedResult

        var connectResult: Boolean = true
        var connectInvocations = 0
        var lastConnectAddress: String? = null
        var lastConnectDirectUrl: String? = null
        var lastConnectConversationId: String? = null
        val switchedConversations = mutableListOf<String?>()
        /**
         * The conversation the socket is actually CONNECTed as — the thing
         * the old fakes had no notion of, so "we sent an INPUT on someone
         * else's session" was unrepresentable and therefore untestable.
         */
        var connectedConversationId: String? = null

        /** Ordered record of what was asked of the connection, for ordering assertions. */
        val interactions = mutableListOf<String>()

        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?): Boolean {
            connectInvocations++
            lastConnectAddress = agentAddress
            lastConnectConversationId = conversationId
            lastConnectDirectUrl = directUrl
            if (connectResult) connectedConversationId = conversationId
            interactions += "connect:$conversationId"
            return connectResult
        }
        /** Mirrors the repository: the session in play is the conversation we connected on. */
        override fun liveSessionIdFor(agentAddress: String): String? = connectedConversationId

        /** What the ViewModel considered active at each switchConversation call. */
        val activeAtSwitch = mutableListOf<String?>()
        var activeSessionIdAtSwitch: (() -> String?)? = null
        override suspend fun switchConversation(conversationId: String?) {
            // Mirrors ConnectionRepositoryImpl: switching to the conversation
            // the connection is already on is a no-op, so a recorded switch
            // always means a real socket round trip.
            if (conversationId == connectedConversationId) return
            switchedConversations += conversationId
            activeAtSwitch += activeSessionIdAtSwitch?.invoke()
            connectedConversationId = conversationId
            interactions += "switch:$conversationId"
        }

        data class SentMessage(
            val content: String,
            val agentAddress: String,
            val images: List<String>?,
            val files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>? = null
        )
        var sentMessages = mutableListOf<SentMessage>()
        /** Set to make the next send throw, the way a refused socket write does. */
        var failNextSend = false
        override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>?) {
            interactions += "send:$content"
            if (failNextSend) {
                failNextSend = false
                throw java.io.IOException("socket refused the write")
            }
            sentMessages += SentMessage(content, agentAddress, images, files)
        }

        var lastRespond: String? = null
        override suspend fun respond(answer: String) { lastRespond = answer }

        var interruptInvocations = 0
        override suspend fun interrupt() { interruptInvocations++ }

        var lastApproval: Boolean? = null
        var lastApprovalScope: String? = null
        var lastApprovalMode: String? = null
        var lastApprovalFeedback: String? = null
        override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {
            lastApproval = approved
            lastApprovalScope = scope
            lastApprovalMode = mode
            lastApprovalFeedback = feedback
        }

        var lastOnboardMethod: String? = null
        var lastOnboardInviteCode: String? = null
        var lastOnboardPayment: Double? = null
        override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {
            lastOnboardMethod = method
            lastOnboardInviteCode = inviteCode
            lastOnboardPayment = payment
        }

        var lastPlanReviewMessage: String? = null
        override suspend fun respondToPlanReview(message: String) { lastPlanReviewMessage = message }

        var lastUlwAction: String? = null
        var lastUlwTurns: Int? = null
        var lastUlwMode: String? = null
        override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {
            lastUlwAction = action
            lastUlwTurns = turns
            lastUlwMode = mode
        }

        var disconnectCount = 0
    var retryNowCalls = 0
    override fun retryNow() { retryNowCalls++ }

        var querySessionStatusCalls = 0
        override suspend fun querySessionStatus() { querySessionStatusCalls++ }

        override suspend fun disconnect() { disconnectCount++ }

        var resetCount = 0
        override suspend fun reset() { resetCount++ }


        // When true, the NEXT collected event throws CancellationException
        // instead of being delivered — simulates the ViewModel's scope
        // being cancelled (e.g. cleared) while the collector is suspended
        // mid-collect, for the "cancellation must propagate, not become a
        // uiState.error" regression test below.
        var throwCancellationOnNextEvent = false
        override fun observeEvents(): Flow<ChatEvent> = _events.map { event ->
            if (throwCancellationOnNextEvent) throw kotlinx.coroutines.CancellationException("simulated scope cancellation")
            event
        }

        // ── Test helpers ───────────────────────────────────────────────

        fun emit(event: ChatEvent) {
            // tryEmit is sufficient here: extraBufferCapacity is large and
            // the test dispatcher runs the collector immediately.
            _events.tryEmit(event)
        }

        fun markConnected(address: String = "0xAGENT") {
            _connectionState.value = ConnectionState.Connected(address = address)
            isConnectedResult = true
        }

        fun forceState(state: ConnectionState) {
            _connectionState.value = state
        }
    }

    private class FakeIgnoredIdsStorage : IgnoredIdsStorage {
        private var backing: MutableMap<String, MutableSet<String>> = mutableMapOf()
        var saveCount = 0

        override suspend fun loadAll(): Map<String, Set<String>> = backing
        override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {
            backing[agentAddress] = ids.toMutableSet()
            saveCount++
        }
    }

    /** Stands in for `ConnectivityManager`, so these tests can pull the radio. */
    private class FakeNetworkMonitor : ai.openonion.oochat.network.NetworkMonitor {
        val online = MutableStateFlow(true)
        override val isOnline: Flow<Boolean> = online
    }

    private class FakeAppSettings : AppSettings {
        private val renderMarkdownFlow = MutableStateFlow(true)
        private val fontSizeIndexFlow = MutableStateFlow(1)
        private val hapticFeedbackFlow = MutableStateFlow(true)
        private val soundEffectsFlow = MutableStateFlow(false)
        private val streamingResponsesFlow = MutableStateFlow(true)
        private val memoryEnabledFlow = MutableStateFlow(true)
        private val customInstructionsFlow = MutableStateFlow("")
        private val pushNotificationsEnabledFlow = MutableStateFlow(false)
        private val notificationSoundFlow = MutableStateFlow("Chime")
        private val analyticsEnabledFlow = MutableStateFlow(false)
        private val safeModeEnabledFlow = MutableStateFlow(true)

        override val renderMarkdown: Flow<Boolean> = renderMarkdownFlow
        override suspend fun setRenderMarkdown(value: Boolean) { renderMarkdownFlow.value = value }
        override val fontSizeIndex: Flow<Int> = fontSizeIndexFlow
        override suspend fun setFontSizeIndex(value: Int) { fontSizeIndexFlow.value = value }
        override val hapticFeedback: Flow<Boolean> = hapticFeedbackFlow
        override suspend fun setHapticFeedback(value: Boolean) { hapticFeedbackFlow.value = value }
        override val soundEffects: Flow<Boolean> = soundEffectsFlow
        override suspend fun setSoundEffects(value: Boolean) { soundEffectsFlow.value = value }
        override val streamingResponses: Flow<Boolean> = streamingResponsesFlow
        override suspend fun setStreamingResponses(value: Boolean) { streamingResponsesFlow.value = value }
        override val memoryEnabled: Flow<Boolean> = memoryEnabledFlow
        override suspend fun setMemoryEnabled(value: Boolean) { memoryEnabledFlow.value = value }
        override val customInstructions: Flow<String> = customInstructionsFlow
        override suspend fun setCustomInstructions(value: String) { customInstructionsFlow.value = value }
        override val pushNotificationsEnabled: Flow<Boolean> = pushNotificationsEnabledFlow
        override suspend fun setPushNotificationsEnabled(value: Boolean) { pushNotificationsEnabledFlow.value = value }
        override val notificationSound: Flow<String> = notificationSoundFlow
        override suspend fun setNotificationSound(value: String) { notificationSoundFlow.value = value }
        override val analyticsEnabled: Flow<Boolean> = analyticsEnabledFlow
        override suspend fun setAnalyticsEnabled(value: Boolean) { analyticsEnabledFlow.value = value }
        override val safeModeEnabled: Flow<Boolean> = safeModeEnabledFlow
        override suspend fun setSafeModeEnabled(value: Boolean) { safeModeEnabledFlow.value = value }
    }

    /** Records every call instead of touching NotificationManager — see the "maybeNotifyAgentReply gating" tests below. */
    private class FakeAgentReplyNotifier : ai.openonion.oochat.data.local.AgentReplyNotifier {
        val posted = mutableListOf<Pair<String?, String>>()
        var clearCalls = 0
        override fun notifyAgentReply(agentName: String?, preview: String) { posted += agentName to preview }
        override fun clearReplyNotifications() { clearCalls++ }
    }

    private class FakeImageAttachmentStore : ImageAttachmentStore {
        var storedUris = mutableListOf<String>()
        var shouldFail = false
        var failingUris = emptySet<String>()
        /** When set, store() suspends here first — lets a test interleave work while a "storage" call is still in flight. */
        var blockUntil: CompletableDeferred<Unit>? = null
        override suspend fun store(uriString: String): StoredImage? {
            blockUntil?.await()
            storedUris += uriString
            if (shouldFail || uriString in failingUris) return null
            return StoredImage(localPath = "file:///fake/$uriString.jpg", dataUrl = "data:image/jpeg;base64,FAKE-$uriString")
        }
    }

    private class FakeFileAttachmentStore : ai.openonion.oochat.data.local.FileAttachmentStore {
        var storedUris = mutableListOf<String>()
        /** URIs in this set resolve to [ai.openonion.oochat.data.local.FileAttachResult.TooLarge]. */
        var tooLargeUris = emptySet<String>()
        /** URIs in this set resolve to [ai.openonion.oochat.data.local.FileAttachResult.Failed]. */
        var failingUris = emptySet<String>()
        override suspend fun store(uriString: String): ai.openonion.oochat.data.local.FileAttachResult {
            storedUris += uriString
            return when (uriString) {
                in tooLargeUris -> ai.openonion.oochat.data.local.FileAttachResult.TooLarge("fake-$uriString", 20_000_000L)
                in failingUris -> ai.openonion.oochat.data.local.FileAttachResult.Failed("fake-$uriString")
                else -> ai.openonion.oochat.data.local.FileAttachResult.Success(
                    attachment = ai.openonion.oochat.domain.model.OutgoingFileAttachment(
                        name = "fake-$uriString",
                        data = "data:application/octet-stream;base64,FAKE-$uriString"
                    ),
                    localPath = "file:///fake/$uriString"
                )
            }
        }
    }

    private class FakeVoiceRecorderStore : VoiceRecorderStore {
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        /** Runs inside startRecording(), so a test can see the state the mic opened in. */
        var onStartRecording: () -> Unit = {}
        /** Set by a test to control what the next stopRecording() returns. */
        var nextRecording: RecordedVoice? = RecordedVoice(
            localPath = "file:///fake/voice.m4a",
            durationSeconds = 3f,
            file = File.createTempFile("fake-voice", ".m4a")
        )
        override suspend fun startRecording(): Boolean {
            startCalls++
            onStartRecording()
            return true
        }
        override suspend fun stopRecording(): RecordedVoice? {
            stopCalls++
            return nextRecording
        }
        override suspend fun cancelRecording() {
            cancelCalls++
        }
    }

    /**
     * Drives dictation by hand: [reportedReadiness] picks the recognizer vs the
     * server-transcription path, and [emit] plays events at the collector.
     * [recordProbeResult] caches like the real one, so a test can watch a
     * second dictation skip the probe.
     */
    private class FakeSpeechRecognitionService : SpeechRecognitionService {
        var reportedReadiness = RecognizerReadiness.READY
        var stopCalls = 0
        var listenCalls = 0
        var recordedVerdicts = mutableListOf<Boolean>()
        /** Set to hold readiness() open — the real one takes up to 1.5s on API 33+. */
        var readinessGate: CompletableDeferred<Unit>? = null
        private val events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
        override suspend fun readiness(): RecognizerReadiness {
            readinessGate?.await()
            return reportedReadiness
        }
        override fun recordProbeResult(usable: Boolean) {
            recordedVerdicts += usable
            reportedReadiness = if (usable) RecognizerReadiness.READY else RecognizerReadiness.UNAVAILABLE
        }
        override fun listen(): Flow<SpeechRecognitionEvent> {
            listenCalls++
            return events
        }
        override suspend fun stop() { stopCalls++ }
        fun emit(event: SpeechRecognitionEvent) { check(events.tryEmit(event)) }
    }

    private class FakeVoiceTranscriptionService : VoiceTranscriptionService {
        /** Set by a test: null simulates a failed/empty transcription. */
        var nextTranscript: String? = "hello from voice"
        var transcribeCalls = 0
        override suspend fun transcribe(audioFile: File): Result<String> {
            transcribeCalls++
            return nextTranscript?.let { Result.success(it) } ?: Result.failure(Exception("transcription failed"))
        }
    }

    private class FakeAgentRepository : AgentRepository {
        // Backed by a real StateFlow (not just the createdAgents list) so
        // getAllAgents()/getAgentByAddress() actually reflect what's been
        // created — needed for ChatViewModel.drawerAgents' reactive combine.
        private val agentsFlow = MutableStateFlow<List<AgentProfile>>(emptyList())
        var createdAgents = mutableListOf<AgentProfile>()
        override fun getAllAgents(): Flow<List<AgentProfile>> = agentsFlow
        override fun getActiveAgents(): Flow<List<AgentProfile>> = agentsFlow.map { it.filter { a -> a.isActive } }
        override suspend fun getAgentById(id: String) = agentsFlow.value.find { it.id == id }
        override suspend fun getAgentByAddress(address: String) = agentsFlow.value.find { it.address == address }
        override suspend fun createAgent(agent: AgentProfile): AgentProfile {
            createdAgents += agent
            agentsFlow.value = agentsFlow.value + agent
            return agent
        }
        override suspend fun updateAgent(agent: AgentProfile) {
            agentsFlow.value = agentsFlow.value.map { if (it.id == agent.id) agent else it }
        }
        override suspend fun deleteAgent(agentId: String) {
            agentsFlow.value = agentsFlow.value.filterNot { it.id == agentId }
        }
        override suspend fun updateLastConnected(agentId: String) {}
        override suspend fun getDefaultAgent() = agentsFlow.value.firstOrNull { it.isActive }
        override suspend fun reorderAgents(orderedIds: List<String>) {
            val byId = agentsFlow.value.associateBy { it.id }
            agentsFlow.value = orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(position = index) }
        }

        /** Pre-existing agent, as if saved by an earlier run of the app. */
        fun seed(agent: AgentProfile) { agentsFlow.value = agentsFlow.value + agent }
    }

    private class FakeConfigRepository : ConnectionConfigRepository {
        var configValue: ConnectionConfig? = null
        override suspend fun getConfig() = configValue
        override fun observeConfig() = MutableStateFlow(configValue)
        override suspend fun saveConfig(config: ConnectionConfig) { configValue = config }
        override suspend fun deleteConfig() { configValue = null }
        override suspend fun hasConfig() = configValue != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    /** In-memory [SessionRepository], reached once a Connected state has run
     *  saveAgentIfNeeded() and ConversationHistoryUseCase.ensureActiveSession()
     *  resolves the agent via [FakeAgentRepository.getAgentByAddress]. */
    private class FakeSessionRepository : SessionRepository {
        private val all = MutableStateFlow<List<ChatSession>>(emptyList())
        private var counter = 0

        override fun getAllSessions(): Flow<List<ChatSession>> = all
        override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> =
            all.map { list -> list.filter { it.agentId == agentId }.sortedByDescending { it.updatedAt } }
        override suspend fun getSessionById(id: String) = all.value.find { it.id == id }
        override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
            val session = ChatSession(
                id = id, agentId = agentId, title = title,
                createdAt = 0L, updatedAt = 0L
            )
            all.value = all.value + session
            return session
        }
        override suspend fun deleteSession(sessionId: String) {
            all.value = all.value.filterNot { it.id == sessionId }
        }
        override suspend fun renameSession(sessionId: String, newTitle: String) {
            all.value = all.value.map { if (it.id == sessionId) it.copy(title = newTitle) else it }
        }
        override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
            all.value = all.value.map { if (it.id == sessionId) it.copy(messageCount = count, lastMessagePreview = preview) else it }
        }

        /** Pre-existing conversation, as if saved by an earlier run of the app. */
        fun seed(agentId: String, title: String, messageCount: Int = 0): ChatSession {
            val session = ChatSession(
                id = "session-${++counter}", agentId = agentId, title = title,
                createdAt = 0L, updatedAt = 0L, messageCount = messageCount
            )
            all.value = all.value + session
            return session
        }
    }

    /** In-memory [MessageRepository], mirroring Room's insert-or-replace-by-id semantics. */
    private class FakeMessageRepository : MessageRepository {
        private val all = MutableStateFlow<List<ChatMessage>>(emptyList())

        override suspend fun getMessagesListBySession(sessionId: String) =
            all.value.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
        override suspend fun createMessage(message: ChatMessage) {
            all.value = all.value.filterNot { it.id == message.id } + message
        }
        override suspend fun deleteMessagesBySession(sessionId: String) {
            all.value = all.value.filterNot { it.sessionId == sessionId }
        }
        override suspend fun getMessageCount(sessionId: String) =
            all.value.count { it.sessionId == sessionId }
        override suspend fun existsById(id: String) =
            all.value.any { it.id == id }
        override suspend fun getOwningSessionId(id: String) =
            all.value.firstOrNull { it.id == id }?.sessionId
        override suspend fun getSessionIdByUserContent(content: String) =
            all.value.lastOrNull { it.role == Role.USER && it.content == content }?.sessionId

        /** Pre-existing rows, as if written by an earlier run of the app. */
        fun seed(vararg messages: ChatMessage) { all.value = all.value + messages }

        fun snapshot(): List<ChatMessage> = all.value
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: ChatViewModel
    private lateinit var connectUseCase: FakeConnectToAgentUseCase
    private lateinit var storage: FakeIgnoredIdsStorage
    private lateinit var agentRepo: FakeAgentRepository
    private lateinit var configRepo: FakeConfigRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var imageStore: FakeImageAttachmentStore
    private lateinit var fileStore: FakeFileAttachmentStore
    private lateinit var voiceRecorderStore: FakeVoiceRecorderStore
    private lateinit var voiceTranscriptionService: FakeVoiceTranscriptionService
    private lateinit var speechRecognition: FakeSpeechRecognitionService
    private lateinit var appSettings: FakeAppSettings
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var agentReplyNotifier: FakeAgentReplyNotifier

    // Backing vars for the two gates ChatViewModel reads through lambdas
    // rather than fakeable objects — see maybeNotifyAgentReply's doc. Tests
    // flip these before emitting the event they want gated.
    private var appInForeground = false
    private var notificationPermissionGranted = true

    @Before
    fun setUp() {
        // viewModelScope.launch {} uses Dispatchers.Main; pin it to our
        // test dispatcher so init's collectors run deterministically.
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)

        connectUseCase = FakeConnectToAgentUseCase()
        storage = FakeIgnoredIdsStorage()
        agentRepo = FakeAgentRepository()
        configRepo = FakeConfigRepository()
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        imageStore = FakeImageAttachmentStore()
        fileStore = FakeFileAttachmentStore()
        voiceRecorderStore = FakeVoiceRecorderStore()
        voiceTranscriptionService = FakeVoiceTranscriptionService()
        speechRecognition = FakeSpeechRecognitionService()
        appSettings = FakeAppSettings()
        networkMonitor = FakeNetworkMonitor()
        agentReplyNotifier = FakeAgentReplyNotifier()
        appInForeground = false
        notificationPermissionGranted = true

        vm = ChatViewModel(
            application = Application(),
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectUseCase,
            agentRepository = agentRepo,
            configRepository = configRepo,
            ignoredIdsManager = IgnoredIdsManager(storage),
            imageAttachmentStore = imageStore,
            fileAttachmentStore = fileStore,
            voiceRecorderStore = voiceRecorderStore,
            voiceTranscriptionService = voiceTranscriptionService,
            speechRecognitionService = speechRecognition,
            appSettings = appSettings,
            messageRepository = messageRepo,
            conversationHistory = ConversationHistoryUseCase(
                agentRepo, sessionRepo, messageRepo,
                FakePersistenceTransaction(messageRepo, sessionRepo)
            ),
            initialMyAddress = "0xTEST",
            networkMonitor = networkMonitor,
            agentReplyNotifier = agentReplyNotifier,
            requiresNotificationPermission = true,
            hasNotificationPermission = { notificationPermissionGranted },
            isAppInForeground = { appInForeground },
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── Construction smoke test ────────────────────────────────────────

    @Test
    fun `constructs without throwing and seeds myAddress from constructor param`() {
        assertEquals(
            "0xTEST",
            vm.uiState.value.myAddress
        )
    }

    @Test
    fun `construction never auto-connects on its own`() {
        // Connecting is always caller-driven now (ChatScreen's
        // connectToAgentId-keyed LaunchedEffect calls connectToAgent or
        // connectToAgentByConfig explicitly) — see connectToAgentByConfig's
        // doc for the real race this replaced (an init-time auto-connect
        // that fought an explicit connectToAgent call from a fresh route).
        assertEquals(0, connectUseCase.connectInvocations)
    }

    @Test
    fun `initial uiState has empty chatItems and no error`() {
        val state = vm.uiState.value
        assertTrue(state.chatItems.isEmpty())
        assertNull(state.error)
    }

    // ── sendMessage ────────────────────────────────────────────────────

    @Test
    fun `retrying a failed turn re-sends without a second copy of the user message`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()

        vm.sendMessage("hello")
        testSchedulerKeepRunning()
        val userBubbles = vm.uiState.value.chatItems.count { it is ChatItem.User }
        val sent = connectUseCase.sentMessages.size

        vm.retryFailedTurn("hello")
        testSchedulerKeepRunning()

        assertEquals(
            "Retry must re-issue the request",
            sent + 1,
            connectUseCase.sentMessages.size
        )
        assertEquals(
            "The user's message is already in the transcript; Retry must not echo it again",
            userBubbles,
            vm.uiState.value.chatItems.count { it is ChatItem.User }
        )
    }

    /**
     * The device losing its network is knowable in milliseconds; the socket
     * only finds out when a ping goes unanswered, 15-30s later. These cover
     * the ViewModel's half of that — the grace period itself belongs to
     * [ai.openonion.oochat.network.NetworkMonitorTest].
     */
    @Test
    fun `an outage shorter than the grace period never reaches the banner`() = runTest(testDispatcher) {
        val collected = backgroundScope.launch { vm.isOffline.collect { } }
        testScheduler.runCurrent()

        networkMonitor.online.value = false
        testScheduler.advanceTimeBy(OFFLINE_GRACE_MS)

        assertFalse(vm.isOffline.value)
        collected.cancel()
    }

    @Test
    fun `an outage that outlasts the grace period surfaces, and clears on recovery`() = runTest(testDispatcher) {
        val collected = backgroundScope.launch { vm.isOffline.collect { } }
        testScheduler.runCurrent()

        networkMonitor.online.value = false
        testScheduler.advanceTimeBy(OFFLINE_GRACE_MS + 1)
        assertTrue(vm.isOffline.value)

        networkMonitor.online.value = true
        testScheduler.runCurrent()
        assertFalse(vm.isOffline.value)

        collected.cancel()
    }

    @Test
    fun `the network coming back retries a pending reconnect instead of waiting out the backoff`() = runTest(testDispatcher) {
        // Mirrors onReturnedToForeground below, and the reference web client's
        // `online` listener: the reason the socket failed has just gone away.
        assertEquals(0, connectUseCase.retryNowCalls)

        networkMonitor.online.value = false
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.retryNowCalls)

        networkMonitor.online.value = true
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.retryNowCalls)
    }

    @Test
    fun `returning to the foreground retries a pending reconnect immediately`() {
        // Android aborts a backgrounded app's sockets, so the drop after a trip
        // to the camera is routine — and its cause is gone the moment we are
        // back. Sitting out a backoff computed for a flaky network wastes the
        // one moment the user is watching.
        assertEquals(0, connectUseCase.retryNowCalls)

        vm.onReturnedToForeground()

        assertEquals(1, connectUseCase.retryNowCalls)
    }

    @Test
    fun `sendMessage with blank content is a no-op`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()

        vm.sendMessage("")
        vm.sendMessage("   ")

        val state = vm.uiState.value
        assertTrue("Blank sendMessage must not append a User bubble", state.chatItems.isEmpty())
        assertTrue("Blank sendMessage must not call the use case", connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `sendMessage with no target agent is a no-op and does not call use case`() {
        // No connectToAgent() → _targetAgentAddress stays blank.
        vm.sendMessage("hello")
        assertTrue(vm.uiState.value.chatItems.isEmpty())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `sendMessage while not connected keeps the message and marks it queued`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        // isConnected is still false (we never called markConnected).

        vm.sendMessage("hello")

        val state = vm.uiState.value
        // Refusing here is what made the persisted outbox almost unreachable:
        // the connection layer is what enqueues, and the old guard returned
        // before the message ever reached it. The user was told "not
        // connected" and the message was dropped.
        val bubble = state.chatItems.single() as ChatItem.User
        assertEquals("hello", bubble.content)
        assertEquals(
            "an undelivered message has to say so, not vanish",
            UserMessageState.QUEUED,
            bubble.state
        )
        assertNull("a queued message is not an error the user has to dismiss", state.error)
        assertEquals(
            "it still goes through the connection layer, which is what queues it",
            listOf("hello"),
            connectUseCase.sentMessages.map { it.content }
        )
    }

    @Test
    fun `a send that fails leaves the message on screen, marked, and resendable`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true

        vm.sendMessage("did this go?")
        testScheduler.runCurrent()

        // Nothing on this protocol reports a failed delivery, so if the bubble
        // does not say it, nothing does.
        val failed = vm.uiState.value.chatItems.single() as ChatItem.User
        assertEquals(UserMessageState.FAILED, failed.state)
        assertTrue("a failed send reaches no one", connectUseCase.sentMessages.isEmpty())

        vm.resendMessage(failed.id)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("did this go?"),
            connectUseCase.sentMessages.map { it.content }
        )
    }

    @Test
    fun `resending revives the same bubble instead of adding a second one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true
        vm.sendMessage("only once")
        testScheduler.runCurrent()
        val id = (vm.uiState.value.chatItems.single() as ChatItem.User).id

        vm.resendMessage(id)
        testScheduler.advanceUntilIdle()

        // Retry was wired straight to sendMessage once, and every tap appended
        // a second copy of the same message. Reusing the id is what prevents
        // that, and is also the only stable handle on an outgoing message —
        // the wire's input_id is regenerated per attempt.
        val bubbles = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>()
        assertEquals("one message, one bubble, however many attempts", 1, bubbles.size)
        assertEquals(id, bubbles.single().id)
        assertEquals(UserMessageState.SENT, bubbles.single().state)
    }

    @Test
    fun `resending a message that did not fail is a no-op`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        vm.sendMessage("fine")
        testScheduler.runCurrent()
        val id = (vm.uiState.value.chatItems.single() as ChatItem.User).id

        vm.resendMessage(id)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "a delivered message must not be sendable twice by a stray tap",
            1,
            connectUseCase.sentMessages.size
        )
    }

    @Test
    fun `sendMessage when connected appends a User bubble and forwards to use case`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("hello world")

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val user = items[0] as ChatItem.User
        assertEquals("hello world", user.content)
        assertEquals(
            "sendMessage must forward to the use case",
            listOf(FakeConnectToAgentUseCase.SentMessage("hello world", address, null)),
            connectUseCase.sentMessages
        )
    }

    @Test
    fun `sendMessage prepends custom instructions to the wire payload only, never to the bubble or persisted content`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            appSettings.setCustomInstructions("Always answer in pirate speak.")
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("hello world")

            val user = vm.uiState.value.chatItems.single() as ChatItem.User
            assertEquals("the visible bubble must show only what the user typed", "hello world", user.content)
            assertEquals(
                "the instructions must be prepended only on the way to the agent",
                "Always answer in pirate speak.\n\nhello world",
                connectUseCase.sentMessages.single().content
            )
        }

    @Test
    fun `sendMessage with images-only content stores, shows a local bubble, and forwards base64 to the use case`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("", images = listOf("content://picker/1", "content://picker/2"))

            val items = vm.uiState.value.chatItems
            assertEquals(1, items.size)
            val user = items[0] as ChatItem.User
            assertEquals("", user.content)
            assertEquals(
                "the bubble must show the store's durable local path, not the raw picker URI",
                listOf("file:///fake/content://picker/1.jpg", "file:///fake/content://picker/2.jpg"),
                user.images
            )
            assertEquals(listOf("content://picker/1", "content://picker/2"), imageStore.storedUris)
            assertEquals(
                "an images-only (blank content) message must still reach the use case",
                listOf(
                    FakeConnectToAgentUseCase.SentMessage(
                        "",
                        address,
                        listOf("data:image/jpeg;base64,FAKE-content://picker/1", "data:image/jpeg;base64,FAKE-content://picker/2")
                    )
                ),
                connectUseCase.sentMessages
            )
        }

    @Test
    fun `sendMessage drops the bubble and surfaces an error when every attached image fails to store`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            imageStore.shouldFail = true

            vm.sendMessage("", images = listOf("content://picker/1"))

            assertTrue(
                "no blank ghost bubble must survive when every image failed to store",
                vm.uiState.value.chatItems.isEmpty()
            )
            assertNotNull("the user must be told the attachment failed", vm.uiState.value.error)
            assertTrue(
                "nothing meaningful to send when content is blank and every image failed",
                connectUseCase.sentMessages.isEmpty()
            )
        }

    @Test
    fun `sendMessage surfaces a non-blocking error when only some attached images fail to store`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            imageStore.failingUris = setOf("content://picker/bad")

            vm.sendMessage("caption", images = listOf("content://picker/good", "content://picker/bad"))

            val items = vm.uiState.value.chatItems
            assertEquals(1, items.size)
            val user = items[0] as ChatItem.User
            assertEquals(
                "the surviving image must still be shown/sent, just not the failed one",
                listOf("file:///fake/content://picker/good.jpg"),
                user.images
            )
            assertNotNull("a partial failure must still surface something to the user", vm.uiState.value.error)
            assertEquals(
                "the message must still send with whatever images survived",
                1,
                connectUseCase.sentMessages.single().images?.size
            )
        }

    @Test
    fun `sendMessage pins the target session so a mid-flight session switch doesn't misfile the persisted message`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            // Lazy session creation: nothing has been sent yet, so there's no
            // real session at this point — activeSessionId is still null.
            assertNull(vm.activeSessionId.value)

            // Block image storage mid-flight so a session switch can land
            // while sendMessage's coroutine is still suspended on it.
            val gate = CompletableDeferred<Unit>()
            imageStore.blockUntil = gate

            vm.sendMessage("", images = listOf("content://picker/1"))
            testScheduler.runCurrent()
            // sendMessage's coroutine has started and resolved (materialized)
            // its target session by now, before the image-store gate, even
            // though it's still suspended waiting on that gate.
            val firstSessionId = vm.activeSessionId.value!!

            vm.startNewSession()
            testScheduler.runCurrent()
            // Nothing has been sent in the new session, so — same lazy
            // behavior — it stays un-materialized; activeSessionId going
            // back to null is itself proof it no longer points at
            // firstSessionId.
            assertNull(vm.activeSessionId.value)

            gate.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(
                "the message must land in the session it was actually sent from",
                1,
                messageRepo.getMessagesListBySession(firstSessionId).size
            )
        }

    // ── Voice input ────────────────────────────────────────────────────
    //
    // Voice fills the composer and never sends: there is no ChatItem, no
    // audio in the transcript, and nothing reaches the agent until the user
    // presses Send on text they have read.

    @Test
    fun `dictation takes the recognizer when one is available, not the recorder`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            assertEquals(0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `dictation falls back to recording plus server transcription with no recognizer`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
        }

    // Tapping the mic used to declare LISTENING on the spot, with the timer
    // running and the meter drawing over a microphone nothing had opened yet.
    // Everything said in that window was lost — up to 2.5s of it on a device
    // whose recognizer binds and never answers.

    @Test
    fun `the mic tap does not claim to be listening until the recognizer answers`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(
                "no callback has arrived, so no microphone is open",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )

            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the fallback path is preparing right up until the recorder is open`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            var phaseWhenMicOpened: VoiceInputPhase? = null
            voiceRecorderStore.onStartRecording = { phaseWhenMicOpened = vm.voiceInput.value.phase }

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(
                "the recorder itself is what ends the wait, so it cannot already be over",
                VoiceInputPhase.PREPARING,
                phaseWhenMicOpened
            )
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the whole probe window is preparing, and the fallback still announces itself`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS - 1)
            testScheduler.runCurrent()
            assertEquals(
                "still nothing capturing audio a moment before the watchdog fires",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )
            assertNull("nothing to announce until a path is actually taken", vm.voiceInput.value.notice)

            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()

            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
        }

    @Test
    fun `cancelling while preparing leaves no watchdog and no recorder behind`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 2)
            testScheduler.runCurrent()

            assertEquals(
                "the watchdog died with the job; nothing may open a mic behind the user",
                0,
                voiceRecorderStore.startCalls
            )
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `cancelling before a path is even chosen releases whatever the last one left`() =
        runTest(testDispatcher) {
            // The readiness query itself is part of the preparing window, and on
            // API 33+ it can hold the tap for over a second on its own.
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            val gate = CompletableDeferred<Unit>()
            speechRecognition.readinessGate = gate

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            gate.complete(Unit)
            testScheduler.runCurrent()

            assertEquals("the recorder is released even though no path had been picked", 1, voiceRecorderStore.cancelCalls)
            assertEquals("and the abandoned query never opens one", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    // ── the recorder is disk I/O, and the UI must not wait on it ──
    //
    // VoiceRecorderStoreImpl writes through RandomAccessFile, deletes files and
    // joins its capture thread with a one-second bound; every call came straight
    // off a UI callback. Main is a *queuing* dispatcher for the two tests below
    // — with the class's usual Unconfined one, a call that had left the caller's
    // stack and one that had not would look identical.

    @Test
    fun `finishing a dictation does not stop the recorder on the caller's stack`() =
        runTest(testDispatcher) {
            kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.advanceUntilIdle()
            assertEquals("test setup: the fallback recorder must be open", 1, voiceRecorderStore.startCalls)

            vm.finishVoiceInput()

            assertEquals(
                "stopRecording joins the capture thread and rewrites the WAV header — " +
                    "the ANR candidate, and it ran before finishVoiceInput even returned",
                0,
                voiceRecorderStore.stopCalls
            )
            testScheduler.advanceUntilIdle()
            assertEquals("but it must still happen", 1, voiceRecorderStore.stopCalls)
        }

    @Test
    fun `cancelling a dictation does not delete the recording on the caller's stack`() =
        runTest(testDispatcher) {
            kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.advanceUntilIdle()

            vm.cancelVoiceRecording()

            assertEquals(
                "cancelRecording joins the capture thread and deletes the file",
                0,
                voiceRecorderStore.cancelCalls
            )
            assertEquals(
                "the row still has to go the instant the user taps cancel",
                VoiceInputPhase.IDLE,
                vm.voiceInput.value.phase
            )
            testScheduler.advanceUntilIdle()
            assertEquals("and the recorder is still released", 1, voiceRecorderStore.cancelCalls)
        }

    // A registered recognizer is not a working one: some devices ship a stub
    // that binds, takes the microphone and never calls back at all — not even
    // onError — so the dictation used to hang until the user gave up.

    @Test
    fun `a recognizer that never calls back hands the dictation to server transcription`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals("the probe is the dictation, so nothing starts recording yet", 0, voiceRecorderStore.startCalls)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals(
                "the user is still holding the mic down; don't make them start over",
                VoiceInputPhase.LISTENING,
                vm.voiceInput.value.phase
            )
            assertNull(vm.voiceInput.value.error)
        }

    @Test
    fun `a recognizer that answers keeps the dictation, however long the user pauses`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 4)
            testScheduler.runCurrent()

            assertEquals("a silent speaker is not a silent recognizer", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertNull("nothing changed, so there is nothing to tell the user", vm.voiceInput.value.notice)
            assertEquals(listOf(true), speechRecognition.recordedVerdicts)
        }

    // READY is the recognizer's own account of itself — checkRecognitionSupport
    // said so — and an API 34 emulator has answered READY and then emitted
    // nothing at all. Only a callback is evidence, so this path is watched too.

    @Test
    fun `a READY recognizer that never calls back falls back and announces it`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals("the recognizer is being given its chance", 0, voiceRecorderStore.startCalls)

            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals(
                "the user is still holding the mic down; don't make them start over",
                VoiceInputPhase.LISTENING,
                vm.voiceInput.value.phase
            )
            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
            assertNull("a working fallback is not a failure", vm.voiceInput.value.error)
        }

    @Test
    fun `a READY recognizer gets the whole window before it is given up on`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS - 1)
            testScheduler.runCurrent()
            assertEquals(
                "a cold recognition service takes ~1.7s; that is slow, not dead",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )

            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 4)
            testScheduler.runCurrent()

            assertEquals("it answered, so it keeps the dictation", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertNull("nothing changed, so there is nothing to tell the user", vm.voiceInput.value.notice)
        }

    @Test
    fun `a READY recognizer proved silent is not asked again this session`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            vm.cancelVoiceRecording()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("READY was disproved by silence, and that verdict sticks", 1, speechRecognition.listenCalls)
            assertEquals("and the recorder starts without waiting again", 2, voiceRecorderStore.startCalls)
        }

    @Test
    fun `cancelling during the READY wait leaves no watchdog and no recorder behind`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS * 2)
            testScheduler.runCurrent()

            assertEquals(
                "the watchdog died with the job; nothing may open a mic behind the user",
                0,
                voiceRecorderStore.startCalls
            )
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `an unproven recognizer still gets the longer wait than a READY one`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertTrue(
                "a recognizer that never claimed readiness is given more rope, not less",
                RECOGNIZER_READY_TIMEOUT_MS < RECOGNIZER_PROBE_TIMEOUT_MS
            )
            assertEquals("the unproven window is not over yet", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the silent verdict is remembered, so the next dictation costs no wait`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            vm.cancelVoiceRecording()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("the recognizer must not be probed a second time", 1, speechRecognition.listenCalls)
            assertEquals("and the recorder starts without waiting out the probe", 2, voiceRecorderStore.startCalls)
        }

    @Test
    fun `stopping before the recognizer ever answers does not open the recorder afterwards`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals("the user already stopped; don't reopen the mic behind them", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `the switch to server transcription is announced once and then dropped`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
            assertNull("a working fallback is not a failure", vm.voiceInput.value.error)

            vm.cancelVoiceRecording()
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertNull("saying it every dictation is nagging", vm.voiceInput.value.notice)
        }

    @Test
    fun `partial results reach the composer while the user is still speaking`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Partial("book a"))
            testScheduler.runCurrent()
            assertEquals("book a", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)

            // Cumulative, not a delta — the composer recomputes from its anchor.
            speechRecognition.emit(SpeechRecognitionEvent.Partial("book a table"))
            testScheduler.runCurrent()
            assertEquals("book a table", vm.voiceInput.value.transcript)
        }

    @Test
    fun `a finished dictation lands in the composer and sends nothing`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Final("book a table for two"))
            testScheduler.runCurrent()

            assertEquals("book a table for two", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertTrue(
                "voice must not post a chat item of its own",
                vm.uiState.value.chatItems.isEmpty()
            )
            assertTrue(
                "nothing reaches the agent until the user presses Send",
                connectUseCase.sentMessages.isEmpty()
            )
        }

    @Test
    fun `finishVoiceInput asks the recognizer to settle rather than stopping the recorder`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            // There is nothing to settle before the recognizer answers, so the
            // dictation has to be genuinely running for this to be the case.
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals(1, speechRecognition.stopCalls)
        }

    @Test
    fun `finishing while preparing discards instead of settling a recognizer that never started`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals("nothing was captured, so there is nothing to settle", 0, speechRecognition.stopCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertEquals("", vm.voiceInput.value.transcript)
        }

    @Test
    fun `a failed dictation keeps the partials it already delivered`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Partial("half a sentence"))
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            assertEquals("half a sentence", vm.voiceInput.value.transcript)
            assertNull("a timeout after real words is not an error worth showing", vm.voiceInput.value.error)
        }

    @Test
    fun `a dictation that heard nothing at all reports why`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            assertEquals("Didn't catch that. Try again.", vm.voiceInput.value.error)
        }

    @Test
    fun `the composer taking the transcript does not wipe the error line with it`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            // The composer merges and reports back in the same frame; an error
            // cleared there would never be on screen long enough to read.
            vm.consumeVoiceTranscript()

            assertEquals("Didn't catch that. Try again.", vm.voiceInput.value.error)
        }

    @Test
    fun `starting the next dictation clears the last one's error`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()
            vm.consumeVoiceTranscript()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertNull(vm.voiceInput.value.error)
        }

    @Test
    fun `cancelling a dictation throws the transcript away`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Partial("never mind"))
            testScheduler.runCurrent()

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `the fallback path transcribes the recording and puts the text in the composer`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            voiceTranscriptionService.nextTranscript = "hello from voice"
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals("hello from voice", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `cancelVoiceRecording releases the recorder on the fallback path`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.cancelVoiceRecording()

            assertEquals(1, voiceRecorderStore.cancelCalls)
        }

    // ── Interjection ───────────────────────────────────────────────────

    /**
     * A message sent into a running turn is acknowledged by the relay with
     * `RUNTIME_INPUT_ACK` and then only read if that turn starts another
     * iteration. Measured both ways on device against one agent with the
     * `runtime_input` plugin enabled: a turn that called a tool picked the
     * message up and answered it; a plain question ran a single iteration and
     * never did. So it waits for the turn instead.
     */
    private fun startTurn(id: String) = ChatEvent.ChatItemReceived(
        ChatItem.Turn(id = id, thinking = ChatItem.Thinking(id = id, status = ThinkingStatus.RUNNING))
    )

    /** Ends a turn the way the wire does — the OUTPUT, not the thinking status. */
    private suspend fun FakeConnectToAgentUseCase.endTurn() =
        emit(ChatEvent.OutputReceived("done", null))

    @Test
    fun `a message typed mid-run is held until the turn ends, not sent into it`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()
            assertTrue("premise: the agent is mid-run", vm.isAgentWorking.value)

            vm.sendMessage("actually, use the staging URL")
            testScheduler.runCurrent()

            assertEquals(
                "only the first has reached the wire",
                listOf("first question"),
                connectUseCase.sentMessages.map { it.content }
            )
            val queued = vm.uiState.value.chatItems.last() as ChatItem.User
            assertEquals("actually, use the staging URL", queued.content)
            assertTrue("the bubble is up, and says it is waiting", queued.queued)

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(
                "once the turn is over it goes out as an ordinary INPUT",
                listOf("first question", "actually, use the staging URL"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertTrue(
                "and stops claiming to be waiting",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().none { it.queued }
            )
        }

    /**
     * Reproduces a hang seen on a device that kills the socket seconds after
     * the app is backgrounded: the turn's OUTPUT never came back, and the send
     * gate — cleared only where OUTPUT arrives — held every later message
     * behind a turn that had already ended. An auto-reconnect goes through
     * Reconnecting, never Disconnected, so none of the disconnect recovery ran.
     */
    @Test
    fun `a turn cut off by a reconnect stops holding later messages`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()
            assertTrue("premise: the agent is mid-run", vm.isAgentWorking.value)

            // The socket dies and the ladder reconnects. No OUTPUT ever lands.
            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            vm.sendMessage("are you still there")
            testScheduler.runCurrent()

            assertEquals(
                "the second message goes out instead of waiting on a dead turn",
                listOf("first question", "are you still there"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertTrue(
                "and no bubble is left claiming to be waiting",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().none { it.queued }
            )
        }

    @Test
    fun `the closing message after Stop lands in the turn that was stopped`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("open a website")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.interrupt()
            testScheduler.runCurrent()
            // Interrupt is a request: the agent finishes its step and answers.
            connectUseCase.emit(ChatEvent.OutputReceived("Stopped. What next?", null))
            testScheduler.runCurrent()

            val turns = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
            assertEquals("the reply belongs in the stopped turn, not beside it", 1, turns.size)
            assertEquals("Stopped. What next?", turns.single().agent?.content)
            assertTrue(
                "no stray reply bubble left carrying the answer on its own",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.Agent>().isEmpty()
            )
        }

    @Test
    fun `Stop reopens the send gate, not just the spinners`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.interrupt()
            testScheduler.runCurrent()
            vm.sendMessage("never mind, different question")
            testScheduler.runCurrent()

            assertEquals(
                "a message after Stop is not held behind the turn Stop ended",
                listOf("first question", "never mind, different question"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    @Test
    fun `what the running turn still emits lands above the message waiting on it`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.sendMessage("and another thing")
            testScheduler.runCurrent()
            // turn-1's answer arrives after the second message was typed. It
            // answers the *first* one, so appending it would put it under the
            // wrong question — which is what it did on device.
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Agent(id = "answer-1", content = "the answer to the first"))
            )
            testScheduler.runCurrent()

            val contents = vm.uiState.value.chatItems.map {
                when (it) {
                    is ChatItem.User -> it.content
                    is ChatItem.Agent -> it.content
                    else -> it.id
                }
            }
            assertEquals(
                listOf("first question", "turn-1", "the answer to the first", "and another thing"),
                contents
            )
        }

    @Test
    fun `a held message is written to the transcript when it is sent, not when it is typed`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.sendMessage("and another thing")
            testScheduler.runCurrent()

            // The row carries the time it was written, and the transcript is
            // ordered by that. Writing it at the tap dated it *before* the
            // reply it is waiting on, so a reload — or any rebuild from the
            // persisted order — showed both questions, then both answers.
            assertEquals(
                "a held message must not be in the transcript yet",
                listOf("first question"),
                messageRepo.snapshot().map { it.content }
            )

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            // The reply sits between the two questions, which is the whole
            // point: written at the tap, the held message would have been
            // dated before it and rendered above it.
            assertEquals(
                "the reply lands between the question it answers and the one that waited",
                listOf("first question", "done", "and another thing"),
                messageRepo.snapshot().map { it.content }
            )
        }

    @Test
    fun `two messages typed mid-run go out one turn at a time`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.sendMessage("second")
            vm.sendMessage("third")
            testScheduler.runCurrent()
            assertEquals(1, connectUseCase.sentMessages.size)

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            // Releasing both at once would put the third straight into the turn
            // the second just started — the bug this exists to prevent.
            assertEquals(
                listOf("first question", "second"),
                connectUseCase.sentMessages.map { it.content }
            )

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(
                listOf("first question", "second", "third"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    // ── clearChat ──────────────────────────────────────────────────────

    @Test
    fun `clearChat empties chatItems and new messages still mint distinct ids`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("one")
        vm.sendMessage("two")
        vm.sendMessage("three")
        assertEquals(3, vm.uiState.value.chatItems.size)
        val idsBeforeClear = vm.uiState.value.chatItems.map { it.id }.toSet()

        vm.clearChat()
        // clearChat awaits the storage save before wiping the UI; let
        // the suspending body finish.
        testScheduler.runCurrent()

        assertTrue("chatItems must be empty after clearChat", vm.uiState.value.chatItems.isEmpty())

        // Message ids are UUIDs (see ChatViewModel.sendMessage) — no
        // per-instance counter to "reset" anymore, so just confirm the
        // next message mints a fresh id, not one reused from before.
        vm.sendMessage("again")
        val newItems = vm.uiState.value.chatItems
        assertEquals(1, newItems.size)
        assertTrue(newItems[0].id !in idsBeforeClear)
    }

    @Test
    fun `clearChat persists the snapshot of current ids to storage`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("hi")
        val firstId = vm.uiState.value.chatItems[0].id
        vm.clearChat()
        testScheduler.runCurrent()

        val saved = storage.loadAll()[address]
        assertNotNull("Storage must have an entry for the current agent", saved)
        assertTrue(
            "Persisted ids must contain the cleared user bubble id",
            firstId in saved!!
        )
    }

    @Test
    fun `clearChat calls reset on the use case before wiping the UI`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        vm.clearChat()
        testScheduler.runCurrent()

        assertTrue(
            "clearChat must reset the use case (suppresses session_sync replay)",
            connectUseCase.resetCount >= 1
        )
    }

    // ── ignoredIds semantics ───────────────────────────────────────────

    @Test
    fun `ChatItemReceived after clearChat with same id is filtered out`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        // Round 1: receive an item, then clear.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "stable-id-1", content = "first reply"))
        )
        testScheduler.runCurrent()
        assertEquals(1, vm.uiState.value.chatItems.size)
        vm.clearChat()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())

        // Round 2: server replays the SAME item (e.g. session_sync on
        // reconnect). Must be suppressed by the ignored-ids set.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "stable-id-1", content = "first reply"))
        )
        testScheduler.runCurrent()

        assertTrue(
            "Replay of a cleared id must NOT re-appear in chatItems",
            vm.uiState.value.chatItems.isEmpty()
        )
    }

    @Test
    fun `clearChat in agent A does not filter replays in agent B`() = runTest(testDispatcher) {
        val addressA = validHexAddress(0xA)
        val addressB = validHexAddress(0xB)

        // Connect to A, receive an item, clear.
        vm.connectToAgent(addressA)
        testScheduler.runCurrent()
        connectUseCase.markConnected(addressA)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "shared-id", content = "from-A"))
        )
        testScheduler.runCurrent()
        vm.clearChat()
        testScheduler.runCurrent()

        // Switch to agent B and replay the same id. Must NOT be filtered
        // (the clear was scoped to agent A).
        vm.connectToAgent(addressB)
        testScheduler.runCurrent()
        connectUseCase.markConnected(addressB)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "shared-id", content = "from-B"))
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "Cross-agent isolation: B's reply with the same id must render",
            1,
            items.size
        )
        assertEquals("from-B", (items[0] as ChatItem.Agent).content)
    }

    // ── ChatItemReceived / ChatItemUpdated / OutputReceived ────────────

    @Test
    fun `ChatItemReceived appends the item to chatItems`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "a1", content = "hi"))
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        assertEquals("hi", (items[0] as ChatItem.Agent).content)
    }

    @Test
    fun `agentReplyArrived fires exactly once for a genuinely new agent item, not at all for one already persisted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            val received = mutableListOf<Unit>()
            val job = launch { vm.agentReplyArrived.collect { received.add(Unit) } }
            testScheduler.runCurrent()

            // Genuinely new id — not yet in the fake Room repository.
            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()
            assertEquals(1, received.size)

            // Same id re-delivered (session_sync replay) — already persisted
            // by the first emission above, so this must NOT fire again.
            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()
            assertEquals(1, received.size)

            job.cancel()
        }

    @Test
    fun `agentReplyArrived does not fire for a user item or a blank-content agent marker`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        val received = mutableListOf<Unit>()
        val job = launch { vm.agentReplyArrived.collect { received.add(Unit) } }
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.User(id = "u1", content = "hi from me")))
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "blank-1", content = "")))
        testScheduler.runCurrent()

        assertEquals(0, received.size)
        job.cancel()
    }

    // ── maybeNotifyAgentReply gating ────────────────────────────────────
    // All three must hold before a system notification is posted: the app
    // is backgrounded, the pref is on, and the OS permission is granted.
    // agentReplyArrived (haptics/sound) above fires regardless — these tests
    // only cover the separate agentReplyNotifier call.

    @Test
    fun `does not post while the app is foregrounded, even with the setting on and permission granted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = true
            appSettings.setPushNotificationsEnabled(true)
            notificationPermissionGranted = true

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `does not post when the push notifications setting is off, even backgrounded with permission granted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = false
            appSettings.setPushNotificationsEnabled(false)
            notificationPermissionGranted = true

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `does not post when backgrounded and the setting is on but the OS permission is denied`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = false
            appSettings.setPushNotificationsEnabled(true)
            notificationPermissionGranted = false

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `posts exactly once when backgrounded, setting on, and permission granted`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        appInForeground = false
        appSettings.setPushNotificationsEnabled(true)
        notificationPermissionGranted = true

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
        testScheduler.runCurrent()

        assertEquals(1, agentReplyNotifier.posted.size)
        assertEquals("hello", agentReplyNotifier.posted[0].second)
    }

    @Test
    fun `onReturnedToForeground clears any posted reply notification`() = runTest(testDispatcher) {
        vm.onReturnedToForeground()
        assertEquals(1, agentReplyNotifier.clearCalls)
    }

    @Test
    fun `ChatItemUpdated replaces existing item by id`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)
            )
        )
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, durationMs = 123.0)
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val thinking = items[0] as ChatItem.Thinking
        assertEquals(ThinkingStatus.DONE, thinking.status)
        assertEquals(123.0, thinking.durationMs)
    }

    @Test
    fun `ChatItemUpdated appends when no item with the same id exists yet`() = runTest(testDispatcher) {
        // Fallback path: server emits an update frame without a
        // preceding "received" (e.g. llm_result for a Thinking bubble
        // we never observed locally).
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Thinking(id = "ghost", status = ThinkingStatus.DONE)
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "Update-without-prior-receive should still land as a single bubble",
            1,
            items.size
        )
        assertEquals("ghost", items[0].id)
    }

    // ── Streaming responses toggle ──────────────────────────────────────

    @Test
    fun `disabling streaming hides in-progress items and they reappear when re-enabled`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)))
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tc1", name = "search", status = ToolStatus.RUNNING)))
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "a1", content = "done reply")))
        testScheduler.runCurrent()
        assertEquals(3, vm.uiState.value.chatItems.size)

        appSettings.setStreamingResponses(false)
        testScheduler.runCurrent()

        val visible = vm.uiState.value.chatItems
        assertEquals(
            "only the finished Agent item should remain once streaming is off",
            listOf("a1"),
            visible.map { it.id }
        )

        appSettings.setStreamingResponses(true)
        testScheduler.runCurrent()

        assertEquals(
            "all items reappear once streaming is re-enabled",
            setOf("t1", "tc1", "a1"),
            vm.uiState.value.chatItems.map { it.id }.toSet()
        )
    }

    @Test
    fun `disabling streaming does not hide a finished ToolCall or a Turn with a completed agent reply`() =
        runTest(testDispatcher) {
            vm.connectToAgent(validHexAddress())
            testScheduler.runCurrent()

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tc1", name = "search", status = ToolStatus.DONE)))
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(
                    ChatItem.Turn(id = "turn1", agent = ChatItem.Agent(id = "a1", content = "finished"))
                )
            )
            testScheduler.runCurrent()

            appSettings.setStreamingResponses(false)
            testScheduler.runCurrent()

            assertEquals(
                "finished work must stay visible even with streaming off",
                setOf("tc1", "turn1"),
                vm.uiState.value.chatItems.map { it.id }.toSet()
            )
        }

    @Test
    fun `OutputReceived creates a Turn item when no matching bubble exists`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived(result = "  hello  ", session = null))
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val turn = items[0] as ChatItem.Turn
        assertNotNull(turn.agent)
        assertEquals("hello", turn.agent!!.content)
    }

    @Test
    fun `OutputReceived with already-rendered content does not duplicate the bubble`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        // Seed an existing Agent bubble with the same content.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "x", content = "hello"))
        )
        testScheduler.runCurrent()

        // Output for the same content must NOT add another bubble.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()

        val agentItems = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Agent>()
        val turnItems = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
        assertEquals("Only the original Agent bubble should remain", 1, agentItems.size)
        assertTrue("No duplicate Turn should be appended", turnItems.isEmpty())
    }

    @Test
    fun `OutputReceived after clearChat with same content is suppressed`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        // Round 1: receive the output, then clear.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()
        assertEquals(1, vm.uiState.value.chatItems.size)
        vm.clearChat()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())

        // Round 2: server replays the same OUTPUT. Suppressed.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()
        assertTrue(
            "Replayed OUTPUT for cleared content must NOT re-appear",
            vm.uiState.value.chatItems.isEmpty()
        )
    }

    // ── Connection-state reactions ─────────────────────────────────────

    @Test
    fun `ConnectionState Error (a genuine fatal connection failure) with nothing in flight appends a standalone failed item`() = runTest(testDispatcher) {
        // ConnectionState.Error now only reaches ChatViewModel for a real
        // transport/connect failure (e.g. ConnectionState.Error.fromException) —
        // a server-sent "ERROR" business-logic frame (credits, etc.) goes
        // through ChatEvent.ConnectionErrorOccurred instead (see below) since
        // it doesn't mean the connection actually died. Either way, no prior
        // RUNNING Thinking/Turn reads as part of the conversation (a
        // synthesized failed Thinking item) rather than a uiState.error
        // banner/snackbar floating outside the message list.
        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()

        assertNull("uiState.error is no longer used for this path", vm.uiState.value.error)
        val failed = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>()
            .find { it.status == ThinkingStatus.ERROR }
        assertNotNull("Expected a synthesized failed Thinking item", failed)
        assertEquals("boom", failed?.content)
    }

    @Test
    fun `ConnectionState Error with a RUNNING Thinking bubble flips it to ERROR with the error message`() = runTest(testDispatcher) {
        val thinkingId = "thinking-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING, model = "gemini-3-flash-preview"))
        )
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Error(message = "TLS handshake failed"))
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.error)
        val thinking = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().single { it.id == thinkingId }
        assertEquals(ThinkingStatus.ERROR, thinking.status)
        assertEquals("TLS handshake failed", thinking.content)
    }

    @Test
    fun `starting a new session fails a turn still running in the one being left`() = runTest(testDispatcher) {
        val thinkingId = "thinking-switch-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING, model = "gemini-3-flash-preview"))
        )
        testScheduler.runCurrent()

        vm.startNewSession()
        testScheduler.runCurrent()

        // The item left with the previous conversation, so it is gone from the
        // cleared list — what matters is that it was resolved on the way out
        // and not persisted as RUNNING.
        assertTrue(
            "the conversation being left must not keep a RUNNING Thinking",
            vm.uiState.value.chatItems.none { it is ChatItem.Thinking && it.status == ThinkingStatus.RUNNING }
        )
    }

    @Test
    fun `ConnectionState Error for a stale-session rejection is not surfaced as a chat item`() = runTest(testDispatcher) {
        // ConnectToAgentUseCase.connect()'s first attempt deliberately lets
        // this specific error reach connectionState.value transiently (so its
        // own fast-fail-and-retry logic can detect it without waiting the
        // full timeout — see its own doc), then silently drops the stale
        // sessionId and reconnects fresh. That auto-recovery is meant to be
        // invisible to the user; before this check it left a permanent
        // "Session is already attached…" error card in the chat even though
        // the very next state transition is a normal Connected. Caught
        // on-device.
        connectUseCase.forceState(ConnectionState.Error(message = "Session is already attached to another connection"))
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.error)
        assertTrue(
            "Stale-session errors must not become a visible chat item",
            vm.uiState.value.chatItems.none { it is ChatItem.Thinking && it.status == ThinkingStatus.ERROR }
        )
    }

    @Test
    fun `ChatEvent ConnectionErrorOccurred (eg Insufficient Credits) attaches to a RUNNING Turn without touching uiState error`() = runTest(testDispatcher) {
        // The actual path a server "ERROR" frame takes today (see
        // ConnectionRepositoryImpl) — deliberately NOT a ConnectionState
        // transition, since this class of error doesn't mean the socket
        // died (verified against production: PING/PONG continues after it).
        val turnId = "turn-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING)))
        )
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"))
        testScheduler.runCurrent()

        assertNull("A business-logic ERROR frame must not surface via uiState.error", vm.uiState.value.error)
        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertEquals(ThinkingStatus.ERROR, turn.thinking?.status)
        assertEquals("Insufficient ConnectOnion Credits", turn.thinking?.content)
    }

    @Test
    fun `real event-collector cancellation does not surface as a uiState error`() = runTest(testDispatcher) {
        // Regression test for Phase 5: the event collector used to be a bare
        // try/catch(Exception), which caught CancellationException (e.g. from
        // the ViewModel's scope being cancelled mid-collect) and wrote a
        // bogus "Connection lost" error — right as the screen observing it
        // was being torn down. Now it's wrapped in runCatchingCancellable,
        // which rethrows CancellationException instead of reporting it.
        connectUseCase.emit(ChatEvent.Waiting) // prove the collector is alive first
        testScheduler.runCurrent()
        assertNull(vm.uiState.value.error)

        connectUseCase.throwCancellationOnNextEvent = true
        connectUseCase.emit(ChatEvent.Waiting)
        testScheduler.runCurrent()

        assertNull(
            "A real CancellationException from the collector must not populate uiState.error",
            vm.uiState.value.error
        )
    }

    @Test
    fun `clearError wipes uiState error`() {
        // Through a real error path rather than by touching _uiState: an
        // attachment that fails to store is the shortest one that does not
        // depend on connection state.
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()
        imageStore.shouldFail = true
        vm.sendMessage("", images = listOf("content://broken"))
        testSchedulerKeepRunning()
        assertNotNull(vm.uiState.value.error)

        vm.clearError()
        assertNull("clearError must wipe the error", vm.uiState.value.error)
    }

    // ── Forwarders ─────────────────────────────────────────────────────

    @Test
    fun `disconnect forwards to the use case`() = runTest(testDispatcher) {
        vm.disconnect()
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.disconnectCount)
    }

    @Test
    fun `respond forwards answer to the use case`() = runTest(testDispatcher) {
        vm.respond("my answer")
        testScheduler.runCurrent()
        assertEquals("my answer", connectUseCase.lastRespond)
    }

    @Test
    fun `interrupt forwards to the use case`() = runTest(testDispatcher) {
        vm.interrupt()
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `interrupt optimistically flips a RUNNING Thinking bubble to DONE`() = runTest(testDispatcher) {
        // Regression coverage for the "no way to stop a running agent turn"
        // gap — see ChatViewModel.interrupt/markRunningItemsDone's own doc.
        // This flip is optimistic only (not persisted): the agent's real
        // closing event, once it arrives, overwrites the same item id
        // through the normal reducer path.
        val thinkingId = "thinking-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        vm.interrupt()
        testScheduler.runCurrent()

        val thinking = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().single { it.id == thinkingId }
        assertEquals(ThinkingStatus.DONE, thinking.status)
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `interrupt optimistically flips a RUNNING Turn's nested thinking to DONE`() = runTest(testDispatcher) {
        val turnId = "turn-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING)))
        )
        testScheduler.runCurrent()

        vm.interrupt()
        testScheduler.runCurrent()

        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertEquals(ThinkingStatus.DONE, turn.thinking?.status)
        assertTrue("must no longer read as in-progress", !turn.isInProgress())
    }

    @Test
    fun `interrupt settles the turn without sealing it against its own closing message`() = runTest(testDispatcher) {
        // This used to also fill in an empty reply, which settled the turn but
        // hid it from mergeIntoPendingTurn — so the closing message the agent
        // still owed arrived as a bubble of its own, stranding the footer with
        // this turn's timing and token count above a reply it no longer held.
        // Settling now rests on the footer alone: a turn whose thinking reads
        // DONE is not in progress even with no reply (see ChatItem.isInProgress),
        // which is what keeps the Stop button dark here.
        val turnId = "turn-2"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING), agent = null))
        )
        testScheduler.runCurrent()
        assertEquals(true, vm.isAgentWorking.value)

        vm.interrupt()
        testScheduler.runCurrent()

        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertTrue("must no longer read as in-progress", !turn.isInProgress())
        assertEquals("settled by its footer, not by a fabricated reply", ThinkingStatus.DONE, turn.thinking?.status)
        assertNull("still able to receive the closing message it is owed", turn.agent)
        assertEquals(false, vm.isAgentWorking.value)
    }

    @Test
    fun `a stopped turn that never answered does not adopt the next turn's reply`() =
        runTest(testDispatcher) {
            // The reason interrupt used to seal the turn. Left open, a stopped
            // turn is still a pending footer, and the merge picks the last one
            // — so the next turn has to be the one that wins it.
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-stopped"))
            testScheduler.runCurrent()
            vm.interrupt()
            testScheduler.runCurrent()

            // The agent says nothing. The user moves on, and that turn answers.
            vm.sendMessage("second")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-next"))
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.OutputReceived("the answer to second", null))
            testScheduler.runCurrent()

            // The merged turn takes the OUTPUT's own id, not the llm_call's,
            // so it is identified by what it holds rather than by name.
            val turns = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
            assertTrue(
                "the reply must land in the turn that produced it",
                turns.any { it.id != "turn-stopped" && it.agent?.content == "the answer to second" }
            )
            assertTrue(
                "the stopped turn must not have claimed it",
                turns.single { it.id == "turn-stopped" }.agent?.content.isNullOrBlank()
            )
        }

    @Test
    fun `a settled turn that produced no reply does not keep the Stop button lit`() = runTest(testDispatcher) {
        // The reload shape: a turn_thinking row decodes to exactly this, and
        // it used to disable the composer on an untouched, freshly opened app.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = "turn-3", thinking = ChatItem.Thinking(id = "turn-3", status = ThinkingStatus.DONE), agent = null)
            )
        )
        testScheduler.runCurrent()

        assertEquals(false, vm.isAgentWorking.value)
    }

    /** respondToApproval now needs the gate present in chatItems to answer it. */
    private suspend fun raiseGate(id: String = "gate-1") {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.ApprovalNeeded(id = id, tool = "bash", arguments = emptyMap())
            )
        )
    }

    private fun gate(id: String = "gate-1") =
        vm.uiState.value.chatItems.filterIsInstance<ChatItem.ApprovalNeeded>().firstOrNull { it.id == id }

    @Test
    fun `respondToApproval forwards the boolean and default scope to the use case`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true)
        testScheduler.runCurrent()
        assertEquals(true, connectUseCase.lastApproval)
        assertEquals("once", connectUseCase.lastApprovalScope)
        assertNull(connectUseCase.lastApprovalMode)
    }

    @Test
    fun `respondToApproval forwards session scope and reject mode to the use case`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()
        assertEquals(false, connectUseCase.lastApproval)
        assertEquals("once", connectUseCase.lastApprovalScope)
        assertEquals("reject_hard", connectUseCase.lastApprovalMode)
    }

    @Test
    fun `respondToApproval writes the decision onto the gate item`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()

        // On the item, not in a side map: this is what lets the card render the
        // outcome after a recomposition *and* after a reload from the database.
        val decision = gate()?.decision
        assertEquals(false, decision?.approved)
        assertEquals("once", decision?.scope)
        assertEquals("reject_hard", decision?.mode)
    }

    @Test
    fun `respondToApproval ignores a second answer for the same gate`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true, scope = "session")
        testScheduler.runCurrent()

        // The server already ran (or skipped) the tool on the first response;
        // a second APPROVAL_RESPONSE would be acted on again.
        assertEquals(false, connectUseCase.lastApproval)
        assertEquals("reject_hard", connectUseCase.lastApprovalMode)
        assertEquals(false, gate()?.decision?.approved)
    }

    @Test
    fun `respondToApproval forwards rejection feedback to the use case and the gate's decision`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_soft", feedback = "Use yarn instead")
        testScheduler.runCurrent()

        assertEquals("Use yarn instead", connectUseCase.lastApprovalFeedback)
        assertEquals("Use yarn instead", gate()?.decision?.feedback)
    }

    @Test
    fun `respondToApproval defaults feedback to null when none is given`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true)
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastApprovalFeedback)
        assertNull(gate()?.decision?.feedback)
    }

    @Test
    fun `respondToOnboard forwards invite code path`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()
        assertEquals("invite_code", connectUseCase.lastOnboardMethod)
        assertEquals("ABC-123", connectUseCase.lastOnboardInviteCode)
        assertNull(connectUseCase.lastOnboardPayment)
    }

    @Test
    fun `respondToOnboard forwards payment path`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        vm.respondToOnboard(method = "payment", payment = 12.5)
        testScheduler.runCurrent()
        assertEquals("payment", connectUseCase.lastOnboardMethod)
        assertEquals(12.5, connectUseCase.lastOnboardPayment)
    }

    @Test
    fun `respondToOnboard is a no-op with a clear error when not connected`() = runTest(testDispatcher) {
        // Regression test: submitting an invite code into a dead connection
        // got no response and no error back, leaving OnboardGateCard's
        // "Verifying…" holding state stuck forever (see ChatViewModel.
        // respondToOnboard's own doc — caught via reconnect-timing testing
        // on-device).
        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastOnboardMethod)
        assertEquals("Not connected. Please reconnect first.", vm.uiState.value.error?.message)
    }

    @Test
    fun `respondToOnboard succeeds while connectionState is Connecting (onboarding pending)`() = runTest(testDispatcher) {
        // Regression test for the fix above's own regression: while an
        // ONBOARD_REQUIRED gate is showing, the server never sends the
        // CONNECTED frame, so connectionState legitimately stays Connecting
        // for the entire time the invite-code form is up — even though the
        // socket is live and AgentConnection.respondToOnboard only needs a
        // non-null socket. An earlier cut of the guard used isConnected()
        // (which requires that CONNECTED frame) and blocked every real
        // invite-code submission, not just genuinely dead connections —
        // caught immediately by re-testing on-device.
        connectUseCase.forceState(ConnectionState.Connecting)

        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()

        assertEquals("invite_code", connectUseCase.lastOnboardMethod)
        assertEquals("ABC-123", connectUseCase.lastOnboardInviteCode)
        assertNull("must not surface a spurious not-connected error", vm.uiState.value.error)
    }

    @Test
    fun `respondToPlanReview forwards the message to the use case`() = runTest(testDispatcher) {
        vm.respondToPlanReview("Plan approved. Implement now. Do NOT re-enter plan mode.")
        testScheduler.runCurrent()
        assertEquals("Plan approved. Implement now. Do NOT re-enter plan mode.", connectUseCase.lastPlanReviewMessage)
    }

    @Test
    fun `respondToUlwTurnsReached forwards the continue action with turns`() = runTest(testDispatcher) {
        vm.respondToUlwTurnsReached(action = "continue", turns = 100)
        testScheduler.runCurrent()
        assertEquals("continue", connectUseCase.lastUlwAction)
        assertEquals(100, connectUseCase.lastUlwTurns)
        assertNull(connectUseCase.lastUlwMode)
    }

    @Test
    fun `respondToUlwTurnsReached forwards the switch_mode action with mode`() = runTest(testDispatcher) {
        vm.respondToUlwTurnsReached(action = "switch_mode", mode = "safe")
        testScheduler.runCurrent()
        assertEquals("switch_mode", connectUseCase.lastUlwAction)
        assertNull(connectUseCase.lastUlwTurns)
        assertEquals("safe", connectUseCase.lastUlwMode)
    }

    // ── Approval mode ──────────────────────────────────────────────────

    @Test
    fun `cycleApprovalMode advances the chip through the base modes`() = runTest(testDispatcher) {
        assertEquals(ApprovalMode.SAFE, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.PLAN, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.ACCEPT_EDITS, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.SAFE, vm.approvalMode.value)

        assertEquals(
            listOf(ApprovalMode.PLAN, ApprovalMode.ACCEPT_EDITS, ApprovalMode.SAFE),
            connectUseCase.setModeCalls.map { it.first }
        )
    }

    /**
     * Safety property, asserted at the layer the chip actually calls: tapping
     * the chip can never hand the agent ULW, from any starting mode and for
     * any number of taps. See [ApprovalMode.CYCLE]'s doc.
     */
    @Test
    fun `cycling the chip never sends a ulw mode_change`() = runTest(testDispatcher) {
        ApprovalMode.entries.forEach { start ->
            connectUseCase._approvalMode.value = start
            repeat(ApprovalMode.entries.size + 2) {
                vm.cycleApprovalMode()
                testScheduler.runCurrent()
            }
        }

        assertTrue(connectUseCase.setModeCalls.isNotEmpty())
        assertTrue(
            "cycling produced a ULW switch: ${connectUseCase.setModeCalls}",
            connectUseCase.setModeCalls.none { it.first == ApprovalMode.ULW }
        )
    }

    @Test
    fun `setApprovalMode forwards ULW with its turn budget`() = runTest(testDispatcher) {
        vm.setApprovalMode(ApprovalMode.ULW, turns = 25)
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.ULW to 25, connectUseCase.setModeCalls.single())
        assertEquals(ApprovalMode.ULW, vm.approvalMode.value)
    }

    @Test
    fun `setApprovalMode sends no turns for a base mode`() = runTest(testDispatcher) {
        vm.setApprovalMode(ApprovalMode.PLAN)
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.PLAN to null, connectUseCase.setModeCalls.single())
    }

    @Test
    fun `an agent-initiated mode_changed moves the chip without any local action`() = runTest(testDispatcher) {
        connectUseCase._approvalMode.value = ApprovalMode.ULW
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.ULW, vm.approvalMode.value)
        assertTrue(connectUseCase.setModeCalls.isEmpty())
    }

    @Test
    fun `connectToAgent with blank address is a no-op`() = runTest(testDispatcher) {
        vm.connectToAgent("")
        testScheduler.runCurrent()
        assertEquals(
            "Blank address must not trigger a connect",
            0,
            connectUseCase.connectInvocations
        )
    }

    @Test
    fun `connectToAgent with valid hex address resolves and forwards to use case`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.connectInvocations)
        assertEquals(address, connectUseCase.lastConnectAddress)
    }

    @Test
    fun `connectToAgent forwards a saved non-default serverUrl as directUrl`() = runTest(testDispatcher) {
        // Regression test: the drawer's "+" on a different agent (and every
        // other connectToAgent(address) caller that doesn't already know a
        // directUrl) silently ignored that agent's saved custom server and
        // fell back to the default relay — see connectToAgent's own doc.
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Custom Server Agent",
                serverUrl = "https://custom.example.com",
                createdAt = 0L
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, connectUseCase.lastConnectAddress)
        assertEquals("https://custom.example.com", connectUseCase.lastConnectDirectUrl)
    }

    @Test
    fun `connectToAgent does not forward a default-relay serverUrl as directUrl`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Default Relay Agent",
                serverUrl = "https://oo.openonion.ai",
                createdAt = 0L
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastConnectDirectUrl)
    }

    @Test
    fun `connectToAgent honors an explicitly passed directUrl over the saved agent serverUrl`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Custom Server Agent",
                serverUrl = "https://custom.example.com",
                createdAt = 0L
            )
        )

        vm.connectToAgent(address, directUrl = "https://explicit.example.com")
        testScheduler.runCurrent()

        assertEquals("https://explicit.example.com", connectUseCase.lastConnectDirectUrl)
    }

    @Test
    fun `targetAgentAddress reflects the resolved address after connectToAgent`() = runTest(testDispatcher) {
        assertNull("no target address before any connect attempt", vm.targetAgentAddress.value)

        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, vm.targetAgentAddress.value)
    }

    @Test
    fun `connectToAgentByConfig reads config and forwards to connectToAgent`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(
            serverUrl = "https://oo.openonion.ai",
            agentAddress = validHexAddress()
        )
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()

        assertEquals(
            "Config-driven connect must call the use case",
            1,
            connectUseCase.connectInvocations
        )
    }

    @Test
    fun `connectToAgentByConfig is a no-op when no config is saved`() = runTest(testDispatcher) {
        configRepo.configValue = null
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.connectInvocations)
        // Regression: Reconnect used to fail silently forever here — see
        // connectToAgentByConfig's else branch.
        assertNotNull("no config must surface an error, not silence", vm.uiState.value.error)
    }

    @Test
    fun `connectToAgentByConfig is a no-op when config has no agent address`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(serverUrl = "https://x", agentAddress = null)
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.connectInvocations)
        assertNotNull("no agent address must surface an error, not silence", vm.uiState.value.error)
    }

    // ── Connected-state side effect: saveAgentIfNeeded ─────────────────

    @Test
    fun `transitioning to Connected calls saveAgentIfNeeded`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertTrue(
            "Connected must trigger saveAgentIfNeeded via agentRepository",
            agentRepo.createdAgents.isNotEmpty()
        )
        val saved = agentRepo.createdAgents.first()
        assertEquals(address, saved.address)
    }

    @Test
    fun `joining an already-live shared connection still runs the connected work`() = runTest(testDispatcher) {
        // LoadingScreen's probe left the shared connection Connected, so
        // ChatViewModel's own connect() joins it and no Connected *transition*
        // ever fires. The agent row and its conversation must still be resolved.
        val address = validHexAddress()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, agentRepo.createdAgents.single().address)
    }

    @Test
    fun `an already-Connected shared connection at construction saves no blank agent`() = runTest(testDispatcher) {
        // The state collector sees Connected the instant it subscribes, which
        // is before ChatScreen has said which agent this ViewModel is for —
        // acting on it then wrote an agent row with an empty address.
        connectUseCase.markConnected(validHexAddress())

        val fresh = ChatViewModel(
            application = Application(),
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectUseCase,
            agentRepository = agentRepo,
            configRepository = configRepo,
            ignoredIdsManager = IgnoredIdsManager(storage),
            imageAttachmentStore = imageStore,
            fileAttachmentStore = fileStore,
            voiceRecorderStore = voiceRecorderStore,
            voiceTranscriptionService = voiceTranscriptionService,
            speechRecognitionService = speechRecognition,
            appSettings = appSettings,
            messageRepository = messageRepo,
            conversationHistory = ConversationHistoryUseCase(
                agentRepo, sessionRepo, messageRepo,
                FakePersistenceTransaction(messageRepo, sessionRepo)
            ),
            initialMyAddress = "0xTEST",
            networkMonitor = networkMonitor,
            agentReplyNotifier = agentReplyNotifier,
            requiresNotificationPermission = true,
            hasNotificationPermission = { notificationPermissionGranted },
            isAppInForeground = { appInForeground },
        )
        testScheduler.runCurrent()

        assertTrue(agentRepo.createdAgents.isEmpty())
        assertNull(fresh.targetAgentAddress.value)
    }

    // ── drawerAgents ─────────────────────────────────────────────────────

    private fun agentProfile(id: String, address: String, isActive: Boolean = true) = AgentProfile(
        id = id, address = address, name = "Agent-$id", serverUrl = "https://example.com",
        createdAt = 0L, isActive = isActive
    )

    @Test
    fun `drawerAgents lists every saved agent, not just the connected one`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        agentRepo.createAgent(agentProfile("a2", "0x2"))
        testScheduler.runCurrent()

        val sections = vm.drawerAgents.value

        assertEquals(setOf("0x1", "0x2"), sections.map { it.agentAddress }.toSet())
    }

    @Test
    fun `drawerAgents shows live Connecting status only for the currently-targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        agentRepo.createAgent(agentProfile("a2", "0x2"))
        vm.connectToAgent("0x1")
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Connecting)
        testScheduler.runCurrent()

        val sections = vm.drawerAgents.value.associateBy { it.agentAddress }
        assertEquals(AgentStatus.Connecting, sections.getValue("0x1").status)
        // The other agent has no live signal — falls back to isActive, same
        // as AgentListScreen's own StatusBadge does.
        assertEquals(AgentStatus.Active, sections.getValue("0x2").status)
    }

    @Test
    fun `drawerAgents falls back to Disabled for an inactive, non-targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1", isActive = false))
        testScheduler.runCurrent()

        val section = vm.drawerAgents.value.single()

        assertEquals(AgentStatus.Disabled, section.status)
    }

    @Test
    fun `drawerAgents reflects Connected and Error for the targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        vm.connectToAgent("0x1")
        testScheduler.runCurrent()

        connectUseCase.markConnected("0x1")
        testScheduler.runCurrent()
        assertEquals(AgentStatus.Connected, vm.drawerAgents.value.single().status)

        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()
        assertEquals(AgentStatus.Error("boom"), vm.drawerAgents.value.single().status)
    }

    // ── awaitingOnboardCode ──────────────────────────────────────────────

    @Test
    fun `markOnboardPending sets awaitingOnboardCode`() {
        assertFalse(vm.awaitingOnboardCode.value)

        vm.markOnboardPending()

        assertTrue(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears when connectionState becomes Connected`() = runTest(testDispatcher) {
        vm.markOnboardPending()
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears when connectionState becomes Error`() = runTest(testDispatcher) {
        vm.markOnboardPending()

        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode stays true while an unanswered OnboardRequired card is showing`() {
        // Some agents never send a CONNECTED frame once onboarding is
        // required, so connectionState alone would stay "Connecting"
        // forever — the chat list having the card is what must keep the
        // banner in the "waiting for code" state, independent of markOnboardPending().
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )

        assertTrue(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears once OnboardSuccess arrives`() {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )
        assertTrue(vm.awaitingOnboardCode.value)

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardSuccess(id = "os1", level = "basic", message = "Welcome"))
        )

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `invite-code rejection flow comes in as a ChatItemReceived OnboardingFailed (not a ConnectionState Error)`() = runTest(testDispatcher) {
        // Invite-code rejections now arrive as ChatItemReceived(OnboardingFailed) replacing the OnboardRequired card in place, not as a ConnectionState.Error — see AgentConnectionRobolectricTest for the emit-side coverage.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
            )
        )
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "OnboardRequired must be replaced in place by OnboardingFailed",
            1, items.size
        )
        val failed = items.single() as ChatItem.OnboardingFailed
        assertEquals("ob1", failed.id)
        assertEquals("Invalid invite code", failed.reason)
        assertFalse(
            "OnboardingFailed must keep awaitingOnboardCode=true — banner should stay 'Waiting for invite code…'",
            vm.awaitingOnboardCode.value.not()
        )
    }

    @Test
    fun `ConnectionState Error without an OnboardRequired card does NOT add an OnboardingFailed`() = runTest(testDispatcher) {
        // Negative path: ConnectionState.Error outside onboarding (e.g. a
        // server-side crash mid-conversation) must NOT inject a stray
        // OnboardingFailed chat item into the chat list. The card is a
        // gate-state-machine item, not a generic error indicator.
        connectUseCase.forceState(ConnectionState.Error(message = "Internal server error"))
        testScheduler.runCurrent()

        assertTrue(
            "no OnboardingFailed should be emitted when no OnboardRequired gate was on screen",
            vm.uiState.value.chatItems.none { it is ChatItem.OnboardingFailed }
        )
    }

    // ── startNewSession / switchToSession / onCleared ──────────────────

    @Test
    fun `startNewSession clears chatItems and starts a new persisted session once an agent is known`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        assertTrue(vm.uiState.value.chatItems.isNotEmpty())

        vm.startNewSession()
        testScheduler.runCurrent()

        assertTrue("chatItems must be cleared for the new session", vm.uiState.value.chatItems.isEmpty())
    }

    @Test
    fun `startNewSession is a no-op before any agent has connected`() = runTest(testDispatcher) {
        // No connectToAgent()/markConnected() — ensureActiveSession() never
        // ran, so ConversationHistoryUseCase has no agent id to work with.
        vm.startNewSession()
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.chatItems.isEmpty())
    }

    @Test
    fun `switchToSession loads that session's persisted messages into chatItems`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val firstSessionId = vm.activeSessionId.value!!

        // Switch away (clearing the visible list, same as startNewSession's
        // own test above), send a message on the new session too, then
        // switch back — this is what exercises switchToSession's *restore*
        // path specifically, and that the two sessions' messages don't
        // collide (message ids are UUIDs, not a per-instance counter that
        // used to reset to 0 on startNewSession() and could mint the same
        // id "1" for both sessions' first message).
        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())
        vm.sendMessage("second session message")
        testScheduler.runCurrent()

        vm.switchToSession(firstSessionId)
        testScheduler.runCurrent()

        val restored = vm.uiState.value.chatItems.single() as ChatItem.User
        assertEquals("first session message", restored.content)
        assertEquals(firstSessionId, vm.activeSessionId.value)
    }

    @Test
    fun `deleteSession clears the visible chat list when the deleted session was the active one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()
        val activeId = vm.activeSessionId.value!!
        assertTrue(vm.uiState.value.chatItems.isNotEmpty())

        vm.deleteSession(activeId)
        testScheduler.runCurrent()

        assertTrue("chatItems must be cleared once the open session is gone", vm.uiState.value.chatItems.isEmpty())
        assertNull(vm.activeSessionId.value)
    }

    @Test
    fun `deleteSession leaves the visible chat list untouched for a session that isn't the active one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()
        val activeId = vm.activeSessionId.value!!

        vm.deleteSession("some-other-session-id-not-open-here")
        testScheduler.runCurrent()

        assertTrue("current conversation must be untouched", vm.uiState.value.chatItems.isNotEmpty())
        assertEquals(activeId, vm.activeSessionId.value)
    }

    // ── renameSession ──────────────────────────────────────────────────

    @Test
    fun `renameSession persists the new title through the repository`() = runTest(testDispatcher) {
        val session = sessionRepo.seed("agent-1", "Old title")

        vm.renameSession(session.id, "New title")
        testScheduler.runCurrent()

        assertEquals("New title", sessionRepo.getSessionById(session.id)?.title)
    }

    // ── session_sync replay must not leak across conversations ───────────

    @Test
    fun `a replayed reply owned by another conversation never reaches the new chat`() = runTest(testDispatcher) {
        // Observed on device: tap "New chat", and seconds later the server's
        // session_sync (one per agent, not per local conversation) refilled the
        // empty chat with the *previous* conversation's replies. Room stayed
        // correct -- PersistenceTransaction refuses to re-file an owned row --
        // so it was the list on screen that lied.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val reply = "the first conversation's reply"
        val replyId = stableAssistantId(reply)
        connectUseCase.emit(ChatEvent.OutputReceived(reply, null))
        testScheduler.runCurrent()
        val firstSessionId = vm.activeSessionId.value!!
        assertEquals(firstSessionId, messageRepo.getOwningSessionId(replyId))

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("hi")
        testScheduler.runCurrent()

        // The server replays the whole agent transcript as ChatItemReceived,
        // exactly as ProtocolParser.parseSessionSync fans it out.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = replyId, agent = ChatItem.Agent(id = replyId, content = reply))
            )
        )
        testScheduler.runCurrent()

        assertTrue(
            "the previous conversation's reply was replayed into the new chat: " +
                vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == replyId }
        )
        // And it stayed where it belongs rather than being re-filed.
        assertEquals(firstSessionId, messageRepo.getOwningSessionId(replyId))
    }

    @Test
    fun `a reply that belongs to no conversation yet still renders`() = runTest(testDispatcher) {
        // The guard keys on "Room already files this id elsewhere", so an id
        // that has never been written -- every genuinely new reply -- must pass
        // straight through. Without this the fix would silence live traffic.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived("a brand new reply", null))
        testScheduler.runCurrent()

        val replyId = stableAssistantId("a brand new reply")
        assertTrue(
            "a new reply was dropped: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == replyId }
        )
    }

    @Test
    fun `a resumed conversation still accepts session_sync history`() = runTest(testDispatcher) {
        // The mirror image: a conversation being resumed is exactly the one
        // the server's history may belong to, so the guard must not block it.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        vm.switchToSession(sessionA)
        testScheduler.runCurrent()

        val restored = "an entry only the server still has"
        val restoredId = stableAssistantId(restored)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = restoredId, agent = ChatItem.Agent(id = restoredId, content = restored)),
                fromSessionSnapshot = true,
                answeredQuestion = "first session message"
            )
        )
        testScheduler.runCurrent()

        assertTrue(
            "a resumed conversation was denied its own history: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == restoredId }
        )
    }

    @Test
    fun `a reply that lands after switching away is filed in the chat that asked`() = runTest(testDispatcher) {
        // Reported from the device: ask in A, switch to B before the answer
        // arrives, and the answer appeared in B. It must go to A instead --
        // invisible in B, and there when the user switches back.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        // Switch away with the turn still unanswered. Deliberately without
        // sending anything here: asking a second question would overwrite the
        // connection's own `currentInputId` too, and ProtocolParser then drops
        // the older turn's OUTPUT as "not for us" long before the ViewModel
        // sees it — a different behaviour, out of this test's scope.
        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue("test setup: must have left A", vm.activeSessionId.value != sessionA)

        val late = "the answer A was waiting for"
        connectUseCase.emit(ChatEvent.OutputReceived(late, null))
        testScheduler.runCurrent()

        val lateId = stableAssistantId(late)
        assertTrue(
            "A's answer showed up in B: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == lateId }
        )
        assertEquals("the answer was not filed under the chat that asked", sessionA, messageRepo.getOwningSessionId(lateId))

        // And switching back surfaces it.
        vm.switchToSession(sessionA)
        testScheduler.runCurrent()
        assertTrue(
            "switching back to A did not load its answer: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == lateId }
        )
    }

    @Test
    fun `a full turn's progress frames do not hand the reply to the chat switched to`() = runTest(testDispatcher) {
        // The test above emits OutputReceived alone, which is not what a turn
        // looks like on the wire. A real one (device log, 01:52) is a burst:
        //
        //   session_sync -> ChatItemReceived(Turn)
        //   llm_call     -> ChatItemReceived(Turn)
        //   [user switches away here]
        //   llm_result   -> ChatItemUpdated(Turn)   <- no agent text yet
        //   OUTPUT       -> the actual reply
        //
        // Every one of those middle frames reduces to a non-null
        // itemToPersist, so releasing ownership on the first of them handed
        // the real reply to whatever conversation was on screen by then.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        // Progress frames that arrive while A is still on screen.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(
                    id = "turn-in-flight",
                    thinking = ChatItem.Thinking(id = "turn-in-flight", status = ThinkingStatus.RUNNING)
                )
            )
        )
        testScheduler.runCurrent()

        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue("test setup: must have left A", vm.activeSessionId.value != sessionA)

        // ...and the one that arrives after the switch. A Turn whose `agent`
        // is null is dropped by PersistenceTransaction, so this writes nothing
        // -- it must not be mistaken for the reply having been filed either.
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Turn(
                    id = "turn-in-flight",
                    thinking = ChatItem.Thinking(id = "turn-in-flight", status = ThinkingStatus.DONE)
                )
            )
        )
        testScheduler.runCurrent()

        val late = "the answer A was waiting for"
        connectUseCase.emit(ChatEvent.OutputReceived(late, null))
        testScheduler.runCurrent()

        val lateId = stableAssistantId(late)
        assertTrue(
            "A's answer showed up in B: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == lateId }
        )
        assertEquals("the answer was not filed under the chat that asked", sessionA, messageRepo.getOwningSessionId(lateId))

        vm.switchToSession(sessionA)
        testScheduler.runCurrent()
        assertTrue(
            "switching back to A did not load its answer: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == lateId }
        )
    }

    @Test
    fun `a turn that ends in a server error releases ownership`() = runTest(testDispatcher) {
        // The mirror of the case above: no reply is coming, so the owner must
        // not stay pinned. Otherwise the next persistable item to arrive while
        // the user reads B would be filed into A instead.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        val sessionB = vm.activeSessionId.value

        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("the turn failed"))
        testScheduler.runCurrent()

        // With the turn closed out, a later reply belongs to whatever is on
        // screen now -- B -- not to A.
        val later = "an answer asked for in B"
        connectUseCase.emit(ChatEvent.OutputReceived(later, null))
        testScheduler.runCurrent()

        val laterId = stableAssistantId(later)
        assertTrue(
            "a reply after the failed turn was still pinned to A",
            messageRepo.getOwningSessionId(laterId) != sessionA
        )
        assertTrue(
            "the reply is not visible in the conversation on screen: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == laterId }
        )
        assertTrue("test setup: B must be a distinct conversation", sessionB != sessionA)
    }

    @Test
    fun `leaving a conversation mid-turn does not persist a failure marker`() = runTest(testDispatcher) {
        // The answer is still coming and will be filed into this conversation,
        // so a persisted "Failed" row would sit right next to it claiming it
        // never arrived. A real disconnect still records one.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!
        // sendMessage alone leaves nothing RUNNING, so without this the
        // assertion below could never have failed -- failRunningThinking
        // returns early and writes nothing either way.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()
        assertTrue(
            "test setup: a RUNNING item must exist for this to mean anything",
            vm.uiState.value.chatItems.any { it.isInProgress() }
        )

        vm.startNewSession()
        testScheduler.runCurrent()

        // The RUNNING row itself is persisted as it arrives -- that is normal.
        // What must NOT appear is that same item flipped to ERROR on the way
        // out, which is what a persisted failure marker looks like.
        val failed = messageRepo.snapshot()
            .filter { it.sessionId == sessionA }
            .mapNotNull { it.payload?.let(TranscriptItemCodec::decode) }
            .filterIsInstance<ChatItem.Thinking>()
            .filter { it.status == ThinkingStatus.ERROR }
        assertTrue("a failure marker was written into the conversation being left: $failed", failed.isEmpty())
    }

    // ── SESSION_STATUS-based revert of a disconnect false alarm ─────────

    @Test
    fun `a disconnect-failed turn is un-failed when SESSION_STATUS says the server is still running it`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        // The reconnect ladder exhausts itself (AgentConnection gives up) --
        // the ViewModel force-fails the still-in-flight Thinking bubble.
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()
        assertEquals(
            "test setup: the turn must be marked failed before a revert means anything",
            ThinkingStatus.ERROR,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )

        // A later manual reconnect (e.g. the app comes back to the
        // foreground) succeeds -- this must trigger exactly one
        // SESSION_STATUS query to find out whether that failure was real.
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.querySessionStatusCalls)

        // The server says the agent thread never actually stopped.
        connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "running"))
        testScheduler.runCurrent()

        assertEquals(
            "SESSION_STATUS confirmed the turn is still running -- the ERROR mark must be reverted",
            ThinkingStatus.RUNNING,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )
    }

    @Test
    fun `a disconnect-failed turn stops saying Failed once the replay carries its answer`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("你好")
            testScheduler.runCurrent()
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
            )
            testScheduler.runCurrent()

            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            // The server's replay brings the answer the lost OUTPUT owed.
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Agent(id = "asst-1", content = "你好！我是一个网络自动化助手。"))
            )
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "connected"))
            testScheduler.runCurrent()

            assertEquals(
                "Failed must not sit directly above the reply it claims never arrived",
                ThinkingStatus.DONE,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )
        }

    @Test
    fun `a disconnect-failed turn keeps saying Failed when the replay brings nothing`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("你好")
            testScheduler.runCurrent()
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
            )
            testScheduler.runCurrent()

            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "connected"))
            testScheduler.runCurrent()

            assertEquals(
                "the run ended with nothing to show, so Retry stays the obvious move",
                ThinkingStatus.ERROR,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )
        }

    @Test
    fun `a disconnect-failed turn stays failed when SESSION_STATUS says the session is gone`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        // The server genuinely lost the session -- the original ERROR mark
        // was correct and must NOT be reverted.
        connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "not_found"))
        testScheduler.runCurrent()

        assertEquals(
            ThinkingStatus.ERROR,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )
    }

    @Test
    fun `connecting with nothing disconnect-failed does not query SESSION_STATUS`() = runTest(testDispatcher) {
        // A fresh/ordinary connect has nothing to reconcile -- querying on
        // every single Connected transition would be pure waste.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(0, connectUseCase.querySessionStatusCalls)
    }

    @Test
    fun `onCleared leaves the shared connection alive for the next screen`() {
        // connectUseCase is app-scoped (see AppContainer): close() is terminal
        // — it cancels the repository's scope for good — so a ViewModel calling
        // it on teardown would brick the connection every other screen shares.
        vm.onClearedForTest()

        assertEquals(0, connectUseCase.disconnectCount)
    }

    // ── startAccountProfileAutoRefresh ───────────────────────────────────

    // ── Resume on open ─────────────────────────────────────────────────

    /** Seeds an agent plus one saved conversation with two persisted rows. */
    private fun seedPreviousConversation(address: String): ChatSession {
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        val session = sessionRepo.seed("agent-1", "Yesterday's chat", messageCount = 2)
        messageRepo.seed(
            ChatMessage(id = "m1", sessionId = session.id, role = Role.USER, content = "hi", timestamp = 100L),
            ChatMessage(id = "m2", sessionId = session.id, role = Role.ASSISTANT, content = "hello", timestamp = 200L)
        )
        return session
    }

    @Test
    fun `connecting renders the previous conversation before the server has answered`() = runTest(testDispatcher) {
        // The reported bug: after a force-stop, Room still held the messages
        // but the screen showed "No messages yet." Local history has to be
        // on screen from the restore, not from a server replay.
        val address = validHexAddress()
        val session = seedPreviousConversation(address)

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(session.id, vm.activeSessionId.value)
        assertEquals(listOf("hi", "hello"), vm.uiState.value.chatItems.map {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Turn -> it.agent?.content.orEmpty()
                else -> ""
            }
        })
    }

    @Test
    fun `an unreachable server leaves the local records on screen, not an empty list`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        connectUseCase.connectResult = false

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Error(message = "Connection refused"))
        testScheduler.runCurrent()

        val contents = vm.uiState.value.chatItems.mapNotNull {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Turn -> it.agent?.content
                else -> null
            }
        }
        assertTrue("local rows must survive the connection failure", contents.containsAll(listOf("hi", "hello")))
    }

    @Test
    fun `markStartNewSessionOnConnect still overrides the resume default`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)

        vm.markStartNewSessionOnConnect()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.chatItems.isEmpty())
        assertNull("lazy creation: the fresh session isn't a Room row yet", vm.activeSessionId.value)
    }

    @Test
    fun `a reconnect does not re-push the resumed rows over what arrived live`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "live-1", content = "live reply")))
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(
            listOf("hi", "hello", "live reply"),
            vm.uiState.value.chatItems.map {
                when (it) {
                    is ChatItem.User -> it.content
                    // Reloaded rows come back as Turn; "live reply" arrives
                    // as a standalone Agent, so both shapes appear here.
                    is ChatItem.Turn -> it.agent?.content.orEmpty()
                    is ChatItem.Agent -> it.content
                    else -> ""
                }
            }
        )
    }


    // ── one server session per conversation ──────────────────────────

    @Test
    fun `the first message of a pending conversation moves the connection before it sends`() = runTest(testDispatcher) {
        // Device bug: the app joined a live connection, the conversation went
        // pending, and materialising its Room row on send told nobody. The
        // INPUT left on whatever session the socket already held, so the turn
        // was filed under another conversation server-side and the new one
        // was empty — nothing downstream can recover a reply that was never
        // stored against it.
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        // The socket is on somebody else's session, exactly as it is after
        // joining a connection the loading probe opened.
        connectUseCase.connectedConversationId = "some-other-session"
        connectUseCase.interactions.clear()

        vm.sendMessage("Hi")
        testScheduler.runCurrent()

        val conversation = vm.activeSessionId.value
        assertNotNull("the send must have materialised a conversation", conversation)
        assertEquals(
            "the INPUT went out on a session this conversation does not own",
            conversation,
            connectUseCase.connectedConversationId
        )
        val switchIndex = connectUseCase.interactions.indexOf("switch:$conversation")
        val sendIndex = connectUseCase.interactions.indexOfFirst { it.startsWith("send:") }
        assertTrue("the connection was never moved: ${connectUseCase.interactions}", switchIndex >= 0)
        assertTrue(
            "the INPUT was sent before the CONNECT for its session: ${connectUseCase.interactions}",
            switchIndex < sendIndex
        )
    }

    @Test
    fun `sending again in the same conversation does not churn the connection`() = runTest(testDispatcher) {
        // The switch costs a socket round trip, so it must happen once per
        // conversation, not once per message.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()
        val conversation = vm.activeSessionId.value!!
        connectUseCase.interactions.clear()

        vm.sendMessage("second")
        testScheduler.runCurrent()

        assertEquals(
            "a second message in the same conversation must not re-CONNECT: " +
                connectUseCase.interactions,
            emptyList<String>(),
            connectUseCase.interactions.filter { it.startsWith("switch:") && it != "switch:$conversation" }
        )
        assertEquals(conversation, connectUseCase.connectedConversationId)
    }


    @Test
    fun `the conversation is resolved locally before the connection is moved`() = runTest(testDispatcher) {
        // Load-bearing ordering. The transcript the server replays after a
        // switch is merged into whatever conversation is active when it
        // lands, so resolving must finish first — the other order files the
        // new session's history into the conversation just left.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("in the first chat")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()
        connectUseCase.activeAtSwitch.clear()
        connectUseCase.activeSessionIdAtSwitch = { vm.activeSessionId.value }

        vm.switchToSession(first)
        testScheduler.runCurrent()

        assertEquals(listOf(first), connectUseCase.switchedConversations)
        assertEquals(
            "the connection was moved before the conversation was resolved",
            listOf<String?>(first),
            connectUseCase.activeAtSwitch
        )
    }

    @Test
    fun `a brand-new conversation connects on an id of its own, before it has a row`() = runTest(testDispatcher) {
        // The agent is known but has no conversations, so this goes Pending:
        // no Room row, yet the CONNECT must already name this chat's session.
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertNull("nothing is persisted yet", vm.activeSessionId.value)
        val connectedOn = connectUseCase.lastConnectConversationId
        assertNotNull("a brand-new conversation must still connect on its own session", connectedOn)

        // And the row, once written, is that same conversation.
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()

        assertEquals(
            "the row must be the conversation the connection was already on",
            connectedOn,
            vm.activeSessionId.value
        )
    }

    @Test
    fun `resuming a stored conversation connects on that conversation's own session`() = runTest(testDispatcher) {
        val address = validHexAddress()
        val session = seedPreviousConversation(address)

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(session.id, connectUseCase.lastConnectConversationId)
    }

    @Test
    fun `two conversations started back-to-back each get their own server session`() = runTest(testDispatcher) {
        // The old positional scheme minted both offsets from the same stream
        // length when no traffic separated them, so both claimed the same
        // replies. Now each conversation carries its own session id and the
        // second one starts from none at all.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("second")
        testScheduler.runCurrent()
        val second = vm.activeSessionId.value!!

        assertNotEquals("the two conversations must be distinct", first, second)
        // One switch per conversation, and the id switched to is the id each
        // row was then created under — i.e. each was already talking on its
        // own session before it had anywhere to be stored.
        assertEquals(
            "each conversation must get its own session, exactly once",
            listOf(first, second),
            connectUseCase.switchedConversations
        )
        assertEquals(
            "the connection must end up on the conversation on screen",
            second,
            connectUseCase.connectedConversationId
        )
    }

    @Test
    fun `switching conversations moves the connection onto that conversation's session`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("in the first chat")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()

        vm.switchToSession(first)
        testScheduler.runCurrent()

        assertEquals(listOf(first), connectUseCase.switchedConversations)
        assertTrue(
            "the transcript must be on screen from Room, not wait for the re-CONNECT",
            vm.uiState.value.chatItems.any { it.id == "in the first chat" || it is ChatItem.User }
        )
    }

    @Test
    fun `deleting the open conversation moves the connection off its session`() = runTest(testDispatcher) {
        // Deleting a conversation used to silently extend the previous one's
        // slice over the deleted one's replies. There is no slice now, but the
        // connection must still stop talking on a session nothing owns.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("doomed")
        testScheduler.runCurrent()
        val doomed = vm.activeSessionId.value!!
        connectUseCase.switchedConversations.clear()

        vm.deleteSession(doomed)
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.switchedConversations.size)
        val replacement = connectUseCase.switchedConversations.single()
        assertNotNull("deleting the open chat must leave a replacement to talk on", replacement)
        assertNotEquals(
            "the connection must not stay on the deleted conversation's session",
            doomed,
            replacement
        )
    }

    @Test
    fun `deleting a middle conversation leaves the others' sessions untouched`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        val older = sessionRepo.seed("agent-1", "Older", messageCount = 1)
        val middle = sessionRepo.seed("agent-1", "Middle", messageCount = 1)
        val newer = sessionRepo.seed("agent-1", "Newer", messageCount = 1)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()

        vm.deleteSession(middle.id)
        testScheduler.runCurrent()

        // The survivors keep their own ids, and nothing re-points them at the
        // deleted conversation's session.
        val remaining = vm.sessions.value.map { it.id }
        assertTrue("the older conversation must survive", remaining.contains(older.id))
        assertTrue("the newer conversation must survive", remaining.contains(newer.id))
        assertTrue(
            "deleting a conversation the user was not in must not move the connection",
            connectUseCase.switchedConversations.isEmpty()
        )
    }

    @Test
    fun `a transcript that arrives before the conversation resolves is applied once it does`() = runTest(testDispatcher) {
        // The sticky transcript is replayed to whoever subscribes, so a
        // ViewModel joining a live connection can receive it before it knows
        // which conversation it is on. A StateFlow will not emit the same
        // value twice, so a merge that no-ops here is the last chance that
        // replay ever gets — on master this is where cold-start history went.
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        assertNull("test setup: nothing resolved yet", vm.activeSessionId.value)

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("answered while you were away")
                ),
                turn = 3
            )
        )
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(session.id, vm.activeSessionId.value)
        assertTrue(
            "the replay was dropped instead of held: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any {
                it.id == stableAssistantId("answered while you were away")
            }
        )
    }

    @Test
    fun `a reply that finished while away appears when the conversation is reopened`() = runTest(testDispatcher) {
        // Device repro: ask in A, start B before A's answer lands, send in B,
        // come back to A. A's turn finished server-side, so the CONNECT
        // transcript is the only way its answer can reach the chat list.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("Hi")
        testScheduler.runCurrent()
        val a = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("Buhao")
        testScheduler.runCurrent()
        val b = vm.activeSessionId.value!!
        assertNotEquals(a, b)

        vm.switchToSession(a)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("Hello! What's your name?")
                ),
                turn = 1
            )
        )
        testScheduler.runCurrent()

        assertEquals("test setup: A must be the conversation on screen", a, vm.activeSessionId.value)
        assertTrue(
            "the reply is in the database but never reached the chat list: " +
                vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any {
                it.id == stableAssistantId("Hello! What's your name?")
            }
        )
    }

    // ── ServerTranscriptReceived ───────────────────────────────────────

    @Test
    fun `a server transcript the local copy already holds changes nothing`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(entries = listOf(ServerTranscriptEntry("hello")), turn = 2)
        )
        testScheduler.runCurrent()

        assertEquals(2, vm.uiState.value.chatItems.size)
    }

    @Test
    fun `a newer server transcript is fetched into the resumed conversation`() = runTest(testDispatcher) {
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("hello"),
                    // Claimed by the conversation's own persisted question
                    // ("hi", row m1) — the asker confirmation an in-slice,
                    // locally-missing entry now needs to merge.
                    ServerTranscriptEntry("answered while you were away")
                ),
                turn = 3
            )
        )
        testScheduler.runCurrent()

        val contents = vm.uiState.value.chatItems.map {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Agent -> it.content
                is ChatItem.Turn -> it.agent?.content.orEmpty()
                else -> ""
            }
        }
        assertEquals(listOf("hi", "hello", "answered while you were away"), contents)
        assertTrue(
            "the fetched entry must be persisted into the resumed session",
            messageRepo.snapshot().any {
                it.sessionId == session.id && it.content == "answered while you were away"
            }
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * 40 hex chars after the `0x` prefix. AgentDiscoveryService treats
     * this as a valid address and returns synchronously without any
     * HTTP call, so tests don't need to stub network IO.
     */
    private fun validHexAddress(nibble: Int = 0xA): String =
        "0x" + nibble.toString(16).repeat(40)

    private fun testSchedulerKeepRunning() {
        // No-op marker: scheduler advancement happens at the call site via runCurrent(); named only for readability.
    }
}

/**
 * Exposes the protected onCleared() for direct testing without a real
 * ViewModelStore teardown. Resolved on the base class, not on ChatViewModel:
 * the override went away once the connection became app-scoped, and virtual
 * dispatch still reaches any future override.
 */
private fun ChatViewModel.onClearedForTest() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}

/**
 * Mirrors the real [ai.openonion.oochat.domain.usecase.PersistenceTransaction.persistMessageAtomically]
 * routing logic (item shape -> role/content/images, default-title rename
 * trigger) but against the fake repositories this test file already asserts
 * against, since the real implementation writes through raw Room DAOs that
 * bypass [MessageRepository]/[SessionRepository] entirely.
 */
class FakePersistenceTransaction(
    private val messageRepo: MessageRepository,
    private val sessionRepo: SessionRepository
) : ai.openonion.oochat.domain.usecase.PersistenceTransaction(FakeDatabaseForChatTest()) {
    override suspend fun persistMessageAtomically(
        sessionId: String,
        item: ChatItem,
        onTitleUpdate: suspend (String, String) -> Unit,
        onPreviewUpdate: suspend (String, Int, String?) -> Unit
    ) {
        val (role, content, images) = when (item) {
            is ChatItem.User -> Triple(Role.USER, item.content, item.images)
            is ChatItem.Agent -> Triple(Role.ASSISTANT, item.content, item.images)
            is ChatItem.Turn -> {
                val agent = item.agent ?: return
                Triple(Role.ASSISTANT, agent.content, agent.images)
            }
            else -> return
        }
        if (content.isBlank() && images.isNullOrEmpty()) return

        val now = 0L
        messageRepo.createMessage(
            ChatMessage(
                id = item.id,
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = now,
                images = images
            )
        )

        val session = sessionRepo.getSessionById(sessionId)
        if (session != null && role == Role.USER && session.title == "New conversation") {
            val newTitle = content.trim().take(40).ifBlank { "New conversation" }
            sessionRepo.renameSession(sessionId, newTitle)
            onTitleUpdate(sessionId, newTitle)
        }

        val count = messageRepo.getMessageCount(sessionId)
        val preview = content.take(80)
        sessionRepo.updateMessageInfo(sessionId, count, preview)
        onPreviewUpdate(sessionId, count, preview)
    }
}

class FakeDatabaseForChatTest : ai.openonion.oochat.data.local.db.AppDatabase() {
    override fun agentDao() = throw NotImplementedError()
    // persistMessageAtomically is overridden to a no-op in FakePersistenceTransaction,
    // but PersistenceTransaction's own constructor eagerly calls messageDao()/sessionDao()
    // to populate its private vals, so these must return an object rather than throw.
    override fun sessionDao() = ai.openonion.oochat.domain.usecase.NoOpSessionDao
    override fun messageDao() = ai.openonion.oochat.domain.usecase.NoOpMessageDao
    override fun sessionStateDao() = throw NotImplementedError()
    override fun pendingMessageDao() = throw NotImplementedError()
    override fun clearAllTables() = throw NotImplementedError()
    // RoomDatabase's own constructor eagerly assigns `invalidationTracker = createInvalidationTracker()`
    // (see androidx.room.RoomDatabase), so this can't throw — unlike createOpenHelper, which is only
    // invoked from init(configuration), never reached when a fake is built via a bare constructor call.
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = androidx.room.InvalidationTracker(this)
    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = throw NotImplementedError()
}
