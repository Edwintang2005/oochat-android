package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.ConnectionStatusDot
import ai.openonion.oochat.ui.components.DangerConfirmActions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Header mirrors Figma: hamburger opens the drawer, title row (status dot +
 * title) with the model name quietly underneath, agent-home button, overflow
 * menu for logs/clear. Settings only lives in the drawer footer — don't
 * duplicate it here.
 *
 * Fixed 64dp and never wraps: title and model name are each one line with an
 * ellipsis, and there is nothing else left in the row that can grow it — the
 * old three-line "Connected · model · $balance" subtitle is why this used to
 * grow past 200dp on a real title. The balance moved to the drawer's account
 * area; see [ai.openonion.oochat.ui.navigation.NavDrawer].
 */
@Composable
fun ChatTopBar(
    title: String,
    connectionState: ConnectionState,
    isConnected: Boolean,
    onClearChat: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onMenuClick: () -> Unit,
    awaitingOnboardCode: Boolean = false,
    // See ConnectionStatusDot's own doc — surfaces the connected
    // agent's model once its AGENT_PROFILE frame lands.
    modelName: String? = null,
    // True once a DASHBOARD_SNAPSHOT has arrived. The button is absent, not
    // disabled, when it hasn't: most agents have no Home page, and a dead
    // control on every one of them is worse than no control.
    hasDashboard: Boolean = false,
    onOpenDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showClearChatSheet by remember { mutableStateOf(false) }

    if (showClearChatSheet) {
        ClearChatSheet(
            onConfirm = {
                showClearChatSheet = false
                onClearChat()
            },
            onDismiss = { showClearChatSheet = false }
        )
    }

    Row(
        modifier = modifier
            .testTag("chat_top_bar")
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // TopAppBar applied this for us; a plain Row does not, and without
            // it the bar sits under the clock and the battery icon.
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
            Icon(imageVector = Icons.Default.Menu, contentDescription = "Open menu")
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp)
        ) {
            // Row 1: dot + title, same baseline — the dot carries "connected",
            // there is no "Connected" word any more.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConnectionStatusDot(connectionState = connectionState, awaitingOnboardCode = awaitingOnboardCode)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Row 2: model, quiet and small — indented to align with the
            // title text (dot 6dp + row gap 6dp = 12dp), not with the dot.
            if (modelName != null) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        if (hasDashboard) {
            IconButton(onClick = onOpenDashboard, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Dashboard,
                    contentDescription = "Open agent home"
                )
            }
        }

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options"
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                // 1.4 switched the default from surface-plus-elevation to
                // the flat surfaceContainer role, which drops the green
                // tint the rest of the app carries. Same computation as
                // before, expressed through the theme rather than pinned
                // to a value, so dark mode still derives its own.
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                DropdownMenuItem(
                    text = { Text("View logs") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onNavigateToLogs()
                    }
                )
                // Gap, then the rule. A hairline alone still leaves the
                // destructive item one thumb-width from the safe one; the
                // space is what stops a reach-through.
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear chat", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showClearChatSheet = true
                    }
                )
            }
        }
    }
}

@Composable
private fun ClearChatSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Clear chat?",
        body = "All messages in this conversation will be permanently deleted.",
        onDismiss = onDismiss,
        actions = {
            DangerConfirmActions(
                confirmLabel = "Clear",
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    )
}
