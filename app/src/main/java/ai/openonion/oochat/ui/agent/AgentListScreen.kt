package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.ui.common.rememberClipboard
import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.DangerConfirmActions
import ai.openonion.oochat.ui.components.EmptyStateMessage
import ai.openonion.oochat.ui.components.agentStatusColor
import ai.openonion.oochat.ui.theme.sectionOverline
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Agent list screen component.
 *
 * Displays all agents with options to create, edit, delete, and set default.
 * Title/back/discover/add navigation lives in [AgentListScreenWrapper]'s top
 * bar — this composable only renders the list body.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentListScreen(
    agents: List<AgentProfile>,
    selectedAgentId: String?,
    defaultAgentId: String?,
    snackbarHostState: SnackbarHostState? = null,
    onAgentSelect: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onEditAgent: (AgentProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onReorderAgents: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var agentToDelete by remember { mutableStateOf<AgentProfile?>(null) }

    agentToDelete?.let { agent ->
        DeleteAgentSheet(
            agentName = agent.name,
            onConfirm = {
                onDeleteAgent(agent.id)
                agentToDelete = null
            },
            onDismiss = { agentToDelete = null }
        )
    }

    if (agents.isEmpty()) {
        EmptyStateMessage(
            modifier = modifier,
            contentPadding = PaddingValues(MaterialTheme.spacing.xxl),
            itemSpacing = MaterialTheme.spacing.lg,
            icon = {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.large)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            },
            title = "No agents configured",
            // titleStyle/bodyStyle left at EmptyStateMessage's defaults so
            // this reads at the same size as the discovery empty/error states.
            titleFontWeight = FontWeight.Bold,
            body = "Add your first agent to start using AI features.",
            actionSpacingBefore = 0.dp,
            action = {
                Button(onClick = onCreateAgent) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Add first agent")
                }
            }
        )
    } else {
        // Local drag order, optimistically reordered on each drag move and
        // persisted (via onReorderAgents) once the drag ends. Synced back
        // from the source list whenever it changes for a non-drag reason
        // (agent added/edited/removed) — a completed drag's own persisted
        // order round-trips back through that same source unchanged, so this
        // doesn't fight the in-progress gesture.
        var orderedAgents by remember { mutableStateOf(agents) }
        LaunchedEffect(agents) { orderedAgents = agents }

        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            orderedAgents = orderedAgents.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }

        // No verticalArrangement: the card brings its own separation and the
        // bare rows have dividers. A uniform gap made every agent read alike.
        LazyColumn(
            state = lazyListState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm)
        ) {
            itemsIndexed(orderedAgents, key = { _, agent -> agent.id }) { index, agent ->
                ReorderableItem(reorderableState, key = agent.id) {
                    AgentItem(
                        agent = agent,
                        isSelected = agent.id == selectedAgentId,
                        isDefault = agent.id == defaultAgentId,
                        isLast = index == orderedAgents.lastIndex,
                        snackbarHostState = snackbarHostState,
                        onSelect = { onAgentSelect(agent.address) },
                        onEdit = { onEditAgent(agent) },
                        onDelete = { agentToDelete = agent },
                        onSetDefault = { onSetDefault(agent.id) },
                        dragHandleModifier = with(this) {
                            Modifier.draggableHandle(
                                onDragStopped = { onReorderAgents(orderedAgents.map { it.id }) }
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * One agent, at one of two weights: the default keeps a card (primary-tinted
 * border, ExtraBold name, "DEFAULT" badge), everything else is a bare row with
 * a divider, truncated address and a 7dp status dot.
 *
 * Every agent used to get the same card, so the screen read as a uniform table
 * and the default was marked only by a 14dp star. Both weights share the same
 * overflow menu, so demoting a row costs it no functionality.
 */
@Composable
fun AgentItem(
    agent: AgentProfile,
    isSelected: Boolean,
    isDefault: Boolean,
    isLast: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    dragHandleModifier: Modifier? = null,
    modifier: Modifier = Modifier
) {
    if (isDefault) {
        DefaultAgentCard(
            agent = agent,
            isSelected = isSelected,
            snackbarHostState = snackbarHostState,
            onSelect = onSelect,
            onEdit = onEdit,
            onDelete = onDelete,
            onSetDefault = onSetDefault,
            dragHandleModifier = dragHandleModifier,
            modifier = modifier
        )
    } else {
        AgentRow(
            agent = agent,
            isSelected = isSelected,
            isLast = isLast,
            snackbarHostState = snackbarHostState,
            onSelect = onSelect,
            onEdit = onEdit,
            onDelete = onDelete,
            onSetDefault = onSetDefault,
            dragHandleModifier = dragHandleModifier,
            modifier = modifier
        )
    }
}

/** The one agent that keeps a container — see [AgentItem]. */
/** The leading status dot on an ordinary row, and the gap the default row reserves in its place. */
private val AgentStatusDotSize = 7.dp

@Composable
private fun DefaultAgentCard(
    agent: AgentProfile,
    isSelected: Boolean,
    snackbarHostState: SnackbarHostState?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    dragHandleModifier: Modifier?,
    modifier: Modifier = Modifier
) {
    // Card's own onClick overload, not `Modifier.clickable` on the modifier we
    // hand to Card: that route puts the ripple under Card's opaque container
    // colour, so the most important row in the list pressed with no feedback.
    Card(
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.sm),
        shape = MaterialTheme.shapes.medium,
        // Primary-tinted rather than outline-variant: this is the border that
        // has to carry "this is the one" from across the screen.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                // Figma specified color-mix(primary-container 22%, surface-container),
                // but that assumed the old out-of-palette primaryContainer; against a
                // proper M3 container tone a 22% wash lands at 1.05:1 — invisible.
                // secondaryContainer is M3's selected-state role and reads at full strength.
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Row(
            // Vertical only. A horizontal inset here pushed this row's drag
            // handle and overflow button inwards by 16dp while every other
            // row's sat flush, so the two controls stepped sideways as the
            // selection moved down the list.
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dragHandleModifier != null) {
                // dragHandleModifier carries the drag gesture detector — it
                // goes on the 48dp touch box, not directly on the icon, so
                // the drag target isn't limited to the glyph's own ~24dp.
                Box(
                    modifier = dragHandleModifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Reorder agent",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            }
            // Holds the width of the status dot the ordinary rows carry here,
            // which this row states as "Active" further down instead. Without
            // it every name in the list starts at one x and this one at
            // another.
            Spacer(modifier = Modifier.width(AgentStatusDotSize + MaterialTheme.spacing.sm2))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                    // A word reads at a glance where a 14dp star did not.
                    Text(
                        text = "DEFAULT",
                        style = MaterialTheme.typography.sectionOverline,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxs))
                Text(
                    text = agent.address.truncateMiddle(10, 6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
                if (agent.description != null) {
                    Text(
                        text = agent.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Per-agent status derived from real data (isActive) — not a
                // shared/global value, so each card reflects its own agent
                // rather than all cards showing whichever single status
                // happened to be set. Live Connecting/Connected/Error states
                // aren't shown here because this screen has no per-agent
                // live connection signal to draw from honestly.
                StatusBadge(
                    agentStatus = if (agent.isActive) AgentStatus.Active else AgentStatus.Disabled,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs)
                )
            }

            AgentOverflowMenu(
                agent = agent,
                isDefault = true,
                snackbarHostState = snackbarHostState,
                onEdit = onEdit,
                onDelete = onDelete,
                onSetDefault = onSetDefault
            )
        }
    }
}

/** Every non-default agent — see [AgentItem]. */
@Composable
private fun AgentRow(
    agent: AgentProfile,
    isSelected: Boolean,
    isLast: Boolean,
    snackbarHostState: SnackbarHostState?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    dragHandleModifier: Modifier?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Only one agent is ever the active one, same single-select
                // family as the default agent's Card(onClick) above.
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 48.dp)
                .padding(vertical = MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dragHandleModifier != null) {
                // dragHandleModifier carries the drag gesture detector — it
                // goes on the 48dp touch box, not directly on the icon, so
                // the drag target isn't limited to the glyph's own 20dp.
                Box(
                    modifier = dragHandleModifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Reorder agent",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            }
            // The card's icon+text StatusBadge would out-weigh the agent's own
            // name here; a dot keeps the colour semantics at less ink.
            Box(
                modifier = Modifier
                    .size(AgentStatusDotSize)
                    .clip(CircleShape)
                    .background(
                        agentStatusColor(
                            if (agent.isActive) AgentStatus.Active else AgentStatus.Disabled
                        )
                    )
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm2))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = agent.address.truncateMiddle(8, 5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            AgentOverflowMenu(
                agent = agent,
                isDefault = false,
                snackbarHostState = snackbarHostState,
                onEdit = onEdit,
                onDelete = onDelete,
                onSetDefault = onSetDefault
            )
        }
        if (!isLast) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Shared by both agent weights, so demoting a row to a bare row costs it nothing. */
@Composable
private fun AgentOverflowMenu(
    agent: AgentProfile,
    isDefault: Boolean,
    snackbarHostState: SnackbarHostState?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberClipboard()
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Agent options",
                tint = MaterialTheme.colorScheme.outline
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            // See ChatTopBar's menu: 1.4 dropped the tonal-elevation tint.
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            if (!isDefault) {
                DropdownMenuItem(
                    text = { Text("Set as default") },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onSetDefault()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Copy address") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    copyToClipboard("Agent address", agent.address)
                    snackbarHostState?.let { host ->
                        scope.launch {
                            host.showSnackbar("Copied ${agent.address.truncateMiddle(10, 6)}")
                        }
                    }
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun StatusBadge(agentStatus: AgentStatus, modifier: Modifier = Modifier) {
    val statusColor = agentStatusColor(agentStatus)
    val (statusIcon, statusText) = when (agentStatus) {
        AgentStatus.Active -> Icons.Default.PanoramaFishEye to "Active"
        AgentStatus.Connecting -> Icons.Default.Sync to "Connecting…"
        AgentStatus.Connected -> Icons.Default.Circle to "Connected"
        is AgentStatus.Error -> Icons.Default.Error to "Error"
        AgentStatus.Disabled -> Icons.Default.Block to "Disabled"
    }

    val rotation = if (agentStatus == AgentStatus.Connecting) {
        val transition = rememberInfiniteTransition(label = "statusSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing)
            ),
            label = "statusSpinAngle"
        ).value
    } else {
        0f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = statusText,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = statusColor
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor
        )
    }
}

/** Bottom sheet confirming agent deletion, matching ChatTopBar's clear-chat sheet. */
@Composable
private fun DeleteAgentSheet(agentName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Delete Agent",
        body = "Are you sure you want to delete \"$agentName\"? All associated sessions will be removed.",
        onDismiss = onDismiss,
        actions = {
            DangerConfirmActions(
                confirmLabel = "Delete",
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                confirmBorder = true
            )
        }
    )
}
