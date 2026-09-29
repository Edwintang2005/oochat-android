package ai.openonion.oochat.di

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.AgentReplyNotifier
import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.DataStoreAppSettings
import ai.openonion.oochat.data.local.DataStoreIgnoredIdsStorage
import ai.openonion.oochat.data.local.FileAttachmentStore
import ai.openonion.oochat.data.local.FileAttachmentStoreImpl
import ai.openonion.oochat.data.local.IgnoredIdsStorage
import ai.openonion.oochat.data.local.ImageAttachmentStore
import ai.openonion.oochat.data.local.ImageAttachmentStoreImpl
import ai.openonion.oochat.data.local.RoomPendingMessageSink
import ai.openonion.oochat.data.local.SafePreferencesWrapper
import ai.openonion.oochat.data.local.VoiceRecorderStore
import ai.openonion.oochat.data.local.VoiceRecorderStoreImpl
import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.AccountRepositoryImpl
import ai.openonion.oochat.data.repository.AgentDiscoveryRepository
import ai.openonion.oochat.data.repository.AgentDiscoveryRepositoryImpl
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.AgentRepositoryImpl
import ai.openonion.oochat.data.repository.AgentSecureConfigRepositoryImpl
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepositoryContract
import ai.openonion.oochat.data.repository.EncryptedPreferencesConnectionConfigRepository
import ai.openonion.oochat.data.repository.MemoryGatedSessionStore
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.MessageRepositoryImpl
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.data.repository.SessionRepositoryImpl
import ai.openonion.oochat.data.repository.SessionStore
import ai.openonion.oochat.data.repository.SessionStoreImpl
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCase
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.PersistenceTransaction
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.AndroidSpeechRecognitionService
import ai.openonion.oochat.network.ConnectivityManagerNetworkMonitor
import ai.openonion.oochat.network.NetworkMonitor
import ai.openonion.oochat.network.SpeechRecognitionService
import ai.openonion.oochat.network.VoiceTranscriptionService
import ai.openonion.oochat.network.VoiceTranscriptionServiceImpl
import ai.openonion.oochat.notification.AndroidAgentReplyNotifier
import android.content.Context
import okhttp3.OkHttpClient

/**
 * The one [OkHttpClient] for the whole app.
 *
 * Five call sites used to build their own, four of them against
 * `oo.openonion.ai`, so a TLS handshake was paid per client and each carried
 * its own dispatcher thread pool and connection pool. Callers that need
 * different timeouts derive from this with `newBuilder()`, which keeps the
 * shared dispatcher and connection pool — that sharing is the whole point.
 *
 * A top-level `by lazy` rather than an [AppContainer] member so the classes
 * that build their own client from a constructor default (ChatViewModel's
 * [AgentDiscoveryService], ConnectToAgentUseCase's) reach the same instance;
 * a container property would only cover the call sites the container itself
 * constructs.
 */
val sharedHttpClient: OkHttpClient by lazy { OkHttpClient() }

/**
 * Application-scoped composition root.
 *
 * Every repository is built once (`by lazy`) and handed out from here
 * instead of each screen/ViewModel re-deriving it via
 * `remember { XImpl(context) }` or a constructor-default expression that
 * calls [AppDatabase.getInstance] from scratch. Kept as an interface (not
 * just a concrete class) so a `FakeAppContainer` can substitute in-memory
 * fakes for instrumented/integration tests without touching production code.
 *
 * [connectToAgentUseCase] is a lazy singleton like everything else here, so
 * the app holds exactly one live connection. A per-ViewModel factory gave
 * LoadingScreen's probe and ChatViewModel a socket each, which meant two
 * server sessions racing to write `session_states` (keyed on agent address,
 * REPLACE) and doubled reconnect churn on every backgrounding. Nothing
 * closes it from `onCleared()` — a shared connection must outlive any one
 * screen — so it lives for the process; user-driven teardown is `disconnect()`.
 */
interface AppContainer {
    val keyManager: KeyManager
    val configRepository: ConnectionConfigRepository
    val agentRepository: AgentRepository
    val sessionRepository: SessionRepository
    val messageRepository: MessageRepository
    val ignoredIdsStorage: IgnoredIdsStorage
    val defaultAgentRepository: DefaultAgentRepositoryContract
    val discoveryRepository: AgentDiscoveryRepository
    val accountRepository: AccountRepository
    val imageAttachmentStore: ImageAttachmentStore
    val fileAttachmentStore: FileAttachmentStore
    val voiceRecorderStore: VoiceRecorderStore
    val voiceTranscriptionService: VoiceTranscriptionService
    val speechRecognitionService: SpeechRecognitionService
    val appSettings: AppSettings
    val persistenceTransaction: PersistenceTransaction

    /** Posts the "agent replied while you were away" system notification — see [AgentReplyNotifier]'s own doc. */
    val agentReplyNotifier: AgentReplyNotifier

    /**
     * Persisted protocol-session store (session_states), one row per local
     * conversation — the connection restores and saves each conversation's
     * own wire session through it. Memory-gated: with the Memory toggle off
     * reads return null, so a conversation starts a fresh server session
     * instead of resuming its own.
     */
    val sessionStore: SessionStore

    /**
     * Whether every Keystore-backed store ([SafePreferencesWrapper]) on this
     * device is actually encrypted. False means at least one of them is
     * writing plaintext — surfaced as a warning in Settings rather than
     * silently accepted.
     *
     * This deliberately aggregates across stores. The previous version read a
     * single instance on the assumption that "a Keystore failure is
     * device-wide, so one instance reflects them all", which is not true:
     * `EncryptedSharedPreferences` derives a keyset per file, and a keyset can
     * be lost or corrupted for one file alone.
     */
    val isSecureStorage: Boolean

    /**
     * Whether the store holding the Ed25519 private key and the BIP39 recovery
     * phrase is encrypted.
     *
     * Split out from [isSecureStorage] because the two are not equally bad. An
     * unencrypted API key is a credential the user can rotate; an unencrypted
     * recovery phrase is the identity itself, in plain English words, readable
     * by anything that gets at the app's data directory. Settings makes that
     * warning non-dismissible.
     */
    val isIdentityStorageSecure: Boolean

    /**
     * Address resolution (`/info` lookups), shared because it owns an
     * [okhttp3.OkHttpClient]: a second instance is a second connection pool
     * and dispatcher thread pool, and the two `/info` GETs one connect makes
     * (ChatViewModel resolves, then [ConnectToAgentUseCase] resolves again)
     * then cannot reuse a pooled TLS connection between them.
     */
    val agentDiscovery: AgentDiscoveryService

    /** The one shared [ConnectToAgentUseCaseContract] for the whole app — see class doc. */
    val connectToAgentUseCase: ConnectToAgentUseCaseContract

    /**
     * Device-level connectivity, the fast half of the disconnect signal —
     * see [NetworkMonitor]'s doc for why the socket alone is too slow.
     */
    val networkMonitor: NetworkMonitor
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.getInstance(appContext) }
    private val safePrefs by lazy { SafePreferencesWrapper(appContext) }

    override val keyManager: KeyManager by lazy { KeyManager(appContext) }

    override val configRepository: ConnectionConfigRepository by lazy {
        EncryptedPreferencesConnectionConfigRepository(appContext)
    }

    override val agentRepository: AgentRepository by lazy {
        AgentRepositoryImpl(database.agentDao(), AgentSecureConfigRepositoryImpl(appContext))
    }

    override val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(database.sessionDao())
    }

    override val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(database.messageDao())
    }

    override val ignoredIdsStorage: IgnoredIdsStorage by lazy {
        DataStoreIgnoredIdsStorage(appContext)
    }

    override val defaultAgentRepository: DefaultAgentRepositoryContract by lazy {
        DefaultAgentRepository(safePrefs, agentRepository)
    }

    override val discoveryRepository: AgentDiscoveryRepository by lazy {
        AgentDiscoveryRepositoryImpl()
    }

    override val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(keyManager)
    }

    override val imageAttachmentStore: ImageAttachmentStore by lazy {
        ImageAttachmentStoreImpl(appContext)
    }

    override val fileAttachmentStore: FileAttachmentStore by lazy {
        FileAttachmentStoreImpl(appContext)
    }

    override val voiceRecorderStore: VoiceRecorderStore by lazy {
        VoiceRecorderStoreImpl(appContext)
    }

    override val voiceTranscriptionService: VoiceTranscriptionService by lazy {
        VoiceTranscriptionServiceImpl(accountRepository)
    }

    override val speechRecognitionService: SpeechRecognitionService by lazy {
        AndroidSpeechRecognitionService(appContext)
    }

    override val appSettings: AppSettings by lazy {
        DataStoreAppSettings(appContext)
    }

    override val persistenceTransaction: PersistenceTransaction by lazy {
        PersistenceTransaction(database)
    }

    override val agentReplyNotifier: AgentReplyNotifier by lazy {
        AndroidAgentReplyNotifier(appContext)
    }

    override val isSecureStorage: Boolean
        get() = probeSecureStores().isEmpty()

    override val isIdentityStorageSecure: Boolean
        get() = KeyManager.PREFS_NAME !in probeSecureStores()

    /**
     * Opens the stores that hold secrets, then reports which of them are
     * sitting in plaintext.
     *
     * This used to be `safePrefs.isSecure()`, which reported on
     * `connectonion_prefs` — a store holding the default-agent id. The private
     * key and recovery phrase live in `connectonion_keys_secure`, a different
     * file with its own `EncryptedSharedPreferences` keyset, so the one store
     * the warning consulted was not the one that mattered. A keyset corrupted
     * for the identity file alone put the key and the phrase on disk in
     * plaintext while the banner stayed hidden.
     *
     * Both stores are touched before reading, because a `by lazy` store that
     * has never been opened has never reported anything.
     */
    private fun probeSecureStores(): Set<String> {
        safePrefs.isSecure()
        keyManager.probeStorage()
        return SafePreferencesWrapper.degradedStores()
    }

    override val sessionStore: SessionStore by lazy {
        MemoryGatedSessionStore(
            delegate = SessionStoreImpl(database.sessionStateDao()),
            memoryEnabled = appSettings.memoryEnabled
        )
    }

    override val agentDiscovery: AgentDiscoveryService by lazy { AgentDiscoveryService() }

    override val connectToAgentUseCase: ConnectToAgentUseCaseContract by lazy {
        ConnectToAgentUseCase(
            keyManager = keyManager,
            sessionStore = sessionStore,
            agentDiscovery = agentDiscovery,
            // App-private, alongside the attachment copies themselves — the
            // outbox row only names its spill, it does not hold it.
            pendingMessageSink = RoomPendingMessageSink(
                dao = database.pendingMessageDao(),
                spillDir = java.io.File(appContext.filesDir, "pending-attachments")
            )
        )
    }

    override val networkMonitor: NetworkMonitor by lazy {
        ConnectivityManagerNetworkMonitor(appContext)
    }
}

/** Convenience accessor for Composables/ViewModels holding a plain [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ai.openonion.oochat.ConnectOnionApplication).container
