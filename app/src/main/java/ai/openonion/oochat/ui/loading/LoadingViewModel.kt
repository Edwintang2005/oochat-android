package ai.openonion.oochat.ui.loading

import ai.openonion.oochat.data.local.toConnectTarget
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ResumableConversationLookup
import ai.openonion.oochat.domain.usecase.ResumableConversationUseCase
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * How long connecting has to run before the screen starts saying how long.
 * Under this it is ordinary latency and a counter would be noise; it also sits
 * well clear of MIN_SPLASH_MS (450ms) and SUCCESS_HOLD_MS (250ms), so a normal
 * connect never shows one.
 */
internal const val LONG_WAIT_THRESHOLD_SECONDS = 5

/** Terminal outcomes the Loading screen can reach — distinct visuals for each. */
enum class LoadingOutcome { IN_PROGRESS, CONNECTED, ONBOARD_REQUIRED }

data class LoadingUiState(
    val statusMessage: String = "Initializing...",
    val progress: Float = 0f,
    val outcome: LoadingOutcome = LoadingOutcome.IN_PROGRESS,
    /** Seconds spent connecting, once that is long enough to be worth saying; null below the threshold. */
    val waitingSeconds: Int? = null
)

/** One-shot outcomes the Composable reacts to (navigation), not part of [LoadingUiState]. */
sealed class LoadingEvent {
    data class Failed(val message: String) : LoadingEvent()
    data object Cancelled : LoadingEvent()
    /** Auto-navigates rather than waiting for user confirmation. */
    data object ConnectSucceeded : LoadingEvent()
}

/**
 * Routes connection through the shared use case (retry/timeout/session-restore logic)
 * instead of duplicating it; only observes uiState/events to match ChatViewModel's MVVM boundary.
 */
open class LoadingViewModel @JvmOverloads constructor(
    application: Application,
    private val configRepository: ConnectionConfigRepository = application.appContainer.configRepository,
    private val connectUseCase: ConnectToAgentUseCaseContract = application.appContainer.connectToAgentUseCase,
    private val resumableConversation: ResumableConversationLookup = ResumableConversationUseCase(
        application.appContainer.agentRepository,
        application.appContainer.sessionRepository
    ),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LoadingUiState())
    val uiState: StateFlow<LoadingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LoadingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LoadingEvent> = _events.asSharedFlow()

    // job.cancel() is cooperative and not instant; hasReportedOutcome makes
    // the first terminal outcome win, turning every later one into a no-op.
    private var hasReportedOutcome = false
    private var connectionJob: Job? = null

    /** Starts the connection attempt once; safe to call repeatedly (e.g. from recomposition). */
    fun start() {
        if (connectionJob != null) return
        connectionJob = viewModelScope.launch {
            // Declared out here so the finally can reach them: both loop
            // forever, and a throw between launching them and the select
            // below would otherwise leave this coroutine unable to complete.
            var observerJob: Job? = null
            var elapsedJob: Job? = null
            try {
                // No sleep between these steps: the config read and the
                // validation below are sub-millisecond, so a delay() here only
                // held two placeholder strings on screen. Progress is driven by
                // real milestones; MIN_SPLASH_MS and SUCCESS_HOLD_MS own the
                // "don't flash" job at the two ends of this screen.
                _uiState.update { it.copy(statusMessage = "Loading configuration...", progress = 0.2f) }

                val config = configRepository.getConfig()
                if (config == null) {
                    reportFailure("No configuration found")
                    return@launch
                }

                _uiState.update { it.copy(statusMessage = "Validating configuration...", progress = 0.4f) }

                if (!config.isValid) {
                    reportFailure("Invalid configuration - server URL is missing")
                    return@launch
                }

                if (!config.serverUrl.startsWith("wss://") && !config.serverUrl.startsWith("ws://") &&
                    !config.serverUrl.startsWith("https://") && !config.serverUrl.startsWith("http://")
                ) {
                    reportFailure("Invalid server URL format")
                    return@launch
                }

                _uiState.update {
                    it.copy(statusMessage = "Connecting to ${config.shortDisplayUrl}...", progress = 0.6f)
                }

                // Live status/progress while connect() (below) blocks —
                // ConnectToAgentUseCaseContract exposes no incremental
                // progress of its own, so we derive it from connectionState
                // transitions instead of re-deriving timeout/retry logic.
                observerJob = launch {
                    connectUseCase.connectionState.collect { state ->
                        when (state) {
                            is ConnectionState.Connecting ->
                                _uiState.update { it.copy(progress = maxOf(it.progress, 0.85f)) }
                            is ConnectionState.Reconnecting ->
                                _uiState.update { it.copy(statusMessage = "Retrying connection...") }
                            else -> { /* Connected/Error/Disconnected handled after connect() returns */ }
                        }
                    }
                }

                // The bar has no milestone left to report while connect()
                // blocks — up to ~60s against an unreachable relay — and one
                // that stops moving reads as a hang. Counting the wait is the
                // sign of life; it never claims progress that isn't happening.
                elapsedJob = launch {
                    var seconds = 0
                    while (true) {
                        delay(1_000)
                        seconds++
                        if (seconds >= LONG_WAIT_THRESHOLD_SECONDS) {
                            _uiState.update { it.copy(waitingSeconds = seconds) }
                        }
                    }
                }

                val target = config.toConnectTarget()
                val agentAddress = target.agentAddress
                val directUrl = target.directUrl

                // Resolved before the socket opens, not corrected afterwards:
                // connecting with no conversation makes the server mint a
                // session that ChatScreen's own connect immediately abandons —
                // an orphaned server session, a second CONNECT signature, and a
                // Connected -> Reconnecting -> Connected flicker on every cold
                // start. Null for an agent with no local history; the server
                // mints a session and ChatScreen joins that same connection.
                val conversationId = resumableConversation.forAgent(agentAddress)

                // Some agents never send CONNECTED, only ONBOARD_REQUIRED, so race
                // the two rather than blocking the full retry timeout.
                val onboardDeferred = async {
                    connectUseCase.observeEvents()
                        .filterIsInstance<ChatEvent.ChatItemReceived>()
                        .mapNotNull { it.item as? ChatItem.OnboardRequired }
                        .first()
                }
                val connectDeferred = async {
                    // Named, not positional: conversationId sits between these
                    // two and is also String?, so the old positional call
                    // bound directUrl to it and still compiled.
                    connectUseCase.connect(
                        agentAddress = agentAddress,
                        conversationId = conversationId,
                        directUrl = directUrl
                    )
                }

                val onboardWon = select<Boolean> {
                    onboardDeferred.onAwait { true }
                    connectDeferred.onAwait { false }
                }

                // Cancelled here, not just in the finally: the observer would
                // otherwise still be free to overwrite "Connected!" with
                // "Retrying connection..." on its way out.
                observerJob?.cancel()
                elapsedJob?.cancel()
                _uiState.update { it.copy(waitingSeconds = null) }

                // If the user already tapped Cancel, don't flip the UI to a
                // success/failure state on a screen that's on its way out.
                if (hasReportedOutcome) {
                    onboardDeferred.cancel()
                    connectDeferred.cancel()
                    return@launch
                }

                if (onboardWon) {
                    connectDeferred.cancel()
                    _uiState.update {
                        it.copy(
                            statusMessage = "Onboarding required",
                            progress = 1.0f,
                            outcome = LoadingOutcome.ONBOARD_REQUIRED
                        )
                    }
                    // Wait for the user to tap "Continue to Onboarding" —
                    // the gate is a manual decision they need to make.
                } else {
                    onboardDeferred.cancel()
                    val connected = connectDeferred.await()
                    if (connected) {
                        _uiState.update {
                            it.copy(statusMessage = "Connected!", progress = 1.0f, outcome = LoadingOutcome.CONNECTED)
                        }
                        _events.tryEmit(LoadingEvent.ConnectSucceeded)
                    } else {
                        val lastError = (connectUseCase.connectionState.value as? ConnectionState.Error)?.message
                        reportFailure(lastError ?: "Connection failed")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e.message ?: "Unknown error")
            } finally {
                observerJob?.cancel()
                elapsedJob?.cancel()
            }
        }
    }

    fun cancel() {
        if (hasReportedOutcome) return
        hasReportedOutcome = true
        connectionJob?.cancel()
        viewModelScope.launch { connectUseCase.disconnect() }
        _events.tryEmit(LoadingEvent.Cancelled)
    }

    private fun reportFailure(message: String) {
        if (hasReportedOutcome) return
        hasReportedOutcome = true
        _events.tryEmit(LoadingEvent.Failed(message))
    }

    // No onCleared() teardown on purpose: connectUseCase is the app-scoped
    // shared connection (see AppContainer), and ChatScreen is about to
    // reuse the very socket this screen just opened. Cancel() disconnects.
}
