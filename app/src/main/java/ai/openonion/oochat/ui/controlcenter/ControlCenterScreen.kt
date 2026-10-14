package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.components.EmptyStateMessage
import ai.openonion.oochat.ui.dashboard.ChatAwaitsUserBar
import ai.openonion.oochat.ui.dashboard.NavigationBlockedBar
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat

/**
 * The agent's Home when it has published a Control Center app (W16AAPPLE-29). Built like
 * [ai.openonion.oochat.ui.dashboard.DashboardScreen], with the same bars: the app replaces the
 * chat on screen, so a conversation waiting on the user still has to say so here.
 *
 * @param app The published app, or null until the Host sends it for this connection.
 * @param supported Whether this device's WebView can host it; see [controlCenterSupported].
 * @param addMessageListener For tests: Robolectric's WebView cannot register one.
 */
@Composable
fun ControlCenterScreen(
    app: ControlCenterApp?,
    snapshot: ControlCenterSnapshot,
    actions: ControlCenterActions,
    onNavigateBack: () -> Unit,
    agentName: String? = null,
    chatAwaitsUser: Boolean = false,
    supported: Boolean = remember { controlCenterSupported() },
    modifier: Modifier = Modifier,
    addMessageListener: (WebView, WebViewCompat.WebMessageListener) -> Unit = ::addPageListener
) {
    var navigationBlocked by remember(app) { mutableStateOf(false) }
    var stopped by remember(app) { mutableStateOf(false) }
    var listenerFailed by remember(app) { mutableStateOf(false) }

    ControlCenterScreenContent(
        // The parser refuses most unpinnable addresses; our own origin is the one it cannot know.
        app = app?.takeIf { ControlCenterPage.appOriginOf(it.url) != null },
        supported = supported && !listenerFailed,
        stopped = stopped,
        onReload = { stopped = false },
        content = { loaded ->
            ControlCenterWebView(
                app = loaded,
                snapshot = snapshot,
                actions = actions,
                onNavigationBlocked = { navigationBlocked = true },
                onStopped = { stopped = true },
                onUnavailable = { listenerFailed = true },
                modifier = Modifier.fillMaxSize(),
                addMessageListener = addMessageListener
            )
        },
        onNavigateBack = onNavigateBack,
        agentName = agentName,
        chatAwaitsUser = chatAwaitsUser,
        navigationBlocked = navigationBlocked,
        onDismissNavigationBlocked = { navigationBlocked = false },
        modifier = modifier
    )
}

/** The presentational half of [ControlCenterScreen], with every bit of state hoisted for tests. */
@Composable
internal fun ControlCenterScreenContent(
    app: ControlCenterApp?,
    supported: Boolean,
    stopped: Boolean,
    onReload: () -> Unit,
    content: @Composable (ControlCenterApp) -> Unit,
    onNavigateBack: () -> Unit,
    agentName: String?,
    chatAwaitsUser: Boolean,
    navigationBlocked: Boolean,
    onDismissNavigationBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                BackTopAppBar(
                    title = agentName?.takeIf { it.isNotBlank() } ?: "Agent home",
                    onNavigateBack = onNavigateBack,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                if (chatAwaitsUser) ChatAwaitsUserBar(onNavigateBack = onNavigateBack)
                if (navigationBlocked) NavigationBlockedBar(onDismiss = onDismissNavigationBlocked)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // Also the window between a reconnect clearing the app and the Host resending it.
                app == null -> ControlCenterNotice(
                    title = "No Control Center yet",
                    body = "This agent hasn't sent a Control Center app for this connection."
                )
                !supported -> ControlCenterNotice(
                    title = "Control Center unavailable",
                    body = "This device's WebView is too old to show it. Update Android System WebView and try again."
                )
                // Not reloaded by itself, so an app that keeps crashing cannot loop.
                stopped -> ControlCenterNotice(
                    title = "Control Center stopped",
                    body = "Its page stopped unexpectedly. The chat is not affected.",
                    action = {
                        Button(onClick = onReload, shape = MaterialTheme.shapes.medium) {
                            Text("Reload")
                        }
                    }
                )
                else -> content(app)
            }
        }
    }
}

@Composable
private fun ControlCenterNotice(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    EmptyStateMessage(
        icon = {
            Icon(
                imageVector = Icons.Outlined.Dashboard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        },
        title = title,
        body = body,
        action = action
    )
}

/**
 * Whether Home opens the Control Center rather than the legacy Home page: wherever the device can
 * host the app, and also when there is no legacy page, so the screen can say why it cannot.
 */
internal fun homeOpensControlCenter(hasApp: Boolean, hasDashboard: Boolean, supported: () -> Boolean): Boolean =
    hasApp && (!hasDashboard || supported())
