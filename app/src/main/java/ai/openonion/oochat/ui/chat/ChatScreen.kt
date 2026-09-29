package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.ui.chat.components.ChatTopBar
import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.chat.components.LowBalanceBar
import ai.openonion.oochat.ui.chat.components.VoiceInputState
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.components.ConnectionBanner
import ai.openonion.oochat.ui.navigation.DrawerAgentSection
import ai.openonion.oochat.ui.navigation.NavDrawer
import ai.openonion.oochat.ui.navigation.NavDrawerWidth
import ai.openonion.oochat.ui.theme.LocalThemeController
import ai.openonion.oochat.util.SoundEffectPlayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Compose-native chat screen used by both [Routes.CHAT] (auto-connect from
 * saved config) and [Routes.CHAT_WITH_AGENT] (tap an agent in
 * AgentListScreen) so the two entry points share the same look, layout, and
 * card types (OnboardGateCard, Thinking bubble, Approval card, etc.).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    connectToAgentId: String = "",
    onboardPending: Boolean = false,
    // Set when arriving via a different agent's session row in the drawer;
    // once this ChatViewModel connects, switch straight to this persisted
    // session instead of the default ensureActiveSession() pick.
    sessionIdToRestore: String? = null,
    // True only for an explicit "start a new conversation" action — the
    // drawer's "+" on a *different* agent. Everything else, cold start
    // included, resumes the previous conversation via ensureActiveSession.
    startNewSessionOnConnect: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToAgentList: () -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    // Drawer row tapped for an agent other than the one this ChatViewModel
    // is connected to — the caller (NavigationGraph) re-navigates to that
    // agent's own chat/{address} destination, tearing down this ChatViewModel
    // and creating a fresh one the normal way (see NavigationGraph.kt).
    // startNew mirrors startNewSessionOnConnect above, threaded through
    // navigation for the fresh ChatViewModel on the other side.
    onNavigateToAgentChat: (agentAddress: String, sessionId: String?, startNew: Boolean) -> Unit = { _, _, _ -> },
    viewModel: ChatViewModel = appViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val hasLiveConnection by viewModel.hasLiveConnection.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAgentWorking by viewModel.isAgentWorking.collectAsState()
    val voiceInput by viewModel.voiceInput.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val awaitingOnboardCode by viewModel.awaitingOnboardCode.collectAsState()
    val agentProfile by viewModel.agentProfile.collectAsState()
    val approvalMode by viewModel.approvalMode.collectAsState()
    val modePending by viewModel.modePending.collectAsState()

    val dashboardHtml by viewModel.dashboardHtml.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val itemTimestamps by viewModel.itemTimestamps.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val targetAgentAddress by viewModel.targetAgentAddress.collectAsState()
    val drawerAgents by viewModel.drawerAgents.collectAsState()
    // Title, cleared-state, usage totals and the snackbar's error are derived
    // in ChatViewModel, not here: computing any of them in this body reads
    // uiState at function scope, which is what made a single tool_call frame
    // re-execute the whole screen. See their declarations for the detail.
    val conversationTitle by viewModel.conversationTitle.collectAsState()
    val wasCleared by viewModel.wasCleared.collectAsState()
    val sessionUsage by viewModel.sessionUsage.collectAsState()
    val error by viewModel.error.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // ModalNavigationDrawer composes its drawerContent whether or not the
    // drawer is open, so the ~800-line panel — every agent's session rows (a
    // plain Column, not a LazyColumn, so all of them are realized), the theme
    // switcher, eight Icons.Filled.* vector builders — was composed, measured
    // and laid out as part of the first chat frame, before the user could
    // possibly have opened it. Latched, not toggled: closing it again should
    // not throw away work the user has already paid for.
    var drawerEverOpened by rememberSaveable { mutableStateOf(false) }
    // The menu button sets this itself so the content is in place for the very
    // first slide-in; this covers any other route to open (a drag, a restored
    // open state) without the body having to observe drawerState.
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen || drawerState.isAnimationRunning }.first { it }
        drawerEverOpened = true
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val renderMarkdown by viewModel.renderMarkdown.collectAsState()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()
    val soundEffectsEnabled by viewModel.soundEffectsEnabled.collectAsState()
    val haptics = LocalHapticFeedback.current

    // The user's font-size preference is applied app-wide by ConnectOnionTheme
    // (LocalDensity override there) — this screen no longer scales its own.

    // Fires only for genuinely-new agent replies (see ChatViewModel.
    // agentReplyArrived's own doc) — never on session_sync replay of
    // already-persisted history.
    LaunchedEffect(viewModel) {
        viewModel.agentReplyArrived.collect {
            if (hapticFeedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (soundEffectsEnabled) SoundEffectPlayer.playReceived()
        }
    }

    // Single decision point for which agent to connect to (route target, or
    // saved-config default). Reused by Reconnect so it replays the same
    // decision rather than falling back to the default agent when
    // connectToAgentId is set — see connectToAgentByConfig's doc for the
    // race this avoids.
    val connectToCurrentTarget: () -> Unit = {
        if (connectToAgentId.isNotBlank()) {
            viewModel.connectToAgent(connectToAgentId)
        } else {
            viewModel.connectToAgentByConfig()
        }
    }
    // Applied synchronously here (not reactively off isConnected) so it
    // fires exactly once and strictly before the Connected event it affects —
    // see markStartNewSessionOnConnect's doc.
    LaunchedEffect(connectToAgentId) {
        if (startNewSessionOnConnect) {
            viewModel.markStartNewSessionOnConnect()
        }
        connectToCurrentTarget()
    }

    // LoadingScreen already determined this agent needs onboarding — skip
    // re-announcing a generic "Connecting to agent…" banner while this
    // screen's own (necessary) fresh connect resolves the same outcome.
    LaunchedEffect(onboardPending) {
        if (onboardPending) {
            viewModel.markOnboardPending()
        }
    }

    // Keyed on isConnected (not sessionIdToRestore alone) so this only fires
    // once ensureActiveSession() — driven by the same Connected transition,
    // inside ChatViewModel's own collector — has already resolved its
    // default active session; calling switchToSession() after that instead
    // of racing it is what makes this land on the *intended* session.
    LaunchedEffect(sessionIdToRestore, isConnected) {
        if (sessionIdToRestore != null && isConnected) {
            viewModel.switchToSession(sessionIdToRestore)
        }
    }

    val accountRefreshLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(accountRefreshLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onReturnedToForeground()
            }
        }
        accountRefreshLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { accountRefreshLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Monitor error state and show a snackbar when one occurs. Long/
    // multi-line server-reported errors (e.g. "Insufficient ConnectOnion
    // Credits") no longer reach uiState.error at all — ChatViewModel routes
    // those into the chat list itself (attached to the in-flight Thinking
    // bubble, or a standalone item) so they read as part of the
    // conversation instead of a transient toast. What's left here is
    // genuinely short, transient blips ("Connection lost", "N images
    // failed to attach"), which a Snackbar fits fine.
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.clearError()
        }
    }

    // Approximates Figma's `backdrop-filter: blur(20px)` on the drawer panel:
    // blurring the content *behind* it reads identically to blurring the
    // panel itself (the drawer covers this content while open). Modifier.blur
    // uses RenderEffect, which is API 31+ only — a graceful no-op (plain
    // translucency, same as before) on older devices, not a crash.
    // Focus follows the keyboard down. The back key already hides the IME —
    // measured on the Huawei/Sogou pair, the input method consumes the key
    // before the app window sees it, so no BackHandler could run there — but
    // the field it was serving stayed focused, leaving a live caret and the
    // pill's focused ring under a keyboard that is gone. Keyed on the inset,
    // not on back, so a hide gesture or the IME's own dismiss key counts too.
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }

    val contentBlur by animateDpAsState(
        targetValue = if (drawerState.isOpen) 20.dp else 0.dp,
        animationSpec = tween(200),
        label = "drawerContentBlur"
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            if (drawerEverOpened) {
                ChatNavDrawerContent(
                    agentSections = drawerAgents,
                    activeSessionId = activeSessionId,
                    targetAgentAddress = targetAgentAddress,
                    onCloseDrawer = { drawerScope.launch { drawerState.close() } },
                    onStartNewSession = viewModel::startNewSession,
                    onSwitchToSession = viewModel::switchToSession,
                    onDeleteSession = viewModel::deleteSession,
                    onRenameSession = viewModel::renameSession,
                    onNavigateToAgentList = onNavigateToAgentList,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToAgentChat = onNavigateToAgentChat
                )
            } else {
                // Same footprint, none of the content: the drawer's open anchor
                // is derived from the sheet's measured width, so an empty
                // drawerContent would leave nothing to slide.
                Spacer(Modifier.fillMaxHeight().width(NavDrawerWidth))
            }
        }
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Edge-to-edge: the background above still paints the full
                // window (including behind the transparent bars), but the
                // content is inset here. Bottom takes safeDrawing, whose ime
                // component is what replaces the window resize that
                // adjustResize no longer performs once the decor stops
                // fitting system windows — it shrinks the weighted
                // MessageList and lifts InputBar instead of pushing ChatTopBar
                // off-screen. Top is deliberately left alone: ChatTopBar's
                // TopAppBar applies its own status-bar inset, so its surface
                // keeps painting behind the bar.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(contentBlur)
            ) {
            ChatTopBar(
                title = conversationTitle,
                connectionState = connectionState,
                isConnected = isConnected,
                onClearChat = { viewModel.clearChat() },
                onNavigateToLogs = onNavigateToLogs,
                onMenuClick = {
                    drawerEverOpened = true
                    drawerScope.launch { drawerState.open() }
                },
                awaitingOnboardCode = awaitingOnboardCode,
                modelName = agentProfile?.model,
                hasDashboard = dashboardHtml != null,
                onOpenDashboard = onNavigateToDashboard
            )

            ConnectionBanner(
                connectionState = connectionState,
                onReconnect = connectToCurrentTarget,
                onStop = { viewModel.disconnect() },
                awaitingOnboardCode = awaitingOnboardCode,
                isOffline = isOffline
            )

            MessageList(
                items = uiState.chatItems,
                timestamps = itemTimestamps,
                wasCleared = wasCleared,
                onRespond = viewModel::respond,
                onApprove = viewModel::respondToApproval,
                onOnboard = viewModel::respondToOnboard,
                onPlanReviewResponse = viewModel::respondToPlanReview,
                onUlwResponse = viewModel::respondToUlwTurnsReached,
                onGateResolved = viewModel::markGateResolved,
                onRetry = viewModel::retryFailedTurn,
                onResendMessage = viewModel::resendMessage,
                renderMarkdown = renderMarkdown,
                hasLiveConnection = hasLiveConnection,
                conversationKey = activeSessionId,
                hasOlderMessages = hasOlderMessages,
                onLoadOlderMessages = viewModel::loadOlderMessages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // With the composer, not in the transcript: the transcript scrolls
            // away and this is the one notice that has to still be there when
            // the reader looks back down to type.
            LowBalanceBar(
                balanceUsd = agentProfile?.balanceUsd,
                agentAddress = targetAgentAddress
            )

            ChatInput(
                awaitingOnboardCode = awaitingOnboardCode,
                isConnected = isConnected,
                approvalMode = approvalMode,
                modePending = modePending,
                usage = sessionUsage,
                skills = agentProfile?.skills.orEmpty(),
                onCycleApprovalMode = viewModel::cycleApprovalMode,
                onSelectApprovalMode = viewModel::setApprovalMode,
                isLoading = isLoading,
                isAgentWorking = isAgentWorking,
                onSend = { text, images, files ->
                    if (hapticFeedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (soundEffectsEnabled) SoundEffectPlayer.playSent()
                    viewModel.sendMessage(text, images, files)
                },
                voiceInput = voiceInput,
                onStartVoiceRecording = viewModel::startVoiceRecording,
                onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                onFinishVoiceInput = viewModel::finishVoiceInput,
                onVoiceTranscriptConsumed = viewModel::consumeVoiceTranscript,
                onQueryVoiceAmplitude = viewModel::currentVoiceAmplitude,
                onStop = viewModel::interrupt,
                modifier = Modifier.graphicsLayer { alpha = if (isConnected) 1f else 0.45f }
            )
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ── Navigation drawer ───────────────────────────────────────────

@Composable
private fun ChatNavDrawerContent(
    agentSections: List<DrawerAgentSection>,
    activeSessionId: String?,
    targetAgentAddress: String?,
    onCloseDrawer: () -> Unit,
    onStartNewSession: () -> Unit,
    onSwitchToSession: (sessionId: String) -> Unit,
    onDeleteSession: (sessionId: String) -> Unit,
    onRenameSession: (sessionId: String, newTitle: String) -> Unit,
    onNavigateToAgentList: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAgentChat: (agentAddress: String, sessionId: String?, startNew: Boolean) -> Unit
) {
    val themeController = LocalThemeController.current
    // Passed straight through: ChatViewModel already emits the drawer's own
    // shape, and re-deriving it here would mint a fresh list on every
    // recomposition, which is exactly what stopped NavDrawer from skipping.
    NavDrawer(
        agentSections = agentSections,
        activeEntryId = activeSessionId,
        themeMode = themeController.mode,
        onNewChatForAgent = { agentAddress ->
            onCloseDrawer()
            if (agentAddress == targetAgentAddress) {
                onStartNewSession()
            } else {
                onNavigateToAgentChat(agentAddress, null, true)
            }
        },
        onSelectAgentSession = { agentAddress, sessionId ->
            onCloseDrawer()
            if (agentAddress == targetAgentAddress) {
                onSwitchToSession(sessionId)
            } else {
                onNavigateToAgentChat(agentAddress, sessionId, false)
            }
        },
        onDeleteSession = onDeleteSession,
        onRenameSession = onRenameSession,
        onOpenAgentList = {
            onCloseDrawer()
            onNavigateToAgentList()
        },
        onOpenSettings = {
            onCloseDrawer()
            onNavigateToSettings()
        },
        onThemeModeChange = themeController.setMode,
        connectedAgentAddress = targetAgentAddress
    )
}

// ── Input bar ───────────────────────────────────────────────────

@Composable
private fun ChatInput(
    isConnected: Boolean,
    awaitingOnboardCode: Boolean,
    isLoading: Boolean,
    isAgentWorking: Boolean,
    approvalMode: ApprovalMode,
    modePending: Boolean,
    usage: SessionUsageTotals,
    skills: List<AgentSkill>,
    onCycleApprovalMode: () -> Unit,
    onSelectApprovalMode: (ApprovalMode, Int?) -> Unit,
    onSend: (String, List<String>, List<String>) -> Unit,
    voiceInput: VoiceInputState,
    onStartVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onVoiceTranscriptConsumed: () -> Unit,
    onQueryVoiceAmplitude: () -> Float = { 0f },
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Matched to the transcript's own cap: a narrow column of messages above a
    // composer running the full width of a tablet reads as an oversight. The
    // Box keeps the full width so the bar's background still spans the screen.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        InputBar(
            isConnected = isConnected,
            awaitingOnboardCode = awaitingOnboardCode,
            approvalMode = approvalMode,
            modePending = modePending,
            usage = usage,
            skills = skills,
            onCycleApprovalMode = onCycleApprovalMode,
            onSelectApprovalMode = onSelectApprovalMode,
            isSending = isLoading,
            isAgentWorking = isAgentWorking,
            onSend = onSend,
            voiceInput = voiceInput,
            onStartVoiceRecording = onStartVoiceRecording,
            onCancelVoiceRecording = onCancelVoiceRecording,
            onFinishVoiceInput = onFinishVoiceInput,
            onVoiceTranscriptConsumed = onVoiceTranscriptConsumed,
            onQueryVoiceAmplitude = onQueryVoiceAmplitude,
            onStop = onStop,
            modifier = Modifier.widthIn(max = ChatContentMaxWidth)
        )
    }
}
