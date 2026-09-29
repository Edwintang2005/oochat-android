package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.ui.components.AgentInitialsAvatar
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.components.EmptyStateMessage
import ai.openonion.oochat.ui.components.PillTextField
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.sectionOverline
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Agent discovery screen: search a relay server for agents and add them to
 * the local list. Directly replicates Figma's DiscoverScreen.tsx layout —
 * a pill-shaped URL field + labeled "Discover" button, boxed icons for the
 * idle/empty states, and a radar sweep with status text while loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDiscoveryScreen(
    discoveredAgents: List<AgentProfile>,
    isLoading: Boolean,
    error: String? = null,
    savedAddresses: Set<String> = emptySet(),
    onDiscover: (String) -> Unit,
    onAddAgent: (AgentProfile) -> Unit,
    onBack: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    // Picker mode: opened from a form's Agent Address field. Replaces the
    // "+" add action with a whole-card tap that hands the address straight
    // back to the caller instead of saving a standalone agent entry here.
    selectionMode: Boolean = false,
    onSelectAgent: (AgentProfile) -> Unit = {},
    // Server URL carried over from the calling form (Setup / Create Agent),
    // if any — prefills the field and triggers an immediate search instead
    // of leaving the user to retype a URL they already entered.
    initialServerUrl: String? = null,
    // Whatever was already typed in the calling form's Agent Address field —
    // used in selectionMode to highlight the one matching result (if any),
    // instead of reusing "already added to my saved agents" (a different,
    // unrelated concept) for the selection highlight.
    initialAgentAddress: String? = null,
    modifier: Modifier = Modifier
) {
    var serverUrl by remember {
        mutableStateOf(initialServerUrl?.takeIf { it.isNotBlank() } ?: "https://oo.openonion.ai")
    }
    // Distinguishes "never searched yet" from "searched, zero results" —
    // discoveredAgents.isEmpty() alone can't tell those apart, and showing
    // "No agents found" before any search has run is misleading.
    var hasSearched by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun runDiscover() {
        hasSearched = true
        onDiscover(serverUrl)
    }

    // Auto-search once when a URL was carried over from the caller.
    LaunchedEffect(Unit) {
        if (!initialServerUrl.isNullOrBlank()) {
            runDiscover()
        }
    }

    Scaffold(
        // The window no longer resizes for the IME under edge-to-edge, so the
        // results list is inset here rather than sliding under the keyboard.
        modifier = Modifier.imePadding(),
        topBar = {
            BackTopAppBar(
                title = "Discover Agents",
                onNavigateBack = onBack,
                backIconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Logs",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md)
            ) {
                // Server URL + Discover button — a pill-shaped bordered field
                // (leading search icon, clear button) beside a labeled pill
                // button, matching Figma's header row exactly.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    ServerUrlField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        onSubmit = { runDiscover() },
                        modifier = Modifier.weight(1f)
                    )

                    DiscoverButton(
                        isLoading = isLoading,
                        enabled = serverUrl.isNotBlank() && !isLoading,
                        onClick = { runDiscover() }
                    )
                }

                Text(
                    text = "Queries the relay server for available AI agents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs, start = 2.dp)
                )
            }

            // Results
            when {
                isLoading -> DiscoveryRadar(url = serverUrl)

                !hasSearched -> IdleDiscoveryState()

                error != null -> ErrorDiscoveryState(
                    message = error,
                    onTryAgain = { runDiscover() }
                )

                discoveredAgents.isEmpty() -> EmptyDiscoveryState(
                    // Searches again, as the label says. Clearing hasSearched
                    // only returned the panel to its idle prompt, which reads
                    // as the search having been forgotten rather than retried.
                    onTryAgain = { runDiscover() }
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MaterialTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    val addedCount = discoveredAgents.count { it.address in savedAddresses }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${discoveredAgents.size} agent${if (discoveredAgents.size == 1) "" else "s"} found".uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.sectionLabel,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (selectionMode) {
                            Text(
                                text = "Tap to select",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (addedCount > 0) {
                            Text(
                                text = "$addedCount added",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.lg)
                    ) {
                        itemsIndexed(discoveredAgents, key = { _, agent -> agent.id }) { index, agent ->
                            DiscoveredAgentItem(
                                agent = agent,
                                alreadyAdded = if (selectionMode) {
                                    agent.address.isNotEmpty() && agent.address == initialAgentAddress
                                } else {
                                    agent.address in savedAddresses
                                },
                                selectionMode = selectionMode,
                                isLast = index == discoveredAgents.lastIndex,
                                onAdd = {
                                    onAddAgent(agent)
                                    android.widget.Toast.makeText(
                                        context,
                                        "Added ${agent.name}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onSelect = { onSelectAgent(agent) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Server URL field: Figma's 14dp-radius surface-container pill with a
 * leading search icon and a clear ("×") action once non-empty — a thin
 * wrapper over the shared [PillTextField], which carries the
 * BasicTextField-over-OutlinedTextField rationale this field used to
 * re-implement.
 */
@Composable
private fun ServerUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    PillTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "Server URL",
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        itemSpacing = MaterialTheme.spacing.xs,
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        },
        trailingContent = if (value.isEmpty()) null else {
            {
                // Outer 48dp box carries the touch target; the 16dp icon is
                // unchanged, just centered inside it.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    )
}

/**
 * Labeled pill button — "Discover" with a search icon, or "Scanning…" with a
 * spinner while loading — matching Figma's auto-width button exactly (not a
 * square icon-only button).
 */
@Composable
private fun DiscoverButton(isLoading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (isLoading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Scanning…",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Discover",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * Individual discovered agent item: a 40dp single-letter square icon,
 * connection-mode badge (relay/direct — real data from
 * [AgentProfile.connectionMode], standing in for Figma's decorative
 * capability badge), a tappable address chip that expands to the full
 * address, and — depending on [selectionMode] — either an Add button that
 * scale-bounces then morphs into a filled checkmark once added, or (picker
 * mode, opened from a form's Agent Address field) a whole-card tap that
 * hands the address back to the caller.
 *
 * Figma's result card also has a color-coded latency indicator; there is no
 * real ping/RTT measurement anywhere in this app's discovery flow, so it's
 * omitted rather than showing a fabricated number.
 */
@Composable
fun DiscoveredAgentItem(
    agent: AgentProfile,
    alreadyAdded: Boolean = false,
    selectionMode: Boolean = false,
    isLast: Boolean = false,
    onAdd: () -> Unit = {},
    onSelect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var addressExpanded by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only the already-added / selected result keeps a container. One
            // highlighted row is the point of a search-result screen.
            .then(
                if (alreadyAdded) {
                    Modifier
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                } else Modifier
            )
            .then(if (selectionMode) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(horizontal = if (alreadyAdded) MaterialTheme.spacing.md else 0.dp)
            .padding(vertical = MaterialTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Single-letter square icon, matching Figma's AgentResultCard exactly.
        AgentInitialsAvatar(
            name = agent.name,
            size = 36.dp,
            shape = MaterialTheme.shapes.small,
            maxInitials = 1,
            textStyle = MaterialTheme.typography.titleMedium,
            backgroundColor = if (alreadyAdded) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (alreadyAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            border = BorderStroke(
                1.dp,
                if (alreadyAdded) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.outlineVariant
            )
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agent.name,
                    // Same level as AgentListScreen's AgentItem name: an
                    // agent's name on its card in a list.
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (alreadyAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                ConnectionModeBadge(connectionMode = agent.connectionMode)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = if (selectionMode) {
                    Modifier
                } else {
                    // 48dp touch target — this row otherwise wraps to just
                    // the single-line address text's height.
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable { addressExpanded = !addressExpanded }
                }
            ) {
                Text(
                    text = if (addressExpanded) agent.address else agent.address.truncateMiddle(8, 6),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!selectionMode) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = if (addressExpanded) 90f else 0f }
                    )
                }
            }
            if (agent.description != null) {
                Text(
                    text = agent.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }

        if (selectionMode) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AddAgentButton(alreadyAdded = alreadyAdded, onAdd = onAdd)
        }
    }
        // Carries the grouping the removed card borders used to.
        if (!isLast) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Tertiary-tinted pill showing the agent's real connection mode (relay/direct). */
@Composable
private fun ConnectionModeBadge(connectionMode: String) {
    // Figma: color-mix(tertiary 12%, transparent) fill, plain tertiary text —
    // a subtle translucent tint, not the Material tertiaryContainer role
    // (which, separately, is hardcoded to the same hex in both light and
    // dark in this app's ColorScheme, so it rendered identically "wrong
    // blue" regardless of theme).
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(tertiary.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, tertiary.copy(alpha = 0.25f)), MaterialTheme.shapes.large)
            .padding(horizontal = MaterialTheme.spacing.xs, vertical = 2.dp)
    ) {
        Text(
            text = connectionMode.uppercase(Locale.ROOT),
            // Uppercase-badge level — same as Settings' SoonBadge.
            style = MaterialTheme.typography.sectionOverline,
            color = tertiary
        )
    }
}

/** Add button: scale-bounces on tap, then morphs into a filled checkmark once added. */
@Composable
private fun AddAgentButton(alreadyAdded: Boolean, onAdd: () -> Unit) {
    var justTapped by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (justTapped) 1.18f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "addAgentBounce",
        finishedListener = { justTapped = false }
    )

    // Outer 48dp box carries the touch target; the 40dp filled/bordered
    // circle is unchanged, just centered inside it.
    Box(
        modifier = Modifier
            .size(48.dp)
            // Shapes the ripple; without it the state layer fills the square.
            .clip(CircleShape)
            .clickable(enabled = !alreadyAdded) {
                justTapped = true
                onAdd()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(
                    if (alreadyAdded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                )
                .border(
                    BorderStroke(
                        1.5.dp,
                        if (alreadyAdded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (alreadyAdded) Icons.Default.CheckCircle else Icons.Default.Add,
                contentDescription = if (alreadyAdded) "Already added" else "Add agent",
                tint = if (alreadyAdded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Boxed icon used by the idle/empty/error states — 72dp, 22dp corners, bordered. */
@Composable
private fun StateIconBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tinted: Boolean = false,
    isError: Boolean = false
) {
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (tinted || isError) accent.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (tinted || isError) accent.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                MaterialTheme.shapes.large
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (isError) accent else MaterialTheme.colorScheme.outline
        )
    }
}

/** Shown before the user has ever tapped Discover — distinct from [EmptyDiscoveryState]. */
@Composable
private fun IdleDiscoveryState() {
    EmptyStateMessage(
        icon = { StateIconBox(icon = Icons.Default.Search) },
        title = "No agents discovered yet",
        body = "Enter a server URL above and tap Discover to find available AI agents."
    )
}

/** Shown after a search with no results: boxed icon, message, and a "Try again" action. */
@Composable
private fun EmptyDiscoveryState(onTryAgain: () -> Unit) {
    EmptyStateMessage(
        icon = { StateIconBox(icon = Icons.Default.SearchOff, tinted = true) },
        title = "No agents found",
        body = "The server didn't return any discoverable agents. Check the URL and try again.",
        action = {
            Button(onClick = onTryAgain, shape = MaterialTheme.shapes.medium) {
                Text("Try again")
            }
        }
    )
}

/**
 * Shown when [AgentDiscoveryScreen]'s discover call actually failed (bad URL,
 * network error, non-200 relay response) — distinct from [EmptyDiscoveryState]
 * so a real failure doesn't look identical to "relay has zero agents".
 */
@Composable
private fun ErrorDiscoveryState(message: String, onTryAgain: () -> Unit) {
    EmptyStateMessage(
        icon = { StateIconBox(icon = Icons.Default.SearchOff, isError = true) },
        title = "Discovery failed",
        body = message,
        action = {
            Button(onClick = onTryAgain, shape = MaterialTheme.shapes.medium) {
                Text("Try again")
            }
        }
    )
}

/**
 * Radar-sweep loading animation with status text, matching Figma's
 * centerpiece discovery-loading state: concentric rings + a rotating
 * gradient sweep, "Discovering agents…" in primary, and the target URL
 * (protocol stripped) below it.
 */
@Composable
private fun DiscoveryRadar(url: String) {
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing)
        ),
        label = "radarAngle"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
        ) {
            val ringColor = MaterialTheme.colorScheme.primary
            val sweepColor = MaterialTheme.colorScheme.primary

            Canvas(modifier = Modifier.size(140.dp)) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f

                // Concentric rings, opacity increasing outward — matches
                // Figma's four rings at 0.28/0.52/0.76/1.0 fractions.
                listOf(0.28f, 0.52f, 0.76f, 1f).forEachIndexed { index, fraction ->
                    drawCircle(
                        color = ringColor,
                        radius = maxRadius * fraction,
                        center = center,
                        alpha = 0.15f + index * 0.06f,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Rotating sweep, drawn as a wedge with a fading gradient
                rotate(degrees = angle, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                sweepColor.copy(alpha = 0f),
                                sweepColor.copy(alpha = 0f),
                                sweepColor.copy(alpha = 0.35f)
                            ),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = true,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            center.x - maxRadius,
                            center.y - maxRadius
                        ),
                        size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                    )
                }

                // Center dot
                drawCircle(color = sweepColor, radius = 3.5.dp.toPx(), center = center)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Discovering agents…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = url.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
