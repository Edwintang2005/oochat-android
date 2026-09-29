package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.local.IgnoredIdsManager
import ai.openonion.oochat.data.local.IgnoredIdsStorage
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.NetworkMonitor
import ai.openonion.oochat.ui.perf.BodyExecutionProbe
import ai.openonion.oochat.ui.perf.RecompositionRecorder
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recomposition counts for the real [ChatScreen], driven by a real
 * [ChatViewModel] over in-memory fakes and measured on the JVM under
 * Robolectric — no device, and no counter left behind in production code.
 *
 * Two instruments, both in
 * [ai.openonion.oochat.ui.perf.RecompositionProbes]:
 *  - [BodyExecutionProbe] counts executions of `ChatScreen`'s own body via the
 *    `DisposableEffect` it keys on `LocalLifecycleOwner.current`.
 *  - [RecompositionRecorder] counts every recompose scope the runtime actually
 *    executes, so a skipped subtree costs nothing and a rebuilt drawer is
 *    unmissable.
 *
 * Each zero-assertion is paired with a change that *must* move the same
 * counter, so a probe that quietly stopped working fails the test instead of
 * passing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenRecompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val AGENT_ADDRESS = "0x1a2b3c4d5e6f7a8b"
        const val AGENT_NAME = "Recomposition Agent"
        /** Frames in a simulated agent turn: llm_call, tool_call, tool_result… */
        const val TURN_FRAMES = 12
        const val OTHER_AGENTS = 4
        const val SESSIONS_PER_AGENT = 6
    }

    private lateinit var connectUseCase: RecompFakeConnectUseCase
    private lateinit var agentRepo: RecompFakeAgentRepository
    private lateinit var sessionRepo: RecompFakeSessionRepository
    private lateinit var messageRepo: RecompFakeMessageRepository
    private lateinit var viewModel: ChatViewModel

    private val bodyProbe = BodyExecutionProbe()
    private val recorder = RecompositionRecorder()

    @Before
    fun setUp() {
        connectUseCase = RecompFakeConnectUseCase()
        agentRepo = RecompFakeAgentRepository()
        sessionRepo = RecompFakeSessionRepository()
        messageRepo = RecompFakeMessageRepository()

        agentRepo.seed(
            AgentProfile(
                id = "agent-1",
                name = AGENT_NAME,
                address = AGENT_ADDRESS,
                serverUrl = "wss://relay.test",
                createdAt = 0L,
                isActive = true
            )
        )
        // A drawer worth measuring: the panel is a plain Column, so every one
        // of these rows is realised whenever it composes. A single agent with
        // a single session makes a full rebuild too cheap to notice.
        repeat(OTHER_AGENTS) { a ->
            val id = "agent-other-$a"
            agentRepo.seed(
                AgentProfile(
                    id = id,
                    name = "Other agent $a",
                    address = "0xother$a",
                    serverUrl = "wss://relay.test",
                    createdAt = 0L,
                    isActive = true
                )
            )
            repeat(SESSIONS_PER_AGENT) { s ->
                sessionRepo.seed(agentId = id, id = "$id-session-$s", title = "Conversation $s")
            }
        }

        viewModel = ChatViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectUseCase,
            agentRepository = agentRepo,
            configRepository = RecompFakeConfigRepository(),
            ignoredIdsManager = IgnoredIdsManager(RecompFakeIgnoredIdsStorage()),
            appSettings = RecompFakeAppSettings(),
            messageRepository = messageRepo,
            conversationHistory = ConversationHistoryUseCase(
                agentRepo, sessionRepo, messageRepo,
                FakePersistenceTransaction(messageRepo, sessionRepo)
            ),
            initialMyAddress = "0xTEST",
            networkMonitor = RecompFakeNetworkMonitor(),
        )
    }

    // ── Harness ────────────────────────────────────────────────────────

    private fun setChatScreen() {
        var hostView: android.view.View? = null
        composeRule.setContent {
            hostView = LocalView.current
            // Providing the lifecycle owner is what arms BodyExecutionProbe:
            // ChatScreen keys its foreground-refresh DisposableEffect on it.
            CompositionLocalProvider(LocalLifecycleOwner provides bodyProbe) {
                ConnectOnionTheme {
                    ChatScreen(
                        connectToAgentId = AGENT_ADDRESS,
                        viewModel = viewModel
                    )
                }
            }
        }
        composeRule.waitForIdle()
        connectUseCase.markConnected(AGENT_ADDRESS)
        composeRule.waitForIdle()
        recorder.attach(requireNotNull(hostView))
    }

    @After
    fun tearDown() {
        recorder.detach()
    }

    /**
     * One agent turn's worth of frames. Half are tool frames (uiState churn
     * only), half are agent replies, which persist — and persisting is what
     * rewrites the session row's updatedAt/messageCount/lastMessagePreview,
     * the emission that used to rebuild the drawer.
     */
    private fun driveAgentTurn(frames: Int = TURN_FRAMES, tag: String = "a") {
        repeat(frames) { i ->
            composeRule.runOnIdle {
                val item = if (i % 2 == 0) {
                    ChatItem.ToolCall(
                        id = "tool-$tag-$i",
                        name = "read_file",
                        status = ToolStatus.DONE,
                        result = "line $i"
                    )
                } else {
                    ChatItem.Agent(id = "reply-$tag-$i", content = "reply $i")
                }
                connectUseCase.emit(ChatEvent.ChatItemReceived(item))
            }
            composeRule.waitForIdle()
        }
    }

    // ── Fix 2: the screen body is out of uiState's invalidation set ────

    /**
     * The body is where the conversation title, `wasCleared`, the usage
     * totals and the snackbar's error used to be derived. Counting the body
     * covers all four at once: any of them moving back here puts `uiState`
     * in the body's invalidation set and this count stops being zero.
     */
    @Test
    fun `an agent turn does not re-execute the chat screen body`() {
        setChatScreen()

        bodyProbe.mark()
        driveAgentTurn()
        val duringTurn = bodyProbe.sinceMark()

        // The frames landed: this is not a turn that never happened.
        assertEquals(TURN_FRAMES, viewModel.uiState.value.chatItems.size)
        assertEquals(
            "$TURN_FRAMES turn frames re-executed the ChatScreen body $duringTurn time(s); " +
                "the body must stay out of uiState's invalidation set",
            0,
            duringTurn
        )

        // Liveness: something that genuinely belongs to the body still moves
        // it, so the zero above cannot be a probe that stopped counting.
        bodyProbe.mark()
        composeRule.runOnIdle { connectUseCase.forceState(ConnectionState.Disconnected) }
        composeRule.waitForIdle()
        assertTrue(
            "BodyExecutionProbe never counted: the DisposableEffect key it relies on is gone",
            bodyProbe.sinceMark() > 0
        )
    }

    // ── Fix 1: the drawer skips through an agent turn ──────────────────

    @Test
    fun `an agent turn leaves the drawer's model instance untouched`() {
        setChatScreen()
        // Warm one persisted message so the session row exists before the
        // instance is captured; the assertion is about later messages.
        driveAgentTurn(frames = 2, tag = "warm")

        val before = viewModel.drawerAgents.value
        assertEquals(1 + OTHER_AGENTS, before.size)
        assertEquals(1, before.first { it.agentAddress == AGENT_ADDRESS }.sessions.size)

        val sessionRowBefore = viewModel.sessions.value.single()
        driveAgentTurn()

        // The Room-backed row really was rewritten — without this the identity
        // assertion below would hold for a turn that persisted nothing.
        val sessionRowAfter = viewModel.sessions.value.single()
        assertTrue(
            "no message was persisted, so nothing tested the drawer's conflation",
            sessionRowAfter.messageCount > sessionRowBefore.messageCount
        )

        // Identity, not equality: NavDrawer takes an unstable List, which
        // StrongSkipping compares by instance. A merely-equal rebuild would
        // recompose the whole panel.
        assertSame(
            "drawerAgents emitted a new list instance during an agent turn; the closed " +
                "drawer would rebuild on every persisted message",
            before,
            viewModel.drawerAgents.value
        )
    }

    @Test
    fun `opening the drawer does not add it to what an agent turn recomposes`() {
        setChatScreen()

        recorder.reset()
        driveAgentTurn(tag = "closed")
        val closed = recorder.scopeExecutions

        openDrawer()
        composeRule.onNodeWithText(AGENT_NAME).assertExists()

        recorder.reset()
        driveAgentTurn(tag = "open")
        val open = recorder.scopeExecutions

        // Measured here: 1112 scopes with the drawer closed, 1152 with it
        // open — the panel costs ~3 scopes a frame, not a rebuild. Reverting
        // the narrowed drawerAgents model takes the open figure to 2370 on
        // the same fixture, so the bound sits between the two by a wide gap.
        assertTrue(
            "an agent turn executed $closed recompose scopes with the drawer closed and " +
                "$open with it open; the open drawer is rebuilding on persisted messages",
            open < closed + 400
        )
        // A recorder that observed nothing would satisfy the bound above.
        assertTrue("RecompositionRecorder counted nothing at all", closed > 0 && open > 0)
    }

    // ── Fix 3: the drawer is built on first open, not on the first frame ──

    @Test
    fun `the drawer panel does not compose until it is first opened`() {
        setChatScreen()

        composeRule.onNodeWithText(AGENT_NAME).assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()

        openDrawer()

        composeRule.onNodeWithText(AGENT_NAME).assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
    }

    private fun openDrawer() {
        composeRule.onNodeWithContentDescription("Open menu").performClick()
        composeRule.waitForIdle()
    }
}

// ── Fakes ──────────────────────────────────────────────────────────────
// Deliberately minimal: only what ChatScreen + ChatViewModel touch on the
// connect / persist / drawer path these counts depend on.

internal class RecompFakeConnectUseCase : ConnectToAgentUseCaseContract {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState
    override val agentProfile: StateFlow<AgentLiveProfile?> = MutableStateFlow(null)
    override val approvalMode: StateFlow<ApprovalMode> = MutableStateFlow(ApprovalMode.DEFAULT)
    override val modePending: StateFlow<Boolean> = MutableStateFlow(false)
    override val dashboardHtml: StateFlow<String?> = MutableStateFlow(null)

    private val events = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override fun observeEvents(): Flow<ChatEvent> = events

    private var connected = false
    private var liveSessionId: String? = null

    override fun isConnected(): Boolean = connected
    override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?): Boolean {
        liveSessionId = conversationId
        return true
    }
    override fun liveSessionIdFor(agentAddress: String): String? = liveSessionId
    override suspend fun switchConversation(conversationId: String?) { liveSessionId = conversationId }
    override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<OutgoingFileAttachment>?) = Unit
    override suspend fun respond(answer: String) = Unit
    override suspend fun interrupt() = Unit
    override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) = Unit
    override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) = Unit
    override suspend fun respondToPlanReview(message: String) = Unit
    override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) = Unit
    override suspend fun setMode(mode: ApprovalMode, turns: Int?) = Unit
    override fun retryNow() = Unit
    override suspend fun querySessionStatus() = Unit
    override suspend fun disconnect() { connected = false }
    override suspend fun reset() = Unit

    fun emit(event: ChatEvent) { events.tryEmit(event) }
    fun markConnected(address: String) {
        connected = true
        _connectionState.value = ConnectionState.Connected(address = address)
    }
    fun forceState(state: ConnectionState) { _connectionState.value = state }
}

internal class RecompFakeAgentRepository : AgentRepository {
    private val agents = MutableStateFlow<List<AgentProfile>>(emptyList())
    override fun getAllAgents(): Flow<List<AgentProfile>> = agents
    override fun getActiveAgents(): Flow<List<AgentProfile>> = agents.map { list -> list.filter { it.isActive } }
    override suspend fun getAgentById(id: String) = agents.value.find { it.id == id }
    override suspend fun getAgentByAddress(address: String) = agents.value.find { it.address == address }
    override suspend fun createAgent(agent: AgentProfile): AgentProfile {
        agents.value = agents.value + agent
        return agent
    }
    override suspend fun updateAgent(agent: AgentProfile) {
        agents.value = agents.value.map { if (it.id == agent.id) agent else it }
    }
    override suspend fun deleteAgent(agentId: String) { agents.value = agents.value.filterNot { it.id == agentId } }
    override suspend fun updateLastConnected(agentId: String) = Unit
    override suspend fun getDefaultAgent() = agents.value.firstOrNull { it.isActive }
    override suspend fun reorderAgents(orderedIds: List<String>) = Unit
    fun seed(agent: AgentProfile) { agents.value = agents.value + agent }
}

internal class RecompFakeSessionRepository : SessionRepository {
    private val all = MutableStateFlow<List<ChatSession>>(emptyList())
    override fun getAllSessions(): Flow<List<ChatSession>> = all
    override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> =
        all.map { list -> list.filter { it.agentId == agentId }.sortedByDescending { it.updatedAt } }
    override suspend fun getSessionById(id: String) = all.value.find { it.id == id }
    override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
        val session = ChatSession(id = id, agentId = agentId, title = title, createdAt = 0L, updatedAt = 0L)
        all.value = all.value + session
        return session
    }
    override suspend fun deleteSession(sessionId: String) { all.value = all.value.filterNot { it.id == sessionId } }
    /** Pre-existing conversation, as if saved by an earlier run of the app. */
    fun seed(agentId: String, id: String, title: String) {
        all.value = all.value + ChatSession(id = id, agentId = agentId, title = title, createdAt = 0L, updatedAt = 0L)
    }
    override suspend fun renameSession(sessionId: String, newTitle: String) {
        all.value = all.value.map { if (it.id == sessionId) it.copy(title = newTitle) else it }
    }
    // The row rewrite behind fix 1: every persisted message changes fields the
    // drawer does not draw, so a drawer model that carried them re-emitted.
    override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
        all.value = all.value.map {
            if (it.id == sessionId) it.copy(messageCount = count, lastMessagePreview = preview, updatedAt = it.updatedAt + 1)
            else it
        }
    }
}

internal class RecompFakeMessageRepository : MessageRepository {
    private val all = MutableStateFlow<List<ChatMessage>>(emptyList())
    override suspend fun getMessagesListBySession(sessionId: String) =
        all.value.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
    override suspend fun createMessage(message: ChatMessage) {
        all.value = all.value.filterNot { it.id == message.id } + message
    }
    override suspend fun deleteMessagesBySession(sessionId: String) {
        all.value = all.value.filterNot { it.sessionId == sessionId }
    }
    override suspend fun getMessageCount(sessionId: String) = all.value.count { it.sessionId == sessionId }
    override suspend fun existsById(id: String) = all.value.any { it.id == id }
    override suspend fun getOwningSessionId(id: String) = all.value.firstOrNull { it.id == id }?.sessionId
    override suspend fun getSessionIdByUserContent(content: String) =
        all.value.lastOrNull { it.role == Role.USER && it.content == content }?.sessionId
}

internal class RecompFakeConfigRepository : ConnectionConfigRepository {
    private var config: ConnectionConfig? = null
    override suspend fun getConfig() = config
    override fun observeConfig() = MutableStateFlow(config)
    override suspend fun saveConfig(config: ConnectionConfig) { this.config = config }
    override suspend fun deleteConfig() { config = null }
    override suspend fun hasConfig() = config != null
    override suspend fun updateLastConnected(timestamp: Long) = Unit
    override suspend fun updateAgentAddress(address: String?) = Unit
}

internal class RecompFakeIgnoredIdsStorage : IgnoredIdsStorage {
    private val backing = mutableMapOf<String, Set<String>>()
    override suspend fun loadAll(): Map<String, Set<String>> = backing
    override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) { backing[agentAddress] = ids }
}

internal class RecompFakeNetworkMonitor : NetworkMonitor {
    override val isOnline: Flow<Boolean> = MutableStateFlow(true)
}

internal class RecompFakeAppSettings : AppSettings {
    private val renderMarkdownFlow = MutableStateFlow(true)
    private val fontSizeIndexFlow = MutableStateFlow(1)
    private val hapticFeedbackFlow = MutableStateFlow(false)
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
