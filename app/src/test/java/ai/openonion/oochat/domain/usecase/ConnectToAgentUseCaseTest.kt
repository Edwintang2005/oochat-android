package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.repository.ConnectionRepository
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ExecRequest
import ai.openonion.oochat.network.AgentDiscoveryService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConnectToAgentUseCase]'s 2-attempt stale-session-retry
 * and timeout state machine — the core connection-reliability logic of the
 * app, previously only exercised indirectly via [StaleSessionDetector]
 * (a tiny helper, not the use case itself). Enabled by the small factory-
 * injection seam added alongside this test (`repositoryFactory` param),
 * since [ConnectToAgentUseCase] otherwise always constructs a real
 * `ConnectionRepositoryImpl`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectToAgentUseCaseTest {

    /** Hex address so [AgentDiscoveryService.resolveAgentAddress] resolves synchronously, no HTTP. */
    private val validAddress = "0x1234567890123456789012345678901234567890"

    private class FakeConnectionRepository : ConnectionRepository {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = state
        override var hasConnectedThisAttempt = false
        override val agentProfile: StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> =
            MutableStateFlow(null)
        override val dashboardHtml: StateFlow<String?> = MutableStateFlow(null)

        var connectCount = 0
        val connectedAddresses = mutableListOf<String>()
        /** One behavior per successive connect() call, applied after setting state to Connecting. Last entry repeats if calls exceed the list. */
        val connectBehaviors = mutableListOf<() -> Unit>()

        val switchedConversations = mutableListOf<String?>()
        override suspend fun switchConversation(conversationId: String?) { switchedConversations += conversationId }
        override fun liveSessionIdFor(agentAddress: String): String? = null

        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?) {
            connectCount++
            connectedAddresses += agentAddress
            hasConnectedThisAttempt = false
            state.value = ConnectionState.Connecting
            val behavior = connectBehaviors.getOrNull(connectCount - 1) ?: connectBehaviors.lastOrNull()
            behavior?.invoke()
        }

        // A hot flow that never completes on its own, matching the real
        // ConnectionRepositoryImpl.observeEvents() (backed by a
        // MutableSharedFlow). An emptyFlow() here would complete
        // immediately, making tryConnectOnce's onboard-event race's
        // .first() throw NoSuchElementException instead of suspending.
        val events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
        override fun observeEvents(): Flow<ChatEvent> = events
        override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>?) {}
        var lastExecRequest: ExecRequest? = null
        var execOutcome: ExecOutcome = ExecOutcome.Success("completed")
        override suspend fun executeTool(request: ExecRequest): ExecOutcome {
            lastExecRequest = request
            return execOutcome
        }
        override suspend fun respond(answer: String) {}
        override suspend fun interrupt() {}
        override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {}
        override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {}
        override suspend fun respondToPlanReview(message: String) {}
        override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {}
        val approvalModeState = MutableStateFlow(ai.openonion.oochat.domain.model.ApprovalMode.DEFAULT)
        override val approvalMode: StateFlow<ai.openonion.oochat.domain.model.ApprovalMode> = approvalModeState
        val modePendingState = MutableStateFlow(false)
        override val modePending: StateFlow<Boolean> = modePendingState
        val setModeCalls = mutableListOf<Pair<ai.openonion.oochat.domain.model.ApprovalMode, Int?>>()
        override suspend fun setMode(mode: ai.openonion.oochat.domain.model.ApprovalMode, turns: Int?) {
            setModeCalls += mode to turns
            approvalModeState.value = mode
        }

        var disconnectCount = 0
            override fun retryNow() {}

        var querySessionStatusCalls = mutableListOf<String>()
        override suspend fun querySessionStatus(sessionId: String) {
            querySessionStatusCalls += sessionId
        }

override suspend fun disconnect() {
            disconnectCount++
            state.value = ConnectionState.Disconnected
        }

        override fun isConnected(): Boolean = state.value is ConnectionState.Connected

        var resetCount = 0
        override suspend fun reset() {
            resetCount++
        }

        var currentSessionResult: SessionState? = null
        override fun getCurrentSession(): SessionState? = currentSessionResult

        var peekPersistedSessionIdResult: String? = null
        override suspend fun peekPersistedSessionId(conversationId: String): String? = peekPersistedSessionIdResult
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakeConnectionRepository

    private fun newUseCase(connectionTimeoutMs: Long = 30_000L): ConnectToAgentUseCase {
        fakeRepository = FakeConnectionRepository()
        return ConnectToAgentUseCase(
            keyManager = KeyManager(android.app.Application()),
            connectionTimeoutMs = connectionTimeoutMs,
            agentDiscovery = AgentDiscoveryService(),
            repositoryFactory = { fakeRepository }
        )
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── Single-attempt success ───────────────────────────────────────

    @Test
    fun `connect succeeds on the first attempt when the repository connects immediately`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        val connected = useCase.connect(validAddress)

        assertTrue(connected)
        assertEquals(1, fakeRepository.connectCount)
        assertEquals(0, fakeRepository.resetCount)
    }

    @Test
    fun `a connection that drops right after connecting is not retried as a failed attempt`() = runTest(testDispatcher) {
        // W16AAPPLE-94: CONNECTED, then a normal close before the use case
        // next looks at the state. The attempt worked; a second one would
        // open a connection the user never asked for, without last_msg_id.
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.hasConnectedThisAttempt = true
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
            fakeRepository.state.value = ConnectionState.Disconnected
        }

        val connected = useCase.connect(validAddress)

        assertTrue(connected)
        assertEquals("no second attempt", 1, fakeRepository.connectCount)
        assertEquals(0, fakeRepository.resetCount)
    }

    @Test
    fun `connect resolves a plain hex address without discovery HTTP and forwards it verbatim`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        useCase.connect(validAddress)

        assertEquals(validAddress, fakeRepository.connectedAddresses.single())
    }

    // ── Stale-session retry ───────────────────────────────────────────

    @Test
    fun `connect fast-fails a stale-session error on attempt 1 then retries and succeeds on attempt 2`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value =
                ConnectionState.Error(message = "Session is already attached to another connection")
        }
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        val connected = useCase.connect(validAddress)

        assertTrue(connected)
        assertEquals(2, fakeRepository.connectCount)
        assertEquals(1, fakeRepository.resetCount)
        // The error ends attempt 1 at once; it does not sit out the 30 s timeout.
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime < 1_000L)
    }

    // ── Definitive verdict vs. transient failure ──────────────────────

    @Test
    fun `an offline verdict from the relay does not spend a second attempt`() = runTest(testDispatcher) {
        // The message the repository actually publishes — ServerErrorText has
        // already replaced the relay's raw "Agent not connected: 0x…".
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Error(message = ServerErrorText.AGENT_OFFLINE)
        }

        val connected = useCase.connect(validAddress)

        assertFalse(connected)
        assertEquals("the relay already answered; re-asking buys nothing", 1, fakeRepository.connectCount)
        assertEquals("and the session must not be thrown away over it", 0, fakeRepository.resetCount)
    }

    @Test
    fun `a transient failure still gets its second attempt`() = runTest(testDispatcher) {
        // The counterpart to the verdict above: nothing here says the agent is
        // gone, so the retry is still worth its timeout.
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Error(message = "Network unreachable")
        }
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        val connected = useCase.connect(validAddress)

        assertTrue(connected)
        assertEquals(2, fakeRepository.connectCount)
    }

    @Test
    fun `a refused protocol version does not spend a second attempt`() = runTest(testDispatcher) {
        // W16AAPPLE-12: attempt 2 would send the same OIP descriptor, be
        // refused again, and put a second copy of the error in front of the user.
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Error(
                message = ServerErrorText.incompatibleProtocol(hostProtocol = "oip/0.2", clientProtocol = "oip/0.1"),
                retryable = false
            )
        }

        val connected = useCase.connect(validAddress)

        assertFalse(connected)
        assertEquals("one CONNECT, one refusal", 1, fakeRepository.connectCount)
        assertEquals(0, fakeRepository.resetCount)
    }

    // ── Both attempts fail ────────────────────────────────────────────

    @Test
    fun `connect returns false when both attempts end in a non-stale error`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Error(message = "Network unreachable")
        }
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Error(message = "Network unreachable")
        }

        val connected = useCase.connect(validAddress)

        assertFalse(connected)
        assertEquals(2, fakeRepository.connectCount)
        assertEquals(1, fakeRepository.resetCount)
    }

    // ── Onboard-required short-circuit ─────────────────────────────────

    @Test
    fun `connect returns true without disconnecting when the agent sends OnboardRequired instead of Connected`() = runTest(testDispatcher) {
        // Regression test: an agent that only ever gates on ONBOARD_REQUIRED
        // (never sends CONNECTED) previously left connectionState stuck on
        // Connecting until the full timeout, which called repo.disconnect()
        // and — via the outer connect()'s stale-session fallback — did it
        // again on a second attempt. That tore down a perfectly healthy
        // onboarding-gated connection, surfacing as a spurious "reconnect"
        // right after landing on Chat.
        val useCase = newUseCase(connectionTimeoutMs = 200L)
        fakeRepository.connectBehaviors.add {
            fakeRepository.events.tryEmit(
                ChatEvent.ChatItemReceived(
                    ChatItem.OnboardRequired(id = "onboard-1", methods = listOf("invite_code"))
                )
            )
        }

        val connected = useCase.connect(validAddress)

        assertTrue(connected)
        assertEquals(1, fakeRepository.connectCount)
        assertEquals(0, fakeRepository.disconnectCount)
    }

    // ── Timeout ───────────────────────────────────────────────────────

    @Test
    fun `connect times out and returns false when the repository never leaves Connecting`() = runTest(testDispatcher) {
        // No connectBehaviors registered — state stays Connecting forever.
        // A short timeout keeps this fast under the test's virtual clock.
        val useCase = newUseCase(connectionTimeoutMs = 200L)

        val connected = useCase.connect(validAddress)

        assertFalse(connected)
        // Both attempts time out identically since nothing ever resolves the state.
        assertEquals(2, fakeRepository.connectCount)
    }

    @Test
    fun `connect calls disconnect after a timeout`() = runTest(testDispatcher) {
        val useCase = newUseCase(connectionTimeoutMs = 200L)

        useCase.connect(validAddress)

        assertTrue(fakeRepository.disconnectCount >= 1)
    }

    // ── Forwarders / lifecycle ────────────────────────────────────────

    @Test
    fun `isConnected reflects the repository connection state after a successful connect`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        useCase.connect(validAddress)

        assertTrue(useCase.isConnected())
    }

    @Test
    fun `isConnected is false before any connect call`() {
        val useCase = newUseCase()

        assertFalse(useCase.isConnected())
    }

    @Test
    fun `disconnect forwards to the repository only when one was created`() = runTest(testDispatcher) {
        val useCase = newUseCase()

        // No connect() call yet — repository was never created via
        // getOrCreateRepository(), so disconnect() must be a no-op, not a
        // NullPointerException or an accidental fresh-repository construction.
        useCase.disconnect()

        assertEquals(0, fakeRepository.disconnectCount)
    }


    @Test
    fun `getOrCreateRepository builds the repository only once across multiple calls`() = runTest(testDispatcher) {
        val useCase = newUseCase()
        fakeRepository.connectBehaviors.add {
            fakeRepository.state.value = ConnectionState.Connected(address = validAddress)
        }

        useCase.connect(validAddress)
        useCase.sendMessage("hi", validAddress)
        useCase.isConnected()

        // All calls above route through getOrCreateRepository(); the
        // factory itself is a lambda returning the same fakeRepository
        // instance regardless of call count, so this indirectly confirms
        // the use case doesn't discard and recreate on every access.
        assertEquals(1, fakeRepository.connectCount)
    }

    @Test
    fun `executeTool forwards request and returns repository outcome`() =
        runTest(testDispatcher) {
            val useCase = newUseCase()
            val request = ExecRequest(
                execId = "exec-1",
                tool = "shell",
                argsJson = """{"command":"pwd"}""",
                agentAddress = validAddress
            )
            val expected = ExecOutcome.Error(
                message = "Command failed",
                durationMs = 15.0
            )
            fakeRepository.execOutcome = expected

            val actual = useCase.executeTool(request)

            assertEquals(request, fakeRepository.lastExecRequest)
            assertEquals(expected, actual)
        }
}
