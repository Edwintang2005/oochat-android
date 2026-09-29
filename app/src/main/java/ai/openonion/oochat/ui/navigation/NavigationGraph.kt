package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.ui.agent.AgentDiscoveryScreenWrapper
import ai.openonion.oochat.ui.agent.AgentEditScreenWrapper
import ai.openonion.oochat.ui.agent.AgentListScreenWrapper
import ai.openonion.oochat.ui.chat.ChatScreen
import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.DangerConfirmActions
import ai.openonion.oochat.ui.dashboard.DashboardScreenWrapper
import ai.openonion.oochat.ui.loading.LoadingScreen
import ai.openonion.oochat.ui.logs.LogsScreen
import ai.openonion.oochat.ui.onboarding.OnboardingScreen
import ai.openonion.oochat.ui.recovery.RecoveryScreen
import ai.openonion.oochat.ui.settings.SettingsScreen
import ai.openonion.oochat.ui.theme.Motion
import ai.openonion.oochat.util.truncateMiddle
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink

/**
 * Navigation routes for the application.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LOADING = "loading"
    const val CHAT = "chat"
    const val CHAT_WITH_AGENT = "chat/{agentId}"
    const val RECOVERY = "recovery"
    const val AGENT_LIST = "agent_list"
    const val AGENT_EDIT = "agent_edit"
    const val AGENT_DISCOVERY = "agent_discovery"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    const val DASHBOARD = "dashboard"

    /** SavedStateHandle key used to hand a picked agent address back from AGENT_DISCOVERY. */
    const val RESULT_SELECTED_AGENT_ADDRESS = "selected_agent_address"
}

/**
 * Main navigation graph for the application.
 *
 * Handles the flow between:
 * - OnboardingScreen (first launch)
 * - LoadingScreen (auto-reconnect)
 * - ChatScreen (connected)
 * - RecoveryScreen (connection failed)
 * - AgentListScreen (agent management)
 * - AgentEditScreen (agent creation/editing)
 * - AgentDiscoveryScreen (agent discovery)
 */
@Composable
fun NavigationGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier
) {
    // Shared by both chat routes (default + agent-scoped): navigating to a
    // *different* agent's chat pops the current chat entry so its ChatViewModel
    // clears. No longer about connection count — the app-scoped connection is
    // one socket regardless — but a surviving ChatViewModel would still collect
    // the shared event bus and fold another agent's events into its own list.
    val navigateToAgentChat: (String, String?, Boolean) -> Unit = { agentAddress, sessionId, startNew ->
        val params = buildList {
            sessionId?.let { add("sessionId=${Uri.encode(it)}") }
            if (startNew) add("startNew=true")
        }
        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        // currentDestination is momentarily null mid-transition; skip the pop
        // rather than crash — the extra entry is the lesser evil.
        val currentId = navController.currentDestination?.id
        navController.navigate("chat/${Uri.encode(agentAddress)}$query") {
            if (currentId != null) popUpTo(currentId) { inclusive = true }
        }
    }

    // Transitions are decided per *edge*, not per destination, because NavHost
    // takes the enter from the target and the exit from the initial entry — a
    // per-route override would pair one screen's fade against another's slide.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { screenEnter(pop = false) },
        exitTransition = { screenExit(pop = false) },
        popEnterTransition = { screenEnter(pop = true) },
        popExitTransition = { screenExit(pop = true) }
    ) {
        composable(Routes.ONBOARDING) { backStackEntry ->
            val selectedAddress by backStackEntry.savedStateHandle
                .getStateFlow<String?>(Routes.RESULT_SELECTED_AGENT_ADDRESS, null)
                .collectAsState()
            OnboardingScreen(
                onProceedToConnect = {
                    // Config is saved; hand off to the shared LoadingScreen
                    // to perform the actual connection attempt — the same
                    // path auto-login uses, instead of a second duplicate
                    // connecting UI living inside Onboarding itself.
                    navController.navigate(Routes.LOADING) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.LOGS)
                },
                onNavigateToDiscover = { serverUrl, agentAddress ->
                    navController.navigate(
                        "${Routes.AGENT_DISCOVERY}?selectFor=onboarding" +
                            "&serverUrl=${Uri.encode(serverUrl)}" +
                            "&agentAddress=${Uri.encode(agentAddress)}"
                    )
                },
                pendingDiscoveredAddress = selectedAddress,
                onDiscoveredAddressConsumed = {
                    backStackEntry.savedStateHandle[Routes.RESULT_SELECTED_AGENT_ADDRESS] = null
                }
            )
        }

        composable(Routes.LOADING) {
            LoadingScreen(
                onConnectionSuccess = { onboardPending ->
                    navController.navigate("${Routes.CHAT}?onboardPending=$onboardPending") {
                        popUpTo(Routes.LOADING) { inclusive = true }
                    }
                },
                onConnectionFailed = { error ->
                    navController.navigate("${Routes.RECOVERY}?errorMessage=${Uri.encode(error)}") {
                        popUpTo(Routes.LOADING) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.LOADING) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = "${Routes.CHAT}?onboardPending={onboardPending}",
            arguments = listOf(
                navArgument("onboardPending") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "connectonion://agent/{address}" },
                navDeepLink { uriPattern = "https://oo-chat.com/{address}" }
            )
        ) { backStackEntry ->
            // `address` only ever comes from one of the two deep links declared
            // above — in-app agent switching goes to CHAT_WITH_AGENT instead —
            // so anything arriving here was supplied by whatever opened the
            // link, which on an exported intent filter is any web page or app.
            val linkedAddress = backStackEntry.arguments?.getString("address").orEmpty()
            val onboardPending = backStackEntry.arguments?.getBoolean("onboardPending") ?: false

            // Same check the Add Agent form applies before saving an address.
            val linkIsWellFormed = remember(linkedAddress) {
                AgentDiscoveryService.ADDRESS_REGEX.matches(linkedAddress)
            }
            var linkApproved by rememberSaveable(linkedAddress) { mutableStateOf(false) }
            var linkAnswered by rememberSaveable(linkedAddress) { mutableStateOf(false) }

            ChatScreen(
                // Until the user accepts, this screen behaves exactly as a
                // normal launch: it connects to their own configured agent.
                connectToAgentId = if (linkApproved) linkedAddress else "",
                onboardPending = onboardPending,
                // Opening the app resumes the previous conversation if it
                // still exists (ensureActiveSession falls back to a fresh one
                // when it doesn't). Starting fresh is now only ever an
                // explicit user action — the drawer's "New conversation".
                startNewSessionOnConnect = false,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.LOGS)
                },
                onNavigateToAgentList = {
                    navController.navigate(Routes.AGENT_LIST)
                },
                onNavigateToDashboard = {
                    navController.navigate(Routes.DASHBOARD)
                },
                onNavigateToAgentChat = navigateToAgentChat
            )

            if (linkedAddress.isNotEmpty() && !linkAnswered) {
                DeepLinkConnectSheet(
                    address = linkedAddress,
                    wellFormed = linkIsWellFormed,
                    onConnect = {
                        linkApproved = true
                        linkAnswered = true
                    },
                    onDismiss = { linkAnswered = true }
                )
            }
        }

        // Recovery screen for connection failures — failure reason is URL-encoded as a query arg.
        composable(
            route = "${Routes.RECOVERY}?errorMessage={errorMessage}",
            arguments = listOf(
                navArgument("errorMessage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val errorMessage = backStackEntry.arguments?.getString("errorMessage")
            RecoveryScreen(
                errorMessage = errorMessage,
                onRetry = {
                    // Same shared LoadingScreen the auto-login and Onboarding
                    // paths use, instead of a third duplicate connecting UI.
                    navController.navigate(Routes.LOADING) {
                        popUpTo(Routes.RECOVERY) { inclusive = true }
                    }
                },
                onEditConfiguration = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.RECOVERY) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = "${Routes.CHAT_WITH_AGENT}?sessionId={sessionId}&startNew={startNew}",
            arguments = listOf(
                navArgument("agentId") { type = NavType.StringType },
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("startNew") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val startNew = backStackEntry.arguments?.getBoolean("startNew") ?: false
            ChatScreen(
                connectToAgentId = agentId,
                sessionIdToRestore = sessionId,
                startNewSessionOnConnect = startNew,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.LOGS)
                },
                onNavigateToAgentList = {
                    navController.navigate(Routes.AGENT_LIST)
                },
                onNavigateToDashboard = {
                    navController.navigate(Routes.DASHBOARD)
                },
                onNavigateToAgentChat = navigateToAgentChat
            )
        }

        composable(Routes.AGENT_LIST) {
            AgentListScreenWrapper(
                onAgentSelect = { agentAddress ->
                    // Tap agent → connect and go to chat. Pop back to (and
                    // including) whichever chat entry sits under this screen,
                    // not just AGENT_LIST — otherwise the old chat's
                    // ChatViewModel stays alive underneath, still collecting
                    // the shared connection's events (see navigateToAgentChat).
                    val chatEntryRoute = CHAT_ROUTES.firstOrNull { route ->
                        runCatching { navController.getBackStackEntry(route) }.isSuccess
                    }
                    navController.navigate(
                        "${Routes.CHAT_WITH_AGENT}".replace("{agentId}", Uri.encode(agentAddress))
                    ) {
                        popUpTo(chatEntryRoute ?: Routes.AGENT_LIST) { inclusive = true }
                    }
                },
                onEditAgent = { agentId ->
                    navController.navigate("${Routes.AGENT_EDIT}/$agentId")
                },
                onCreateAgent = {
                    navController.navigate("${Routes.AGENT_EDIT}/new")
                },
                onNavigateToDiscovery = {
                    navController.navigate(Routes.AGENT_DISCOVERY)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("${Routes.AGENT_EDIT}/{agentId}") { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId")
            val selectedAddress by backStackEntry.savedStateHandle
                .getStateFlow<String?>(Routes.RESULT_SELECTED_AGENT_ADDRESS, null)
                .collectAsState()
            AgentEditScreenWrapper(
                agentId = agentId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDiscover = { serverUrl, agentAddress ->
                    navController.navigate(
                        "${Routes.AGENT_DISCOVERY}?selectFor=agentEdit" +
                            "&serverUrl=${Uri.encode(serverUrl)}" +
                            "&agentAddress=${Uri.encode(agentAddress)}"
                    )
                },
                pendingDiscoveredAddress = selectedAddress,
                onDiscoveredAddressConsumed = {
                    backStackEntry.savedStateHandle[Routes.RESULT_SELECTED_AGENT_ADDRESS] = null
                }
            )
        }

        // Agent discovery screen. Optional `selectFor` argument switches it into picker mode,
        // handing the address back via SavedStateHandle instead of saving a standalone agent.
        composable(
            route = "${Routes.AGENT_DISCOVERY}?selectFor={selectFor}&serverUrl={serverUrl}&agentAddress={agentAddress}",
            arguments = listOf(
                navArgument("selectFor") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("serverUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("agentAddress") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val selectFor = backStackEntry.arguments?.getString("selectFor")
            val serverUrl = backStackEntry.arguments?.getString("serverUrl")
            val agentAddress = backStackEntry.arguments?.getString("agentAddress")
            AgentDiscoveryScreenWrapper(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.LOGS)
                },
                selectionMode = selectFor != null,
                onAgentSelected = { agent ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(Routes.RESULT_SELECTED_AGENT_ADDRESS, agent.address)
                    navController.popBackStack()
                },
                initialServerUrl = serverUrl,
                initialAgentAddress = agentAddress
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateToAgents = {
                    navController.navigate(Routes.AGENT_LIST)
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.LOGS)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // The agent's own Home page. Reached only from the chat top bar, and
        // only once a DASHBOARD_SNAPSHOT has arrived, so the chat entry it
        // borrows its ChatViewModel from is always still on the stack — see
        // DashboardScreenWrapper for why that matters.
        composable(Routes.DASHBOARD) {
            DashboardScreenWrapper(
                chatViewModelStoreOwner = CHAT_ROUTES.firstNotNullOfOrNull { route ->
                    runCatching { navController.getBackStackEntry(route) }.getOrNull()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

/**
 * The two destinations that host a [ai.openonion.oochat.ui.chat.ChatViewModel].
 * Only one is ever on the stack at a time — each chat hop pops the other.
 */
private val CHAT_ROUTES = listOf(
    "${Routes.CHAT}?onboardPending={onboardPending}",
    "${Routes.CHAT_WITH_AGENT}?sessionId={sessionId}&startNew={startNew}"
)

/**
 * Routes that are pushed plainly and left with `popBackStack()` — the only ones
 * where "back" exists. Every other route (onboarding/loading/chat/recovery) is a
 * state machine: each hop pops its own source with `inclusive = true`, so a
 * directional slide would advertise a swipe-back that actually exits the app.
 */
private val DRILL_DOWN_ROUTES = listOf(
    Routes.AGENT_LIST,
    Routes.AGENT_EDIT,
    Routes.AGENT_DISCOVERY,
    Routes.SETTINGS,
    Routes.LOGS,
    Routes.DASHBOARD
)

private fun NavBackStackEntry.isDrillDown(): Boolean =
    DRILL_DOWN_ROUTES.any { destination.route?.startsWith(it) == true }

/**
 * AGENT_DISCOVERY carrying `selectFor` is a modal picker, not a page: it hands a
 * result back through SavedStateHandle and is dismissed rather than navigated
 * away from. Without the argument the same route is an ordinary browse screen.
 */
private fun NavBackStackEntry.isPicker(): Boolean =
    destination.route?.startsWith(Routes.AGENT_DISCOVERY) == true &&
        arguments?.getString("selectFor") != null

private val SlideSpec = tween<IntOffset>(Motion.Medium2, easing = Motion.Emphasized)

// Fade-through: the outgoing screen is fully gone before the incoming one starts,
// so two full-screen layouts never sit on top of each other at partial alpha.
private val FadeThroughIn =
    fadeIn(tween(Motion.Short4, delayMillis = Motion.Short2, easing = Motion.EmphasizedDecelerate))
private val FadeThroughOut =
    fadeOut(tween(Motion.Short3, easing = Motion.EmphasizedAccelerate))

/**
 * Enter transition for the incoming screen. [pop] distinguishes a back
 * navigation from a forward one — it is also what keeps the picker's vertical
 * motion from firing when we merely pop Logs off the top of it.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.screenEnter(
    pop: Boolean
): EnterTransition = when {
    !pop && targetState.isPicker() -> slideIntoContainer(SlideDirection.Up, SlideSpec)
    // Caller re-emerging from under a dismissing picker: opaque fast, so the
    // strip the picker has already uncovered is never a translucent gap.
    pop && initialState.isPicker() -> fadeIn(tween(Motion.Short2))
    isDrillDownEdge() ->
        if (pop) slideIntoContainer(SlideDirection.End, SlideSpec) { it / 4 }
        else slideIntoContainer(SlideDirection.Start, SlideSpec) + fadeIn(tween(Motion.Short2))
    // Loading -> Chat is an arrival, not a lateral move: the user has just
    // watched "Connected ✓". A fade-through between two screens that share a
    // background reads as nothing happening at all, so the conversation
    // settles in from below instead.
    isConnectedArrival() -> slideIntoContainer(SlideDirection.Up, SlideSpec) { it / 6 } +
        fadeIn(tween(Motion.Short4, easing = Motion.EmphasizedDecelerate))
    else -> FadeThroughIn
}

/** Exit transition for the outgoing screen; see [screenEnter] for [pop]. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.screenExit(
    pop: Boolean
): ExitTransition = when {
    !pop && targetState.isPicker() -> fadeOut(tween(Motion.Short2, delayMillis = Motion.Short4))
    pop && initialState.isPicker() -> slideOutOfContainer(SlideDirection.Down, SlideSpec)
    isDrillDownEdge() ->
        if (pop) slideOutOfContainer(SlideDirection.End, SlideSpec)
        // Quarter-distance parallax: the screen being covered reads as pressed
        // underneath the new one rather than pushed away alongside it.
        else slideOutOfContainer(SlideDirection.Start, SlideSpec) { it / 4 }
    // Paired with screenEnter's arrival: Loading leaves without moving, so the
    // only thing travelling is the conversation arriving over it.
    isConnectedArrival() -> fadeOut(tween(Motion.Short3, easing = Motion.EmphasizedAccelerate))
    else -> FadeThroughOut
}

/**
 * The one hop that is a completion rather than a change of place: Loading
 * resolving into the conversation it was opening. Deliberately narrow — every
 * other pairing among the root screens stays a fade-through, because those
 * really are lateral.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isConnectedArrival(): Boolean =
    initialState.destination.route?.startsWith(Routes.LOADING) == true &&
        targetState.destination.route?.startsWith(Routes.CHAT) == true

/** True when either end of this hop is a real back-stack screen. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isDrillDownEdge(): Boolean =
    initialState.isDrillDown() || targetState.isDrillDown()

/**
 * Gate on `connectonion://agent/<address>` and `https://oo-chat.com/<address>`.
 *
 * Both intent filters are exported, so the address in one of these links is
 * attacker-supplied by default: any web page can fire the scheme link, and the
 * https one is not `autoVerify`, so another app can claim it too. Connecting is
 * not a read-only act — it sends a CONNECT frame signed with this device's
 * Ed25519 identity and persists the agent, which is why it takes an explicit
 * yes rather than happening inside a LaunchedEffect on arrival.
 *
 * A [wellFormed] address still gets a confirmation because well-formed only
 * means "0x plus 64 hex", not "an agent you meant to talk to". A malformed one
 * cannot be connected to at all, and says so instead of failing silently.
 */
@Composable
private fun DeepLinkConnectSheet(
    address: String,
    wellFormed: Boolean,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationSheet(
        title = if (wellFormed) "Connect to this agent?" else "This link is not valid",
        body = if (wellFormed) {
            "A link asked to open a conversation with an agent that is not in your list. " +
                "Connecting identifies your device to it. Only continue if you know where " +
                "this link came from."
        } else {
            "The address in this link is not a valid agent address, so it has been ignored. " +
                "An agent address is 0x followed by 64 hexadecimal characters."
        },
        onDismiss = onDismiss,
        content = {
            Text(
                text = address.truncateMiddle(12, 8),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        actions = {
            if (wellFormed) {
                DangerConfirmActions(
                    confirmLabel = "Connect",
                    onConfirm = onConnect,
                    onDismiss = onDismiss,
                    cancelLabel = "Not now"
                )
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    )
}
