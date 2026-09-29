package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AppError
import ai.openonion.oochat.ui.agent.components.AdvancedSettingsSection
import ai.openonion.oochat.ui.agent.components.AgentFormContent
import ai.openonion.oochat.ui.agent.components.AgentFormState
import ai.openonion.oochat.ui.agent.components.EmbeddedDiscoveryPanel
import ai.openonion.oochat.ui.agent.components.FormFieldShape
import ai.openonion.oochat.ui.agent.components.formFieldColors
import ai.openonion.oochat.ui.agent.components.formFieldTextStyle
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.DangerConfirmActions
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Screen for adding or editing an agent.
 *
 * Edit mode ([existingAgent] non-null) shows the manual form. Create mode ([existingAgent] null)
 * leads with [EmbeddedDiscoveryPanel] for discovery-first selection, with the manual form as a
 * collapsed fallback for Direct/peer-to-peer connections or relays with no discovery endpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFormScreen(
    existingAgent: AgentProfile? = null,
    savedAgents: List<AgentProfile> = emptyList(),
    // The live AGENT_PROFILE for this agent, when it's the one currently
    // connected — see AgentEditScreenWrapper's match-by-address gate. Null
    // in create mode (there's nothing to be connected to yet) and whenever
    // this saved agent isn't the live connection.
    liveProfile: AgentLiveProfile? = null,
    onNavigateToDiscover: (serverUrl: String, agentAddress: String) -> Unit = { _, _ -> },
    pendingDiscoveredAddress: String? = null,
    onDiscoveredAddressConsumed: () -> Unit = {},
    // Create-mode-only discovery state/actions, plumbed from AgentEditScreenWrapper's
    // AgentViewModel — deliberately plain data/callbacks (not a ViewModel reference) to match
    // this screen's existing prop-driven, ViewModel-free style.
    discoveredAgents: List<AgentProfile> = emptyList(),
    discoveryPhase: DiscoveryPhase = DiscoveryPhase.Idle,
    discoveryError: AppError? = null,
    onDiscover: (String) -> Unit = {},
    onSelectDiscovered: (AgentProfile) -> Unit = {},
    onSave: (AgentProfile) -> Unit,
    onCancel: () -> Unit,
    errorMessage: AppError? = null,
    modifier: Modifier = Modifier
) {
    val isEditing = existingAgent != null
    val snackbarHostState = remember { SnackbarHostState() }

    // Surfaces createAgent/updateAgent failures (blank fields, duplicate
    // address) instead of the caller silently navigating back as if the
    // save succeeded — see AgentEditScreenWrapper's onSave.
    LaunchedEffect(errorMessage) {
        errorMessage?.let { snackbarHostState.showSnackbar(it.message) }
    }

    // Use agent ID as key so form resets when navigating to different agent
    val agentKey = existingAgent?.id ?: "new"

    var formState by remember(agentKey) {
        mutableStateOf(
            AgentFormState(
                serverUrl = existingAgent?.serverUrl ?: "https://oo.openonion.ai",
                agentAddress = existingAgent?.address ?: "",
                apiKey = existingAgent?.apiKey ?: "",
                useDirectConnection = existingAgent?.connectionMode == "direct"
            )
        )
    }
    var name by remember(agentKey) { mutableStateOf(existingAgent?.name ?: "") }
    var description by remember(agentKey) { mutableStateOf(existingAgent?.description ?: "") }

    // What the form looked like on arrival, so back-navigation can tell an
    // untouched form (leave silently) from an edited one (ask first).
    val pristine = remember(agentKey) {
        FormSnapshot(
            formState = formState,
            name = name,
            description = description
        )
    }
    val isDirty = formState != pristine.formState ||
        name != pristine.name ||
        description != pristine.description

    var showDiscardConfirm by remember(agentKey) { mutableStateOf(false) }
    val requestCancel: () -> Unit = { if (isDirty) showDiscardConfirm = true else onCancel() }

    // Covers the gesture/hardware back that never reaches the top bar's arrow.
    BackHandler(enabled = isDirty) { showDiscardConfirm = true }

    // Address handed back from the Discover Agents screen (picker mode) —
    // apply it once, then tell the caller to clear it so it doesn't reapply
    // on a later recomposition (e.g. navigating away and back). Only ever
    // fires for Edit mode's magnifier-button roundtrip; create mode's own
    // discovery is inline via EmbeddedDiscoveryPanel and never triggers this.
    LaunchedEffect(pendingDiscoveredAddress) {
        if (pendingDiscoveredAddress != null) {
            formState = formState.copy(agentAddress = pendingDiscoveredAddress)
            onDiscoveredAddressConsumed()
        }
    }

    fun save() {
        val address = formState.agentAddress.trim()
        val agent = AgentProfile(
            id = existingAgent?.id ?: java.util.UUID.randomUUID().toString(),
            address = address,
            name = name.trim().ifBlank { defaultAgentName(address) },
            description = description.trim().takeIf { it.isNotBlank() },
            serverUrl = formState.serverUrl.trim(),
            apiKey = formState.apiKey.trim().takeIf { it.isNotBlank() },
            avatarUrl = existingAgent?.avatarUrl,
            createdAt = existingAgent?.createdAt ?: System.currentTimeMillis(),
            lastConnectedAt = existingAgent?.lastConnectedAt,
            isActive = existingAgent?.isActive ?: true,
            connectionMode = if (formState.useDirectConnection) "direct" else "relay"
        )
        onSave(agent)
    }

    if (isEditing) {
        Scaffold(
            // See the create-mode Scaffold below for why imePadding is needed.
            modifier = modifier.imePadding(),
            topBar = {
                BackTopAppBar(
                    title = "Edit Agent",
                    onNavigateBack = requestCancel,
                    backIconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
                ) {
                    // Was 28sp Bold primary directly under the 22sp "Edit Agent"
                    // top-bar title — the same sentence twice, louder. The focus
                    // is the form and its save button.
                    Text(
                        text = "Update Agent Configuration",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Modify the agent settings below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )

                    // Only shown while this saved agent is the live
                    // connection — a disconnected agent has no current
                    // tools/skills to report, and stale data from a past
                    // session would be actively misleading here.
                    liveProfile?.let { profile ->
                        LiveProfileSection(profile)
                    }

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Agent Name (optional)") },
                        placeholder = { Text("My AI Agent", style = formFieldTextStyle()) },
                        singleLine = true,
                        supportingText = { Text("Left blank, the agent is named after its address.") },
                        shape = FormFieldShape,
                        colors = formFieldColors(),
                        textStyle = formFieldTextStyle(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AgentFormContent(
                        formState = formState,
                        onFormStateChange = { formState = it },
                        savedAgents = savedAgents,
                        onNavigateToDiscover = onNavigateToDiscover
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        placeholder = { Text("Describe this agent...", style = formFieldTextStyle()) },
                        maxLines = 3,
                        shape = FormFieldShape,
                        colors = formFieldColors(),
                        textStyle = formFieldTextStyle(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                PinnedSaveFooter(
                    label = "Save changes",
                    helper = "Changes will be saved to your agent list.",
                    enabled = formState.isSubmittable,
                    onClick = { save() }
                )
            }

            if (showDiscardConfirm) {
                DiscardChangesSheet(
                    onDiscard = {
                        showDiscardConfirm = false
                        onCancel()
                    },
                    onKeepEditing = { showDiscardConfirm = false }
                )
            }
        }
        return
    }

    // Create mode — discovery-first.
    Scaffold(
        // Edge-to-edge stops the window resizing for the IME (API 30+ ignores
        // adjustResize once the decor no longer fits system windows), so the
        // form takes the keyboard inset itself. Scaffold excludes what this
        // consumes from its own contentWindowInsets, so no doubled bottom gap.
        modifier = modifier.imePadding(),
        topBar = {
            BackTopAppBar(
                title = "Add agent",
                onNavigateBack = onCancel,
                backIconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
        ) {
            Text(
                text = "Select an agent from the server to add it to your list.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // A discovered agent already has a name, but manual entry
            // doesn't — so Name stays a top-level field (matching Edit
            // mode's field order) rather than living inside the panel,
            // which only Create mode's manual-address path actually needs it for.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Agent Name (optional)") },
                placeholder = { Text("My AI Agent", style = formFieldTextStyle()) },
                singleLine = true,
                supportingText = { Text("Left blank, the agent is named after its address.") },
                shape = FormFieldShape,
                colors = formFieldColors(),
                textStyle = formFieldTextStyle(),
                modifier = Modifier.fillMaxWidth()
            )

            EmbeddedDiscoveryPanel(
                formState = formState,
                onFormStateChange = { formState = it },
                savedAgents = savedAgents,
                discoveredAgents = discoveredAgents,
                discoveryPhase = discoveryPhase,
                error = discoveryError,
                onDiscover = onDiscover,
                onSelectDiscovered = onSelectDiscovered,
                actionLabel = "Select",
                // Add Agent picks from the radar above; a quick-pick of agents
                // already saved on this device would only re-add a duplicate.
                savedAgentDropdown = false,
                advancedSettings = {
                    AdvancedSettingsSection(
                        apiKey = formState.apiKey,
                        onApiKeyChange = { formState = formState.copy(apiKey = it) }
                    )
                },
                submitButton = {
                    Button(
                        onClick = { save() },
                        enabled = formState.isSubmittable,
                        modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Save agent")
                    }
                }
            )
        }

        if (showDiscardConfirm) {
            DiscardChangesSheet(
                onDiscard = {
                    showDiscardConfirm = false
                    onCancel()
                },
                onKeepEditing = { showDiscardConfirm = false }
            )
        }
    }
}

/** The form's field values at the moment it opened — see [AgentFormScreen]'s dirty check. */
private data class FormSnapshot(
    val formState: AgentFormState,
    val name: String,
    val description: String
)

/**
 * Name given to an agent saved with the Name field left blank.
 *
 * Agent Name used to be mandatory, which produced the worst kind of dead end:
 * Save stayed greyed out with nothing on screen explaining why. The address is
 * the one thing every agent has, so it supplies the label instead, and the
 * agent list stays scannable rather than showing an unlabelled row.
 */
internal fun defaultAgentName(address: String): String = "Agent ${address.truncateMiddle(6, 4)}"

/**
 * Guards back-navigation out of a form with unsaved edits. Both entry points
 * need it — the top bar's arrow and the system/gesture back, which never
 * reaches the arrow's callback.
 */
@Composable
private fun DiscardChangesSheet(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    ConfirmationSheet(
        title = "Discard changes?",
        body = "This agent's details have not been saved. Leaving now loses what you typed.",
        onDismiss = onKeepEditing,
        actions = {
            DangerConfirmActions(
                confirmLabel = "Discard",
                onConfirm = onDiscard,
                onDismiss = onKeepEditing,
                cancelLabel = "Keep editing"
            )
        }
    )
}

/**
 * The connected agent's own tools/skills, straight from its `AGENT_PROFILE`
 * frame — see [AgentLiveProfile]'s doc. Read-only: this is what the live
 * socket reported, not something the form edits.
 */
@Composable
private fun LiveProfileSection(profile: AgentLiveProfile) {
    if (profile.tools.isEmpty() && profile.skills.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Text(
            text = "Live from this agent",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (profile.tools.isNotEmpty()) {
            ProfileTagGroup(label = "Tools", tags = profile.tools)
        }
        if (profile.skills.isNotEmpty()) {
            // Names only: this is a chip row, and the descriptions belong to
            // the composer's `/` palette, where they help pick a command.
            ProfileTagGroup(label = "Skills", tags = profile.skills.map { it.name })
        }
    }
}

@Composable
private fun ProfileTagGroup(label: String, tags: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            tags.forEach { tag ->
                Text(
                    text = tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs2)
                )
            }
        }
    }
}

/**
 * Pinned Save footer: gradient fade + button, so it stays visible without
 * scrolling away (Figma pins this to the viewport bottom with a CSS
 * linear-gradient fade; this achieves the same effect via a weighted scroll
 * area + a non-scrolling footer sibling). Only used by Edit mode now — Create
 * mode's submit lives inside [EmbeddedDiscoveryPanel]'s fallback instead.
 */
@Composable
private fun PinnedSaveFooter(label: String, helper: String, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md)
        ) {
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(text = label)
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
