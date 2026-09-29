package ai.openonion.oochat.di

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.AgentReplyNotifier
import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.local.FileAttachResult
import ai.openonion.oochat.data.local.FileAttachmentStore
import ai.openonion.oochat.data.local.IgnoredIdsStorage
import ai.openonion.oochat.data.local.ImageAttachmentStore
import ai.openonion.oochat.data.local.RecordedVoice
import ai.openonion.oochat.data.local.VoiceRecorderStore
import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.AccountSnapshot
import ai.openonion.oochat.data.repository.AgentDiscoveryRepository
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepositoryContract
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.UserProfile
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.PersistenceTransaction
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.RecognizerReadiness
import ai.openonion.oochat.network.SpeechRecognitionEvent
import ai.openonion.oochat.network.SpeechRecognitionService
import ai.openonion.oochat.network.VoiceTranscriptionService
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * NOTE: this file was out of sync with the current repository interfaces
 * (each Fake below implemented an older/smaller shape of its interface,
 * e.g. missing [ConnectionConfigRepository.observeConfig], a completely
 * different [AgentRepository]/[SessionRepository]/[MessageRepository]
 * surface, `Any`-typed [ConnectToAgentUseCaseContract.connectionState],
 * etc.) and did not compile — `:app:compileDebugAndroidTestKotlin` failed
 * before any androidTest file (including this one) could be built, so this
 * was brought back in line with the real interfaces as minimally as
 * possible (same "return an empty/no-op default" behavior throughout) to
 * unblock the androidTest source set. See ChatViewModelTest's JVM-side
 * fakes for the pattern this mirrors.
 *
 * [FakeConnectionConfigRepository] additionally accepts an [initialConfig]
 * so a test can seed a saved [ConnectionConfig] up front (e.g. to exercise
 * OnboardingScreen/RecoveryScreen's prefill-from-saved-config behavior)
 * without a separate one-off fake per test file.
 */
class FakeAppContainer(
    context: Context,
    // KeyManager is a concrete (final) class, not an interface, so unlike
    // every other dependency here it cannot be swapped for a lightweight
    // fake — AppContainer.keyManager is typed as KeyManager itself. This
    // constructs a real one against the instrumented test's Context;
    // whichever of its (Keystore/libsodium-backed) operations a test
    // actually exercises still runs for real.
    override val keyManager: KeyManager = KeyManager(context),
    override val configRepository: ConnectionConfigRepository = FakeConnectionConfigRepository(),
    override val agentRepository: AgentRepository = FakeAgentRepository(),
    override val sessionRepository: SessionRepository = FakeSessionRepository(),
    override val messageRepository: MessageRepository = FakeMessageRepository(),
    override val ignoredIdsStorage: IgnoredIdsStorage = FakeIgnoredIdsStorage(),
    override val defaultAgentRepository: DefaultAgentRepositoryContract = FakeDefaultAgentRepository(),
    override val discoveryRepository: AgentDiscoveryRepository = FakeAgentDiscoveryRepository(),
    override val accountRepository: AccountRepository = FakeAccountRepository(),
    override val imageAttachmentStore: ImageAttachmentStore = FakeImageAttachmentStore(),
    override val fileAttachmentStore: FileAttachmentStore = FakeFileAttachmentStore(),
    override val voiceRecorderStore: VoiceRecorderStore = FakeVoiceRecorderStore(),
    override val voiceTranscriptionService: VoiceTranscriptionService = FakeVoiceTranscriptionService(),
    override val speechRecognitionService: SpeechRecognitionService = FakeSpeechRecognitionService(),
    override val appSettings: AppSettings = FakeAppSettings(),
    override val persistenceTransaction: PersistenceTransaction = FakePersistenceTransaction(context),
    override val agentReplyNotifier: AgentReplyNotifier = FakeAgentReplyNotifier(),
    override val isSecureStorage: Boolean = true,
    override val isIdentityStorageSecure: Boolean = true,
    // Real instance: for a hex address it resolves synchronously, no HTTP.
    override val agentDiscovery: AgentDiscoveryService = AgentDiscoveryService(),
    override val sessionStore: ai.openonion.oochat.data.repository.SessionStore = FakeSessionStore()
) : AppContainer {
    override val connectToAgentUseCase: ConnectToAgentUseCaseContract = FakeConnectToAgentUseCase()

    /** Always online: instrumented runs have no way to pull the device's radio. */
    override val networkMonitor: ai.openonion.oochat.network.NetworkMonitor =
        object : ai.openonion.oochat.network.NetworkMonitor {
            override val isOnline: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
        }
}

class FakeSessionStore : ai.openonion.oochat.data.repository.SessionStore {
    private val sessions = mutableMapOf<String, ai.openonion.oochat.data.protocol.SessionState>()
    override suspend fun saveSession(conversationId: String, session: ai.openonion.oochat.data.protocol.SessionState) {
        sessions[conversationId] = session
    }
    override suspend fun getSession(conversationId: String) = sessions[conversationId]
    override suspend fun deleteSessionByConversation(conversationId: String) {
        sessions.remove(conversationId)
    }
    override suspend fun deleteOrphanedSessions(): Int = 0
}

class FakeConnectionConfigRepository(
    initialConfig: ConnectionConfig? = null
) : ConnectionConfigRepository {
    private var config: ConnectionConfig? = initialConfig
    private val configFlow = MutableStateFlow(initialConfig)

    override suspend fun getConfig() = config
    override fun observeConfig(): Flow<ConnectionConfig?> = configFlow
    override suspend fun saveConfig(config: ConnectionConfig) {
        this.config = config
        configFlow.value = config
    }
    override suspend fun deleteConfig() {
        config = null
        configFlow.value = null
    }
    override suspend fun hasConfig() = config != null
    override suspend fun updateLastConnected(timestamp: Long) {}
    override suspend fun updateAgentAddress(address: String?) {
        config = config?.copy(agentAddress = address)
        configFlow.value = config
    }
}

class FakeAgentRepository : AgentRepository {
    override fun getAllAgents() = kotlinx.coroutines.flow.emptyFlow<List<AgentProfile>>()
    override fun getActiveAgents() = kotlinx.coroutines.flow.emptyFlow<List<AgentProfile>>()
    override suspend fun getAgentById(id: String): AgentProfile? = null
    override suspend fun getAgentByAddress(address: String): AgentProfile? = null
    override suspend fun createAgent(agent: AgentProfile) = agent
    override suspend fun updateAgent(agent: AgentProfile) {}
    override suspend fun deleteAgent(agentId: String) {}
    override suspend fun updateLastConnected(agentId: String) {}
    override suspend fun getDefaultAgent(): AgentProfile? = null
    override suspend fun reorderAgents(orderedIds: List<String>) {}
}

class FakeSessionRepository : SessionRepository {
    override fun getAllSessions() = kotlinx.coroutines.flow.emptyFlow<List<ChatSession>>()
    override fun getSessionsByAgent(agentId: String) = kotlinx.coroutines.flow.emptyFlow<List<ChatSession>>()
    override suspend fun getSessionById(id: String): ChatSession? = null
    override suspend fun createSession(agentId: String, title: String, id: String) = ChatSession(
        id = id,
        agentId = agentId,
        title = title,
        createdAt = 0L,
        updatedAt = 0L
    )
    override suspend fun deleteSession(sessionId: String) {}
    override suspend fun renameSession(sessionId: String, newTitle: String) {}
    override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {}
}

class FakeMessageRepository : MessageRepository {
    override suspend fun getMessagesListBySession(sessionId: String): List<ChatMessage> = emptyList()
    override suspend fun createMessage(message: ChatMessage) {}
    override suspend fun deleteMessagesBySession(sessionId: String) {}
    override suspend fun getMessageCount(sessionId: String) = 0
    override suspend fun existsById(id: String) = false
    override suspend fun getOwningSessionId(id: String): String? = null
    override suspend fun getSessionIdByUserContent(content: String): String? = null
}

class FakeIgnoredIdsStorage : IgnoredIdsStorage {
    override suspend fun loadAll(): Map<String, Set<String>> = emptyMap()
    override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {}
}

class FakeDefaultAgentRepository : DefaultAgentRepositoryContract {
    override suspend fun getDefaultAgent(): AgentProfile? = null
    override suspend fun setDefaultAgent(agentId: String) {}
    override suspend fun clearDefaultAgent() {}
    override suspend fun hasDefaultAgent() = false
}

class FakeAgentDiscoveryRepository : AgentDiscoveryRepository {
    override suspend fun discoverFromRelay(relayUrl: String): List<AgentProfile> = emptyList()
}

class FakeAccountRepository : AccountRepository {
    override suspend fun loadAccount(forceReauth: Boolean) = Result.success(
        AccountSnapshot(
            apiKey = "",
            profile = UserProfile(publicKey = "", creditsUsd = 0.0, totalCostUsd = 0.0, balanceUsd = 0.0)
        )
    )
    override fun invalidate() {}
}

class FakeImageAttachmentStore : ImageAttachmentStore {
    override suspend fun store(uriString: String) = null
}

class FakeFileAttachmentStore : FileAttachmentStore {
    override suspend fun store(uriString: String): FileAttachResult = FileAttachResult.Failed(uriString)
}

class FakeVoiceRecorderStore : VoiceRecorderStore {
    override suspend fun startRecording() = true
    override suspend fun stopRecording(): RecordedVoice? = null
    override suspend fun cancelRecording() {}
}

class FakeVoiceTranscriptionService : VoiceTranscriptionService {
    override suspend fun transcribe(audioFile: java.io.File): Result<String> = Result.success("")
}

/** Reports no recognizer, so instrumentation never opens a real microphone. */
class FakeSpeechRecognitionService : SpeechRecognitionService {
    override suspend fun readiness() = RecognizerReadiness.UNAVAILABLE
    override fun recordProbeResult(usable: Boolean) {}
    override fun listen(): Flow<SpeechRecognitionEvent> = emptyFlow()
    override suspend fun stop() {}
}

class FakeAppSettings : AppSettings {
    private val renderMarkdownFlow = MutableStateFlow(true)
    override val renderMarkdown: Flow<Boolean> = renderMarkdownFlow
    override suspend fun setRenderMarkdown(value: Boolean) { renderMarkdownFlow.value = value }

    private val fontSizeIndexFlow = MutableStateFlow(1)
    override val fontSizeIndex: Flow<Int> = fontSizeIndexFlow
    override suspend fun setFontSizeIndex(value: Int) { fontSizeIndexFlow.value = value }

    private val hapticFeedbackFlow = MutableStateFlow(true)
    override val hapticFeedback: Flow<Boolean> = hapticFeedbackFlow
    override suspend fun setHapticFeedback(value: Boolean) { hapticFeedbackFlow.value = value }

    private val soundEffectsFlow = MutableStateFlow(true)
    override val soundEffects: Flow<Boolean> = soundEffectsFlow
    override suspend fun setSoundEffects(value: Boolean) { soundEffectsFlow.value = value }

    private val streamingResponsesFlow = MutableStateFlow(true)
    override val streamingResponses: Flow<Boolean> = streamingResponsesFlow
    override suspend fun setStreamingResponses(value: Boolean) { streamingResponsesFlow.value = value }

    private val memoryEnabledFlow = MutableStateFlow(true)
    override val memoryEnabled: Flow<Boolean> = memoryEnabledFlow
    override suspend fun setMemoryEnabled(value: Boolean) { memoryEnabledFlow.value = value }

    private val customInstructionsFlow = MutableStateFlow("")
    override val customInstructions: Flow<String> = customInstructionsFlow
    override suspend fun setCustomInstructions(value: String) { customInstructionsFlow.value = value }

    private val pushNotificationsEnabledFlow = MutableStateFlow(true)
    override val pushNotificationsEnabled: Flow<Boolean> = pushNotificationsEnabledFlow
    override suspend fun setPushNotificationsEnabled(value: Boolean) { pushNotificationsEnabledFlow.value = value }

    private val notificationSoundFlow = MutableStateFlow("Chime")
    override val notificationSound: Flow<String> = notificationSoundFlow
    override suspend fun setNotificationSound(value: String) { notificationSoundFlow.value = value }

    private val analyticsEnabledFlow = MutableStateFlow(false)
    override val analyticsEnabled: Flow<Boolean> = analyticsEnabledFlow
    override suspend fun setAnalyticsEnabled(value: Boolean) { analyticsEnabledFlow.value = value }

    private val safeModeEnabledFlow = MutableStateFlow(false)
    override val safeModeEnabled: Flow<Boolean> = safeModeEnabledFlow
    override suspend fun setSafeModeEnabled(value: Boolean) { safeModeEnabledFlow.value = value }
}

class FakeAgentReplyNotifier : AgentReplyNotifier {
    var notified: Pair<String?, String>? = null
    var cleared = false
    override fun notifyAgentReply(agentName: String?, preview: String) { notified = agentName to preview }
    override fun clearReplyNotifications() { cleared = true }
}

class FakeConnectToAgentUseCase : ConnectToAgentUseCaseContract {
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)
    override val agentProfile: StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> =
        MutableStateFlow(null)
    override val dashboardHtml: StateFlow<String?> = MutableStateFlow(null)
    override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?) = true
    override suspend fun switchConversation(conversationId: String?) {}
    override fun liveSessionIdFor(agentAddress: String): String? = null
    override fun observeEvents(): Flow<ChatEvent> = kotlinx.coroutines.flow.emptyFlow()
    override fun retryNow() {}
    override suspend fun querySessionStatus() {}

    override suspend fun disconnect() {}
    override fun isConnected() = false
    override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>?) {}
    override suspend fun respond(answer: String) {}
    override suspend fun interrupt() {}
    override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {}
    override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {}
    override suspend fun respondToPlanReview(message: String) {}
    override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {}
    override val approvalMode: StateFlow<ai.openonion.oochat.domain.model.ApprovalMode> =
        MutableStateFlow(ai.openonion.oochat.domain.model.ApprovalMode.DEFAULT)
    override val modePending: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun setMode(mode: ai.openonion.oochat.domain.model.ApprovalMode, turns: Int?) {}
    override suspend fun reset() {}
}

/**
 * [PersistenceTransaction] (like [KeyManager] above) is a concrete class,
 * not an interface, so it can't be hand-faked — it's constructed from a
 * real [ai.openonion.oochat.data.local.db.AppDatabase]. Rather than
 * manually re-implementing [androidx.room.RoomDatabase]'s generated
 * internals (fragile and Room-version-specific), this builds a real,
 * Room-generated in-memory database against the instrumented test's
 * Context — cheap, and never actually touched anyway since
 * [persistMessageAtomically] itself is overridden to a no-op below.
 */
class FakePersistenceTransaction(context: Context) : PersistenceTransaction(
    androidx.room.Room.inMemoryDatabaseBuilder(
        context,
        ai.openonion.oochat.data.local.db.AppDatabase::class.java
    ).allowMainThreadQueries().build()
) {
    override suspend fun persistMessageAtomically(
        sessionId: String,
        item: ai.openonion.oochat.domain.model.ChatItem,
        onTitleUpdate: suspend (String, String) -> Unit,
        onPreviewUpdate: suspend (String, Int, String?) -> Unit
    ) {
    }
}
