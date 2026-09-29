package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.R
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.components.AgentInitialsAvatar
import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.DangerConfirmActions
import ai.openonion.oochat.ui.components.agentStatusColor
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Width of the drawer panel. Shared with ChatScreen, which reserves the same
 * width for the placeholder it shows before the drawer's first open — the
 * open anchor must be identical either way or the first slide-in is wrong.
 */
val NavDrawerWidth = 300.dp

/** A single selectable session row nested under its agent. */
data class DrawerEntry(
    val id: String,
    val title: String
)

/**
 * One agent-grouped section of the drawer's agent list, emitted directly by
 * [ai.openonion.oochat.ui.chat.ChatViewModel.drawerAgents]. Deliberately
 * holds only what the drawer draws, so a persisted message — which rewrites a
 * session row's timestamp, count and preview — leaves this value unchanged.
 */
data class DrawerAgentSection(
    val agentId: String,
    val agentAddress: String,
    val name: String,
    val status: AgentStatus,
    val sessions: List<DrawerEntry>
)

/**
 * Navigation drawer content matching the Figma design's agent-grouped
 * sidebar: every saved agent is its own expandable row (status dot + "+"
 * new-chat action), holding that agent's own session list — replacing the
 * previous flat, single-agent session list.
 *
 * Self-contained and fully parameterized so it can be dropped into a
 * `ModalNavigationDrawer(drawerContent = { NavDrawer(...) })`.
 *
 * Layout (top → bottom):
 * - Brand header (wordmark + theme-cycle button)
 * - Scrollable "Agents" section: one row per agent, expandable to its own sessions
 * - Footer: "Add agent" (→ AgentListScreen) + Settings row + mini account card
 *
 * @param agentSections Every saved agent (already in the shared drag-to-reorder
 *   order — see [ai.openonion.oochat.data.repository.AgentRepository.reorderAgents])
 *   with its own live [AgentStatus] and session list
 * @param activeEntryId Currently selected session id, highlighted in primary color
 * @param themeMode Currently selected theme mode, cycled by the header button
 * @param connectedAgentAddress Address of the agent this session is connected to, or null if none yet
 * @param onNewChatForAgent Invoked with an agent's address when its "+" button is tapped
 * @param onSelectAgentSession Invoked with (agentAddress, sessionId) when a session row is tapped
 * @param onDeleteSession Invoked with a sessionId once the user confirms deleting that row —
 *   the confirmation sheet itself is owned by this composable (see [pendingDeleteEntry] below),
 *   matching the reference web client's per-row trash icon + confirm dialog (always visible on
 *   touch, unlike its desktop hover-reveal — there's no hover state on Android to reserve it for)
 * @param onRenameSession Invoked with (sessionId, newTitle) once the user confirms a rename —
 *   the edit sheet is owned by this composable (see [pendingRenameEntry] below), same pattern
 *   as [onDeleteSession]
 * @param onOpenAgentList Invoked when the footer "Add agent" button is tapped
 * @param onOpenSettings Invoked when the footer Settings row is tapped
 * @param onThemeModeChange Invoked when the header theme-cycle button is tapped
 * @param modifier Optional modifier for the drawer container
 * @param brandTitle Wordmark shown next to the brand icon
 */
@Composable
fun NavDrawer(
    agentSections: List<DrawerAgentSection>,
    activeEntryId: String?,
    themeMode: ThemeMode,
    onNewChatForAgent: (agentAddress: String) -> Unit,
    onSelectAgentSession: (agentAddress: String, sessionId: String) -> Unit,
    onDeleteSession: (sessionId: String) -> Unit,
    onRenameSession: (sessionId: String, newTitle: String) -> Unit,
    onOpenAgentList: () -> Unit,
    onOpenSettings: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    connectedAgentAddress: String? = null,
    brandTitle: String = "ConnectOnion"
) {
    // Tracks COLLAPSED agent ids (inverted, matching the Figma reference) so
    // any agent not yet seen here — including ones added after first
    // composition — defaults to expanded without needing to be seeded.
    var collapsedAgentIds by remember { mutableStateOf(emptySet<String>()) }

    // Which row's trash icon was tapped, awaiting confirmation — a single
    // piece of state here (not per-row) so at most one confirmation sheet
    // can ever be showing, mirroring the reference web client's single
    // `pendingDelete` state in its sidebar rather than one per row.
    var pendingDeleteEntry by remember { mutableStateOf<DrawerEntry?>(null) }

    // Same single-piece-of-state pattern as pendingDeleteEntry above, for the
    // pencil icon's rename sheet.
    var pendingRenameEntry by remember { mutableStateOf<DrawerEntry?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(NavDrawerWidth)
            // Glassmorphism approximation of the Figma design's
            // `background: var(--glass-bg)` + `backdrop-filter: blur(20px)
            // saturate(130%)`: a translucent surface + subtle border. The
            // actual content blur (what would be `backdrop-filter` in CSS)
            // is applied one level up, in ChatScreen.kt, to the screen
            // content sitting *behind* this drawer — Modifier.blur() blurs
            // its own subtree's rendered output, so blurring the content
            // achieves the same visual result as blurring the panel would,
            // without making this drawer's own text/icons unreadable.
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f))
            .border(
                // Figma's --glass-border is rgba(outline, 0.20) in both
                // themes, not outline-variant (which is nearly indistinguishable
                // from the drawer's own fill in dark mode).
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))
            )
            // ModalNavigationDrawer hands its drawerContent the full window
            // height and applies no insets of its own (this is a raw Column,
            // not a ModalDrawerSheet), so the panel's glass fill/border above
            // still spans edge to edge while the rows inset themselves.
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Brand ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // top was xxl (32dp) hand-simulating the status bar; the real
                // inset is taken by the parent now, so this is plain spacing.
                .padding(
                    start = MaterialTheme.spacing.lg,
                    end = MaterialTheme.spacing.lg,
                    top = MaterialTheme.spacing.md,
                    bottom = MaterialTheme.spacing.md
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm2)
        ) {
            // Real brand mark — an independent multi-color logo, shown
            // as-is rather than tinted into a themed chip (the app's
            // green accent and the logo's purple are deliberately not
            // forced to match).
            Image(
                painter = painterResource(R.drawable.ic_onion_logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = brandTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            ThemeCycleButton(mode = themeMode, onChange = onThemeModeChange)
        }

        HorizontalDividerThin()

        // ── Scrollable agent list ──
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.sm2, vertical = MaterialTheme.spacing.sm)
        ) {
            val onlineCount = agentSections.count {
                it.status == AgentStatus.Active || it.status == AgentStatus.Connected
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacing.sm,
                        end = MaterialTheme.spacing.sm,
                        top = MaterialTheme.spacing.md2,
                        bottom = MaterialTheme.spacing.xs
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AGENTS",
                    style = MaterialTheme.typography.sectionLabel,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    if (onlineCount > 0) {
                        Text(
                            text = "$onlineCount online",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .border(
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    MaterialTheme.shapes.large
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "${agentSections.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
            }

            if (agentSections.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Text(
                        text = "No agents yet",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Add one below to start chatting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            agentSections.forEach { section ->
                val expanded = section.agentId !in collapsedAgentIds
                AgentSectionRow(
                    section = section,
                    expanded = expanded,
                    onToggleExpanded = {
                        collapsedAgentIds = if (expanded) {
                            collapsedAgentIds + section.agentId
                        } else {
                            collapsedAgentIds - section.agentId
                        }
                    },
                    onNewChat = { onNewChatForAgent(section.agentAddress) },
                    activeEntryId = activeEntryId,
                    onSelectSession = { sessionId -> onSelectAgentSession(section.agentAddress, sessionId) },
                    onRequestDeleteSession = { entry -> pendingDeleteEntry = entry },
                    onRequestRenameSession = { entry -> pendingRenameEntry = entry }
                )
            }
        }

        HorizontalDividerThin()

        // ── Footer: Add Agent + Settings + mini account card ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.sm2,
                    vertical = MaterialTheme.spacing.sm
                )
        ) {
            // "Add agent" — dashed ghost button; new chat is now a per-agent "+" action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .border(
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        MaterialTheme.shapes.medium
                    )
                    .clickable(onClick = onOpenAgentList)
                    .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Text(
                    text = "Add agent",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = MaterialTheme.spacing.sm2, vertical = MaterialTheme.spacing.sm2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm2)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            MiniAccountCard(agentAddress = connectedAgentAddress)
        }
    }

    pendingDeleteEntry?.let { entry ->
        DeleteSessionSheet(
            sessionTitle = entry.title,
            onConfirm = {
                onDeleteSession(entry.id)
                pendingDeleteEntry = null
            },
            onDismiss = { pendingDeleteEntry = null }
        )
    }

    pendingRenameEntry?.let { entry ->
        RenameSessionSheet(
            currentTitle = entry.title,
            onConfirm = { newTitle ->
                onRenameSession(entry.id, newTitle)
                pendingRenameEntry = null
            },
            onDismiss = { pendingRenameEntry = null }
        )
    }
}

/** Confirmation sheet for a session's trash-icon tap — matches ChatTopBar's ClearChatSheet pattern. */
@Composable
private fun DeleteSessionSheet(sessionTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Delete this chat?",
        body = "\"$sessionTitle\" and all its messages will be permanently deleted.",
        onDismiss = onDismiss,
        actions = {
            DangerConfirmActions(confirmLabel = "Delete", onConfirm = onConfirm, onDismiss = onDismiss)
        }
    )
}

/** Rename sheet for a session's pencil-icon tap — pre-filled, Save disabled until the title actually changes. */
@Composable
private fun RenameSessionSheet(currentTitle: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(currentTitle) }
    val trimmed = input.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != currentTitle

    ConfirmationSheet(
        // A statement, not a question: the sheet asks for a name rather than
        // for a yes, so it follows ImportKeySheet rather than its own
        // delete-sibling two functions up.
        title = "Rename chat",
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onConfirm(trimmed) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

/** Thin full-width divider matching the drawer's outline-variant separators. */
@Composable
private fun HorizontalDividerThin() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * One agent row (chevron / avatar / name / "+" new-chat / status dot),
 * expandable to that agent's own session list.
 */
@Composable
private fun AgentSectionRow(
    section: DrawerAgentSection,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNewChat: () -> Unit,
    activeEntryId: String?,
    onSelectSession: (String) -> Unit,
    onRequestDeleteSession: (DrawerEntry) -> Unit,
    onRequestRenameSession: (DrawerEntry) -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = MaterialTheme.spacing.sm, end = MaterialTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                AgentInitialsAvatar(
                    name = section.name,
                    size = 22.dp,
                    shape = MaterialTheme.shapes.small,
                    maxInitials = 1,
                    textStyle = MaterialTheme.typography.labelSmall,
                    backgroundColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.surface
                )
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // "+" new chat, to the left of the status dot per the confirmed
            // spec. Outer 48dp box carries the touch target; the 22dp tinted
            // square is unchanged, just centered inside it. The clip shapes
            // the ripple — without it the state layer fills the 48dp square.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNewChat),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                            MaterialTheme.shapes.small
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New chat with ${section.name}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

            StatusDot(status = section.status, modifier = Modifier.padding(end = MaterialTheme.spacing.sm))
        }

        if (expanded) {
            // A thread/indent guide on the left only, not a box around the
            // whole list. Compose has no one-sided border modifier, so the line
            // itself is a thin filled Box beside the row content instead.
            Row(modifier = Modifier.padding(start = 32.dp).height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                if (section.sessions.isEmpty()) {
                    Text(
                        text = "No chats yet",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = MaterialTheme.spacing.sm, top = 2.dp, bottom = MaterialTheme.spacing.xs)
                    )
                } else {
                    Column(modifier = Modifier.padding(start = MaterialTheme.spacing.sm)) {
                        section.sessions.forEach { entry ->
                            DrawerEntryRow(
                                entry = entry,
                                active = entry.id == activeEntryId,
                                onClick = { onSelectSession(entry.id) },
                                onRenameClick = { onRequestRenameSession(entry) },
                                onDeleteClick = { onRequestDeleteSession(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small color-coded status indicator — pulses only for [AgentStatus.Connecting] (a real in-flight signal). */
@Composable
private fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier) {
    val color = agentStatusColor(status)
    val pulsing = status == AgentStatus.Connecting
    val transition = rememberInfiniteTransition(label = "statusDotPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusDotAlpha"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = if (pulsing) alpha else 1f }
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * A single tappable session row with a selected highlight (primary tint +
 * border) and a trailing trash icon. Always visible (not hover-revealed —
 * the reference web client only reserves that for desktop; touch has no
 * hover state to reveal on), same as its mobile behavior.
 */
@Composable
private fun DrawerEntryRow(
    entry: DrawerEntry,
    active: Boolean,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        // 48dp touch target (WCAG 2.5.5 / Material minimum) — was 40dp.
        // This drawer body is a plain scrolling Column (not a LazyColumn, as
        // in the upstream fix this is ported from), but the effect is the
        // same: rows stack vertically, so the extra 8dp just reflows the
        // list taller; nothing to overlap.
        .heightIn(min = 48.dp)
        .clip(MaterialTheme.shapes.small)
        .then(
            if (active) {
                Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        shape = MaterialTheme.shapes.small
                    )
            } else {
                Modifier
            }
        )
        .clickable(onClick = onClick)
        .padding(start = MaterialTheme.spacing.sm2, end = MaterialTheme.spacing.xs, top = MaterialTheme.spacing.xs, bottom = MaterialTheme.spacing.xs)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // No explicit size: default 48dp touch target, now that the row
        // itself is also heightIn(min = 48.dp) so this no longer overflows it.
        IconButton(onClick = onRenameClick) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Rename \"${entry.title}\"",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete \"${entry.title}\"",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Cycles light → dark → system on each tap, matching the Figma header
 * control that replaced the old 3-segment footer toggle.
 */
@Composable
private fun ThemeCycleButton(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val next = when (mode) {
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.SYSTEM
        ThemeMode.SYSTEM -> ThemeMode.LIGHT
    }
    val icon = when (mode) {
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
        ThemeMode.SYSTEM -> Icons.Filled.Smartphone
    }

    // Outer 48dp box carries the touch target; the 32dp tinted square is
    // unchanged, just centered inside it. It's the last item in the brand
    // row (Row padding end = lg), so the extra 8dp per side eats into that
    // padding rather than colliding with anything.
    Box(
        modifier = Modifier
            .size(48.dp)
            // Shapes the ripple; without it the state layer fills the square.
            .clip(CircleShape)
            .clickable(onClick = { onChange(next) }),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    shape = MaterialTheme.shapes.small
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Theme: ${mode.name.lowercase()} — tap to cycle",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Compact account preview in the drawer footer: the address this session is
 * connected to, or an em dash before any connection is established.
 *
 * No balance row. A balance is only worth saying when it is low enough to act
 * on, and by then it needs to be beside the composer rather than behind a
 * drawer — see [ai.openonion.oochat.ui.chat.components.LowBalanceBar].
 */
@Composable
private fun MiniAccountCard(agentAddress: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.sm2, vertical = MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Agent",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = agentAddress?.truncateMiddle(6, 4) ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 300, heightDp = 720)
@Composable
private fun NavDrawerPreview() {
    ConnectOnionTheme {
        NavDrawer(
            agentSections = listOf(
                DrawerAgentSection(
                    agentId = "1",
                    agentAddress = "0x1a2b3c",
                    name = "Research Assistant",
                    status = AgentStatus.Connected,
                    sessions = listOf(
                        DrawerEntry("1", "Quantum entanglement basics"),
                        DrawerEntry("2", "React Server Components")
                    )
                ),
                DrawerAgentSection(
                    agentId = "2",
                    agentAddress = "0x9a2b3c",
                    name = "Code Copilot",
                    status = AgentStatus.Connecting,
                    sessions = listOf(
                        DrawerEntry("3", "Refactor auth module")
                    )
                ),
                DrawerAgentSection(
                    agentId = "3",
                    agentAddress = "0x55aa66",
                    name = "Data Analyst",
                    status = AgentStatus.Disabled,
                    sessions = emptyList()
                )
            ),
            activeEntryId = "1",
            themeMode = ThemeMode.SYSTEM,
            onNewChatForAgent = {},
            onSelectAgentSession = { _, _ -> },
            onDeleteSession = {},
            onRenameSession = { _, _ -> },
            onOpenAgentList = {},
            onOpenSettings = {},
            onThemeModeChange = {},
            connectedAgentAddress = "0x1a2b3c",
        )
    }
}
