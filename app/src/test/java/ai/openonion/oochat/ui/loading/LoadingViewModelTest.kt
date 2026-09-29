package ai.openonion.oochat.ui.loading

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LoadingViewModel] — the MVVM boundary that replaced
 * LoadingScreen's hand-rolled connect/timeout logic (see class doc on
 * [LoadingViewModel]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadingViewModelTest {

    private class FakeConnectToAgentUseCase : ConnectToAgentUseCaseContract {
        val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState
        override val agentProfile: StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> =
            MutableStateFlow(null)
        override val dashboardHtml: StateFlow<String?> = MutableStateFlow(null)

        var connectResult: Boolean = true
        var connectInvocations = 0
        var lastAddress: String? = null
        var lastDirectUrl: String? = null
        var lastConversationId: String? = null
        /** If set, run before returning [connectResult] — lets a test drive connectionState transitions. */
        var onConnect: (suspend () -> Unit)? = null
        /** When true, connect() never resolves — used to let an ONBOARD_REQUIRED event win the race deterministically. */
        var suspendConnectForever = false

        override suspend fun switchConversation(conversationId: String?) {}

        override fun liveSessionIdFor(agentAddress: String): String? = null

        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?): Boolean {
            connectInvocations++
            lastAddress = agentAddress
            lastConversationId = conversationId
            lastDirectUrl = directUrl
            onConnect?.invoke()
            if (suspendConnectForever) awaitCancellation()
            return connectResult
        }

        private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
        override fun observeEvents(): Flow<ChatEvent> = _events

        fun emitOnboardRequired(
            item: ChatItem.OnboardRequired = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        ) {
            _events.tryEmit(ChatEvent.ChatItemReceived(item))
        }
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

        var disconnectCount = 0
            override fun retryNow() {}
        override suspend fun querySessionStatus() {}
override suspend fun disconnect() { disconnectCount++ }

        var isConnectedResult = false
        override fun isConnected(): Boolean = isConnectedResult

        var resetCount = 0
        override suspend fun reset() { resetCount++ }
    }

    private class FakeConnectionConfigRepository : ConnectionConfigRepository {
        var config: ConnectionConfig? = ConnectionConfig(
            serverUrl = "https://relay.example.com",
            agentAddress = "0xabc123"
        )

        override suspend fun getConfig(): ConnectionConfig? = config
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) { this.config = config }
        override suspend fun deleteConfig() { config = null }
        override suspend fun hasConfig(): Boolean = config != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    // A single shared instance so every `runTest(testDispatcher)` call
    // below advances the same virtual clock that Dispatchers.Main (and
    // therefore viewModelScope.launch) is pinned to in setUp() — a fresh
    // UnconfinedTestDispatcher() per test would have its own independent
    // TestCoroutineScheduler, and advanceUntilIdle() would only advance
    // the runTest scope's scheduler, never the ViewModel's.
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var connectUseCase: FakeConnectToAgentUseCase
    private lateinit var configRepository: FakeConnectionConfigRepository
    private lateinit var viewModel: LoadingViewModel

    /** What the agent's conversation resolves to; null models a fresh install. */
    private var resumableConversationId: String? = null

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        connectUseCase = FakeConnectToAgentUseCase()
        configRepository = FakeConnectionConfigRepository()
        resumableConversationId = null
        viewModel = LoadingViewModel(
            application = Application(),
            configRepository = configRepository,
            connectUseCase = connectUseCase,
            resumableConversation = { resumableConversationId }
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `a direct-URL agent passes its URL as the URL, not as a conversation id`() = runTest(testDispatcher) {
        // conversationId was inserted between agentAddress and directUrl and
        // is also String?, so a positional call still compiled while binding
        // the URL to the wrong slot — the probe would then have connected via
        // the relay and handed a URL to the session layer as a conversation.
        configRepository.config = ConnectionConfig(
            serverUrl = "https://my-agent.example.com",
            agentAddress = "0xabc123"
        )

        viewModel.start()
        advanceUntilIdle()

        assertEquals("https://my-agent.example.com", connectUseCase.lastDirectUrl)
        assertNull(
            "this agent has no local history, so there is no conversation to resume",
            connectUseCase.lastConversationId
        )
    }

    @Test
    fun `a cold start connects on the conversation it is going to resume`() = runTest(testDispatcher) {
        // The probe used to hardcode conversationId = null, so the server minted
        // a session that ChatScreen's connect abandoned a second later — an
        // orphaned session and a Connected -> Reconnecting -> Connected flicker
        // on every launch. One connect, already named.
        resumableConversationId = "79da58ad-conversation"

        viewModel.start()
        advanceUntilIdle()

        assertEquals(1, connectUseCase.connectInvocations)
        assertEquals("79da58ad-conversation", connectUseCase.lastConversationId)
    }

    @Test
    fun `a fresh install connects unnamed instead of waiting for a session`() = runTest(testDispatcher) {
        // Nothing local to resume. The probe must connect anyway and let the
        // server mint the session, not block on a conversation that will
        // never arrive.
        resumableConversationId = null

        viewModel.start()
        advanceUntilIdle()

        assertEquals(1, connectUseCase.connectInvocations)
        assertNull(connectUseCase.lastConversationId)
        assertEquals(LoadingOutcome.CONNECTED, viewModel.uiState.value.outcome)
    }

    @Test
    fun `start on success reaches CONNECTED outcome and emits ConnectSucceeded`() = runTest(testDispatcher) {
        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.CONNECTED, viewModel.uiState.value.outcome)
        assertEquals(1.0f, viewModel.uiState.value.progress)
        // Successful connect now emits ConnectSucceeded (auto-navigate to
        // ChatScreen) — there is no longer a separate user-confirm tap.
        // No Failed/Cancelled event must accompany it.
        assertEquals(listOf<LoadingEvent>(LoadingEvent.ConnectSucceeded), events.toList())
        assertEquals(1, connectUseCase.connectInvocations)
        collectJob.cancel()
    }

    @Test
    fun `an ONBOARD_REQUIRED chat event wins the race and abandons connect()`() = runTest(testDispatcher) {
        // connect() never resolves on its own — only the event flow can
        // produce a terminal outcome here, proving the race is driven by
        // the event, not by connect() eventually returning.
        connectUseCase.suspendConnectForever = true
        connectUseCase.onConnect = { connectUseCase.emitOnboardRequired() }

        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.ONBOARD_REQUIRED, viewModel.uiState.value.outcome)
        assertEquals(1.0f, viewModel.uiState.value.progress)
        assertTrue(
            "no Failed/Cancelled event should fire when onboarding is required",
            events.isEmpty()
        )
        collectJob.cancel()
    }

    @Test
    fun `connect() resolving before any onboard event is unaffected by the race`() = runTest(testDispatcher) {
        // No onboard event is ever emitted — connect() must still win and
        // emit ConnectSucceeded exactly like the success path above.
        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.CONNECTED, viewModel.uiState.value.outcome)
        assertEquals(listOf<LoadingEvent>(LoadingEvent.ConnectSucceeded), events.toList())
        collectJob.cancel()
    }

    @Test
    fun `a long wait is counted on screen instead of freezing`() = runTest(testDispatcher) {
        // The bar pins at 0.85 for as long as connect() blocks — up to ~60s
        // against an unreachable relay — and a bar that stops moving is
        // indistinguishable from a hang. The count is the sign of life, and it
        // is true by construction: it reports the wait, never progress.
        connectUseCase.suspendConnectForever = true
        connectUseCase.onConnect = {
            connectUseCase._connectionState.value = ConnectionState.Connecting
        }

        viewModel.start()
        // Virtual clock only; the 500ms past the tick avoids depending on
        // whether advanceTimeBy runs the task landing exactly on the boundary.
        testDispatcher.scheduler.advanceTimeBy(12_500)

        assertEquals(12, viewModel.uiState.value.waitingSeconds)
        assertEquals(LoadingOutcome.IN_PROGRESS, viewModel.uiState.value.outcome)
        assertEquals("the bar itself still reports only what it knows", 0.85f, viewModel.uiState.value.progress)

        // connect() never resolves here, so the ticker is only stopped by the
        // escape hatch a real user has — leaving it running would hand runTest
        // an endlessly rescheduling task to advance through.
        viewModel.cancel()
    }

    @Test
    fun `a wait too short to be worth naming is not named`() = runTest(testDispatcher) {
        // Below the threshold a counter is noise, and it must never appear
        // inside the MIN_SPLASH_MS / SUCCESS_HOLD_MS windows at either end.
        connectUseCase.suspendConnectForever = true

        viewModel.start()
        testDispatcher.scheduler.advanceTimeBy(3_500)

        assertNull(viewModel.uiState.value.waitingSeconds)
        viewModel.cancel()
    }

    @Test
    fun `a connect that lands promptly never names a wait`() = runTest(testDispatcher) {
        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.CONNECTED, viewModel.uiState.value.outcome)
        assertNull(viewModel.uiState.value.waitingSeconds)
    }

    @Test
    fun `the path to CONNECTED spends no virtual time on blind sleeps`() = runTest(testDispatcher) {
        // 800ms of delay() used to sit between these steps, inherited from the
        // scaffold where connect() was a delay(2000) stub. It gated nothing —
        // the config read and validation are sub-millisecond — and only held
        // two placeholder status strings on screen. Progress is driven by real
        // milestones now; MIN_SPLASH_MS (ConnectOnionApp) and SUCCESS_HOLD_MS
        // (LoadingScreen) own the "don't flash" job at the two ends.
        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.CONNECTED, viewModel.uiState.value.outcome)
        assertEquals(
            "a delay() anywhere on the connect path would show up as virtual time here",
            0L,
            testDispatcher.scheduler.currentTime
        )
    }

    @Test
    fun `start uses agentAddress over serverUrl when present`() = runTest(testDispatcher) {
        viewModel.start()
        advanceUntilIdle()

        assertEquals("0xabc123", connectUseCase.lastAddress)
    }

    @Test
    fun `start reports the real error message from connectionState on failure`() = runTest(testDispatcher) {
        connectUseCase.connectResult = false
        connectUseCase.onConnect = {
            connectUseCase._connectionState.value = ConnectionState.Error(message = "Agent not connected")
        }
        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        assertEquals(LoadingOutcome.IN_PROGRESS, viewModel.uiState.value.outcome)
        assertEquals(1, events.size)
        val failed = events.single() as LoadingEvent.Failed
        assertEquals("Agent not connected", failed.message)
        collectJob.cancel()
    }

    @Test
    fun `start falls back to generic message when no error state is available`() = runTest(testDispatcher) {
        connectUseCase.connectResult = false

        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        val failed = events.single() as LoadingEvent.Failed
        assertEquals("Connection failed", failed.message)
        collectJob.cancel()
    }

    @Test
    fun `start reports failure when no configuration is saved`() = runTest(testDispatcher) {
        configRepository.config = null
        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.start()
        advanceUntilIdle()

        val failed = events.single() as LoadingEvent.Failed
        assertEquals("No configuration found", failed.message)
        assertEquals(0, connectUseCase.connectInvocations)
        collectJob.cancel()
    }

    @Test
    fun `cancel before any outcome emits Cancelled and stops further reporting`() = runTest(testDispatcher) {
        val events = mutableListOf<LoadingEvent>()
        val collectJob = launch { viewModel.events.toList(events) }

        viewModel.cancel()
        // A later failure must be a no-op — first terminal outcome wins.
        connectUseCase.connectResult = false
        viewModel.start()
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertTrue(events.single() is LoadingEvent.Cancelled)
        assertEquals(1, connectUseCase.disconnectCount)
        collectJob.cancel()
    }

    @Test
    fun `start is idempotent and does not double-invoke connect`() = runTest(testDispatcher) {
        viewModel.start()
        viewModel.start()
        advanceUntilIdle()

        assertEquals(1, connectUseCase.connectInvocations)
    }

    @Test
    fun `onCleared leaves the shared connection alive for ChatScreen`() {
        // Regression guard for the duplicate-session bug: connectUseCase is
        // app-scoped, and ChatScreen reuses the very socket this probe opened
        // the moment LoadingScreen navigates away. Tearing it down here is
        // what forced ChatViewModel to open a second one.
        viewModel.onClearedForTest()

        assertEquals(0, connectUseCase.disconnectCount)
    }
}

/**
 * Exposes the protected onCleared() for direct testing without a real
 * ViewModelStore teardown. Resolved on the base class, not on LoadingViewModel:
 * the override went away once the connection became app-scoped, and virtual
 * dispatch still reaches any future override.
 */
private fun LoadingViewModel.onClearedForTest() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}
