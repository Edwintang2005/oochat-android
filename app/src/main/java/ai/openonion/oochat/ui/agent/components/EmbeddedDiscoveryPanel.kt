package ai.openonion.oochat.ui.agent.components

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AppError
import ai.openonion.oochat.ui.agent.DiscoveredAgentItem
import ai.openonion.oochat.ui.agent.DiscoveryPhase
import ai.openonion.oochat.ui.components.PillTextField
import ai.openonion.oochat.ui.theme.Motion
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Discovery-first connection panel: Relay/Direct mode toggle up top drives
 * everything below it (single source of truth), a single Server URL field
 * doubles as the relay scan input or the direct connection target, and the
 * Agent Address field is always directly visible — no "Connect by address
 * instead" toggle hiding it. Shared by Onboarding and Add Agent.
 *
 * [advancedSettings] and [submitButton] are caller-supplied slots since
 * submit validation/labels differ per screen (Onboarding just connects;
 * Add Agent also needs a Name field and builds a full [AgentProfile]).
 */
@Composable
fun EmbeddedDiscoveryPanel(
    formState: AgentFormState,
    onFormStateChange: (AgentFormState) -> Unit,
    savedAgents: List<AgentProfile> = emptyList(),
    discoveredAgents: List<AgentProfile>,
    discoveryPhase: DiscoveryPhase,
    error: AppError?,
    onDiscover: (String) -> Unit,
    onSelectDiscovered: (AgentProfile) -> Unit,
    actionLabel: String = "Connect",
    // Off for Add Agent: its address comes from the radar above, and a
    // quick-pick of agents already saved would only re-add a duplicate.
    savedAgentDropdown: Boolean = true,
    modifier: Modifier = Modifier,
    advancedSettings: @Composable () -> Unit = {},
    submitButton: @Composable () -> Unit
) {
    // Mode-switch clearing, mirrors AgentFormContent's own handling so both
    // surfaces behave identically when flipping Relay/Direct.
    var previousMode by remember { mutableStateOf(formState.useDirectConnection) }
    LaunchedEffect(formState.useDirectConnection) {
        if (formState.useDirectConnection != previousMode) {
            previousMode = formState.useDirectConnection
            onFormStateChange(
                if (formState.useDirectConnection) {
                    formState.copy(serverUrl = "", agentAddress = "")
                } else {
                    formState.copy(serverUrl = ConnectionConfig.DEFAULT_RELAY_URL, agentAddress = "")
                }
            )
        }
    }

    // Auto-scan once on first composition — the panel always starts in
    // Relay mode (AgentFormState's own default), matching the Onboarding/Add
    // Agent screens' fresh formState.
    LaunchedEffect(Unit) { onDiscover(formState.serverUrl) }

    val isDirect = formState.useDirectConnection

    // Idle counts as scanning: the LaunchedEffect above is about to start one,
    // and a radar that is momentarily early is honest, where "no agents on
    // this server" — about a server we have not spoken to yet — is not. It
    // also keeps Rescan disabled over that gap instead of inviting a second
    // scan on top of the one already starting.
    val isScanning = discoveryPhase != DiscoveryPhase.Done
    // Only once the server has actually answered may the panel give up its
    // 220dp and shrink to a single line.
    val foundNothing = !isScanning && error == null && discoveredAgents.isEmpty()

    // Someone typing an address by hand has decided against discovery, so the
    // radar hands its 220dp back. Tracked as one derived signal — a tap on a
    // dropdown suggestion both focuses and fills, and must not read as two
    // separate events.
    var addressFocused by remember { mutableStateOf(false) }
    val addressInUse = addressFocused || formState.agentAddress.isNotEmpty()
    var radarCollapsed by remember { mutableStateOf(false) }
    // Keyed on the address itself, not on [addressInUse]: keying on the latter
    // made re-opening by hand permanent, since it was already true by then and
    // no further edit could re-trigger. Typing after a manual re-open is the
    // clearest possible statement that discovery is no longer wanted, while
    // re-opening and only reading the results changes neither key.
    LaunchedEffect(addressFocused, formState.agentAddress) { radarCollapsed = addressInUse }

    Column(modifier = modifier) {
        // 1. Mode toggle — top-level first decision, drives everything below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            ModeToggleButton(
                label = "Relay Server",
                selected = !isDirect,
                onClick = { onFormStateChange(formState.copy(useDirectConnection = false)) },
                modifier = Modifier.weight(1f)
            )
            ModeToggleButton(
                label = "Direct Connection",
                selected = isDirect,
                onClick = { onFormStateChange(formState.copy(useDirectConnection = true)) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        // 2. Server URL — single unified field: relay mode pairs it with a
        // Rescan button (this field IS the scan input); direct mode is a
        // plain field holding the final connection URL.
        FieldLabel("Server URL")
        if (!isDirect) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                PillTextField(
                    shape = FormFieldShape,
                    value = formState.serverUrl,
                    onValueChange = { onFormStateChange(formState.copy(serverUrl = it)) },
                    placeholder = "https://oo.openonion.ai",
                    label = "Server URL",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingContent = if (
                        formState.serverUrl.isNotEmpty() && formState.serverUrl != ConnectionConfig.DEFAULT_RELAY_URL
                    ) {
                        {
                            // Outer 48dp box carries the touch target; the
                            // 16dp icon is unchanged, just centered inside it.
                            // The pill's own heightIn(min = 52.dp) already
                            // has room for it.
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        onFormStateChange(formState.copy(serverUrl = ConnectionConfig.DEFAULT_RELAY_URL))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Reset to default relay",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f)
                )

                RescanButton(
                    isLoading = isScanning,
                    enabled = formState.serverUrl.isNotBlank() && !isScanning,
                    onClick = { onDiscover(formState.serverUrl) }
                )
            }
        } else {
            PillTextField(
                shape = FormFieldShape,
                value = formState.serverUrl,
                onValueChange = { onFormStateChange(formState.copy(serverUrl = it)) },
                placeholder = "https://your-server.com",
                label = "Server URL",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = if (isDirect) {
                "Direct mode connects peer-to-peer. Requires the agent to be reachable from your network."
            } else {
                "Relay mode routes through oo.openonion.ai for NAT traversal and reliability."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs, bottom = MaterialTheme.spacing.lg)
        )

        // 3. Relay only: scan results panel.
        if (!isDirect && radarCollapsed) {
            CollapsedRadarSummary(
                agentCount = discoveredAgents.size,
                isScanning = isScanning,
                hasError = error != null,
                onExpand = { radarCollapsed = false }
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
        } else if (!isDirect) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (foundNothing) Modifier else Modifier.heightIn(min = 220.dp))
                    .animateContentSize(animationSpec = tween(Motion.Medium1, easing = Motion.Emphasized))
                    .clip(MaterialTheme.shapes.large)
                    // Depth comes from the tonal surface, not a border on top of
                    // it: M3 already reads surfaceContainer as raised, and the
                    // 1dp outline was a second, redundant way of saying so. The
                    // border stays only where the surface is transparent (a
                    // result list) and something has to mark the edge.
                    .then(
                        if (discoveredAgents.isEmpty() || isScanning || error != null) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                        } else {
                            Modifier.border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                MaterialTheme.shapes.large
                            )
                        }
                    )
            ) {
                when {
                    isScanning -> RadarLoadingState(formState.serverUrl)
                    // A different icon, not just a different tint: this one says
                    // the server was never reached, where SearchOff below says it
                    // answered and had nothing. Sharing the glyph made the two
                    // outcomes look like the same thing, and they point the user
                    // at different fixes — the network versus the URL.
                    error != null -> InlineDiscoveryMessage(
                        icon = Icons.Default.CloudOff,
                        isError = true,
                        title = "Can't reach this server",
                        body = error.message,
                        onRetry = { onDiscover(formState.serverUrl) }
                    )
                    discoveredAgents.isEmpty() -> CollapsedEmptyDiscovery(
                        onRetry = { onDiscover(formState.serverUrl) }
                    )
                    else -> Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${discoveredAgents.size} agent${if (discoveredAgents.size == 1) "" else "s"} found".uppercase(Locale.ROOT),
                                style = MaterialTheme.typography.sectionLabel,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "Tap to ${actionLabel.lowercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        // Capped + independently scrollable rather than laying
                        // every result out flat: a relay can return a dozen-plus
                        // agents, and stacking all of them at full height would
                        // push the address field/advanced settings/submit far
                        // down the page.
                        Column(
                            modifier = Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm)
                        ) {
                            discoveredAgents.forEachIndexed { index, agent ->
                                DiscoveredAgentItem(
                                    agent = agent,
                                    selectionMode = true,
                                    isLast = index == discoveredAgents.lastIndex,
                                    onSelect = { onSelectDiscovered(agent) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
        }

        // 4. Agent Address — always visible; relay frames it as a manual
        // alternative to tapping a scanned card, direct frames it as the
        // primary field (there's nothing else to fill in for that mode).
        AgentAddressField(
            value = formState.agentAddress,
            onValueChange = { onFormStateChange(formState.copy(agentAddress = it)) },
            savedAgents = savedAgents,
            useDirectConnection = isDirect,
            label = if (isDirect) "Agent Address" else "Or enter Agent Address manually",
            enableDropdown = savedAgentDropdown,
            onFocusChanged = { addressFocused = it }
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        // 5. Advanced Settings — caller-supplied (API Key today).
        advancedSettings()

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        // 6. Submit — caller-supplied (label/validation differ per screen).
        submitButton()
    }
}

/** "Rescan" pill button — labeled, matching the panel's URL bar height (mirrors AgentDiscoveryScreen's DiscoverButton). */
@Composable
private fun RescanButton(isLoading: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
                text = "Rescan",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** Radar-sweep loading animation with status text — a compact variant of AgentDiscoveryScreen's DiscoveryRadar. */
@Composable
private fun RadarLoadingState(url: String) {
    val transition = rememberInfiniteTransition(label = "embeddedRadarSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 2200, easing = LinearEasing)),
        label = "embeddedRadarAngle"
    )

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            val ringColor = MaterialTheme.colorScheme.primary

            Canvas(modifier = Modifier.size(120.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f

                listOf(0.28f, 0.52f, 0.76f, 1f).forEachIndexed { index, fraction ->
                    drawCircle(
                        color = ringColor,
                        radius = maxRadius * fraction,
                        center = center,
                        alpha = 0.15f + index * 0.06f,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                rotate(degrees = angle, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                ringColor.copy(alpha = 0f),
                                ringColor.copy(alpha = 0f),
                                ringColor.copy(alpha = 0.35f)
                            ),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = true,
                        topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                        size = Size(maxRadius * 2, maxRadius * 2)
                    )
                }

                drawCircle(color = ringColor, radius = 3.dp.toPx(), center = center)
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

/**
 * The empty result, collapsed to a single line.
 *
 * A 220dp box whose entire content is "there is nothing here" is 220dp of
 * nothing. Once the server has genuinely answered with no agents, the panel
 * hands the space back to the address field below it. Rescan stays, because an
 * empty relay is usually a transient or wrong-URL condition rather than a
 * verdict — this collapses the box, it does not close the door.
 */
@Composable
private fun CollapsedEmptyDiscovery(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "No agents on this server",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
            Text("Rescan")
        }
    }
}

/**
 * The radar, collapsed to one line because the user is entering an address by
 * hand. Reversible on purpose — typing one character must not cost the user
 * the scan results for the rest of the session; "Show" puts them straight
 * back, and clearing the field does too.
 */
@Composable
private fun CollapsedRadarSummary(
    agentCount: Int,
    isScanning: Boolean,
    hasError: Boolean,
    onExpand: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onExpand)
            .padding(start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Icon(
            imageVector = if (hasError) Icons.Default.CloudOff else Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = when {
                isScanning -> "Scanning for agents…"
                hasError -> "Can't reach this server"
                agentCount == 0 -> "No agents on this server"
                else -> "$agentCount agent${if (agentCount == 1) "" else "s"} found"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onExpand, shape = MaterialTheme.shapes.medium) {
            Text("Show")
        }
    }
}

/** Shared empty/error body for the panel — boxed icon, message, and a "Try again" action. */
@Composable
private fun InlineDiscoveryMessage(
    icon: ImageVector,
    isError: Boolean,
    title: String,
    body: String,
    onRetry: () -> Unit
) {
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (isError) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    BorderStroke(1.dp, if (isError) accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant),
                    MaterialTheme.shapes.large
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isError) accent else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
            Text("Try again")
        }
    }
}
