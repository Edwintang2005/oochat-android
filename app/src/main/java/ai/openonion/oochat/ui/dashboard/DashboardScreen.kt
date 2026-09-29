package ai.openonion.oochat.ui.dashboard

import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.components.EmptyStateMessage
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The connected agent's Home page: the `dashboard.html` it wrote for itself,
 * rendered inside the containment described in [DashboardDocument].
 *
 * Owns exactly one piece of state — whether the page just tried to navigate
 * away — and hoists it straight into [DashboardScreenContent], which is what
 * makes that state (otherwise only reachable through a real WebView's
 * `shouldOverrideUrlLoading`, which Robolectric's stub WebView never fires)
 * drivable from a test.
 *
 * @param html The agent's snapshot, or null when none has arrived.
 * @param allowedSkills The agent's published skills, from its `AGENT_PROFILE`.
 *   Null or empty refuses every button — see [DashboardSkillIntent].
 * @param onRunSkill Receives a validated `/skill …` line to send as a normal,
 *   visible user message.
 * @param agentName Titles the bar. This is *an agent's* page, and its name is
 *   the only thing that can say which — "Home" alone named nothing.
 * @param chatAwaitsUser The conversation is parked on something only the user
 *   can answer. This screen replaces the chat, so without saying so the run
 *   stalls out of sight — see [ChatViewModel.chatAwaitsUser].
 */
@Composable
fun DashboardScreen(
    html: String?,
    allowedSkills: List<String>?,
    onRunSkill: (message: String) -> Unit,
    onNavigateBack: () -> Unit,
    agentName: String? = null,
    chatAwaitsUser: Boolean = false,
    modifier: Modifier = Modifier
) {
    // A fallback for pages that declare no background of their own; one that
    // does still wins, since its rule comes later in the document. Resolved
    // here, where MaterialTheme is in scope, and handed to the pure builder as
    // CSS; a theme switch is a new document.
    val surfaceCss = MaterialTheme.colorScheme.surface.toCssHex()
    val onSurfaceCss = MaterialTheme.colorScheme.onSurface.toCssHex()
    // A new snapshot is a new document with a new nonce; an unchanged snapshot
    // keeps the one already loaded, so a run that didn't touch the dashboard
    // leaves it exactly as it was.
    val document = remember(html, surfaceCss, onSurfaceCss) {
        html?.let {
            DashboardDocument.build(it, DashboardDocument.generateNonce(), surfaceCss, onSurfaceCss)
        }
    }
    var navigationBlocked by remember(document) { mutableStateOf(false) }

    DashboardScreenContent(
        document = document,
        allowedSkills = allowedSkills,
        onRunSkill = onRunSkill,
        onNavigateBack = onNavigateBack,
        agentName = agentName,
        chatAwaitsUser = chatAwaitsUser,
        navigationBlocked = navigationBlocked,
        onNavigationBlocked = { navigationBlocked = true },
        onDismissNavigationBlocked = { navigationBlocked = false },
        modifier = modifier
    )
}

/**
 * The presentational half of [DashboardScreen] — every bit of state hoisted
 * into parameters, [document] included, so it can be driven directly from a
 * test or a screenshot baseline.
 */
@Composable
internal fun DashboardScreenContent(
    document: String?,
    allowedSkills: List<String>?,
    onRunSkill: (message: String) -> Unit,
    onNavigateBack: () -> Unit,
    agentName: String?,
    chatAwaitsUser: Boolean,
    navigationBlocked: Boolean,
    onNavigationBlocked: () -> Unit,
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
                // Both bars are pinned here, not inside the scrollable content:
                // the page is arbitrary HTML of any length, and a notice that
                // scrolls away is one the reader waiting on it will never see.
                //
                // Waiting bar first, always in the same spot right under the
                // header — a stalled run is the reader's problem to resolve.
                // The nav-blocked notice is merely informational and
                // dismissible, so it stacks below rather than displacing the
                // bar that actually matters.
                if (chatAwaitsUser) ChatAwaitsUserBar(onNavigateBack = onNavigateBack)
                if (navigationBlocked) NavigationBlockedBar(onDismiss = onDismissNavigationBlocked)
                // The page below is the agent's own document and usually
                // brings its own palette, so its edge against our chrome is a
                // colour change we cannot predict. Drawing the boundary makes
                // it read as the seam it is rather than as a mismatch.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                document == null -> DashboardNotice(
                    // Nothing on the wire says "this agent has no dashboard" —
                    // the host simply never sends one. Reaching this screen at
                    // all takes a snapshot, so this is only the window between
                    // a reconnect clearing the old one and the new one landing.
                    title = "No Home page yet",
                    body = "This agent hasn't sent a Home page for this connection."
                )

                else -> DashboardWebView(
                    document = document,
                    onRunSkill = { skill, args ->
                        val message = DashboardSkillIntent.toChatMessage(skill, args, allowedSkills)
                        if (message == null) {
                            FileLogger.w(LogTags.DASHBOARD, "Refused dashboard skill intent (not in this agent's published skills)")
                        } else {
                            onRunSkill(message)
                        }
                    },
                    onNavigationBlocked = onNavigationBlocked,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * The one thing on this screen that is about the conversation rather than the
 * page. Tinted rather than plain because a stalled run is the reader's problem
 * to resolve, and the whole row is the tap target — the point is to get back,
 * not to read.
 */
@Composable
private fun ChatAwaitsUserBar(onNavigateBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onNavigateBack)
            .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "The conversation is waiting for you",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Open",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * The page tried to navigate away. Link interception here is expected and
 * harmless — a slim, muted, dismissible bar rather than the full-screen
 * notice this replaced, which used to lose the whole page over a blocked
 * link. The page stays visible underneath.
 */
@Composable
private fun NavigationBlockedBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Links in this page open here — navigation away is blocked",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // minimumInteractiveComponentSize() was tried here to reach 48dp
        // without growing this 36dp bar, but the reported layout size still
        // grows even when nothing new is painted at rest — Row gives the
        // adjacent weighted Text less width, shifting its ellipsis and
        // changing 4 Roborazzi baselines (dashboard_screen_nav_blocked_*).
        // Left at 32dp: a changed baseline is worse than a small target here.
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * A Compose colour as a CSS `#rrggbb` literal — the one conversion that lets
 * [DashboardDocument] stay free of Android and Compose types. Alpha is dropped:
 * the theme's surface roles are opaque, and there is nothing behind the WebView
 * for a translucent page to blend with.
 */
internal fun Color.toCssHex(): String = "#%06x".format(toArgb() and 0xFFFFFF)

@Composable
private fun DashboardNotice(title: String, body: String) {
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
        body = body
    )
}
