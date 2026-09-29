package ai.openonion.oochat.ui.agent.components

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.ui.components.AgentInitialsAvatar
import ai.openonion.oochat.ui.components.PillTextField
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared 12dp rounded shape — matches the Figma design's uniform 12px-radius
 * input styling. Passed to the shared [PillTextField] by this form and used
 * by AgentFormScreen's Name/Description fields (which aren't in the Figma
 * source, so they stay on the stock Material3 [OutlinedTextField]).
 */
internal val FormFieldShape = RoundedCornerShape(12.dp)

/**
 * Focus-tinted border/container colors for AgentFormScreen's Name/Description
 * [OutlinedTextField]s. Server URL/Agent Address/API Key use [PillTextField]
 * instead — see its doc comment for why.
 */
/**
 * The one input-text size, shared by the stock [OutlinedTextField]s and
 * [PillTextField] alike.
 *
 * A bare Material3 [OutlinedTextField] inherits `LocalTextStyle`, which
 * `MaterialTheme` sets to `bodyLarge` (16sp) — so every form field that
 * didn't pass `textStyle` silently rendered a size larger than the
 * PillTextField sitting directly beneath it. Pass this to both `textStyle`
 * and the `placeholder` slot (the placeholder doesn't pick up `textStyle`).
 */
@Composable
internal fun formFieldTextStyle() = MaterialTheme.typography.bodyMedium

@Composable
internal fun formFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
)

/** Static label above a [PillTextField], matching Figma's `labelStyle` (12sp, semibold, onSurfaceVariant). */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MaterialTheme.spacing.xs)
    )
}

/**
 * Form state holder for agent configuration.
 */
data class AgentFormState(
    val serverUrl: String = ConnectionConfig.DEFAULT_RELAY_URL,
    val agentAddress: String = "",
    val apiKey: String = "",
    val useDirectConnection: Boolean = false
) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && agentAddress.isNotBlank() &&
                !agentAddress.startsWith("wss://") && !agentAddress.startsWith("ws://") &&
                !agentAddress.startsWith("https://") && !agentAddress.startsWith("http://")

    /**
     * Whether Save should be tappable, as opposed to [isValid]'s "would this
     * pass validation".
     *
     * The two differ on purpose. Gating the button on [isValid] left anyone
     * who pasted a URL into Agent Address staring at a permanently greyed
     * Save with nothing on screen saying which field was wrong — the button
     * was the only feedback, and a disabled button says nothing. Submitting
     * instead routes the same input through `AgentViewModel.validateAgentInput`,
     * which names the actual problem ("Agent address should be a hex address
     * (0x...), not a URL") in the form's snackbar.
     *
     * So this only blocks a submission that has nothing to report on: both
     * required fields empty is self-evident from the empty fields themselves.
     */
    val isSubmittable: Boolean
        get() = serverUrl.isNotBlank() && agentAddress.isNotBlank()
}

/**
 * Reusable agent configuration form content.
 *
 * The Agent Address field's dropdown only ever lists `savedAgents` (a quick
 * pick from agents already saved on this device); finding *new* agents on a
 * relay happens on the dedicated Discover Agents screen, reached via
 * [onNavigateToDiscover], which hands the picked address back to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFormContent(
    formState: AgentFormState,
    onFormStateChange: (AgentFormState) -> Unit,
    savedAgents: List<AgentProfile> = emptyList(),
    // Carries the form's current Server URL and Agent Address along so the
    // Discover screen can prefill/search immediately and highlight whichever
    // result (if any) matches what's already typed here.
    onNavigateToDiscover: (serverUrl: String, agentAddress: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var previousMode by remember { mutableStateOf(formState.useDirectConnection) }

    LaunchedEffect(formState.useDirectConnection) {
        if (formState.useDirectConnection != previousMode) {
            previousMode = formState.useDirectConnection
            if (formState.useDirectConnection) {
                onFormStateChange(formState.copy(
                    serverUrl = "",
                    agentAddress = ""
                ))
            } else {
                onFormStateChange(formState.copy(
                    serverUrl = ConnectionConfig.DEFAULT_RELAY_URL,
                    agentAddress = ""
                ))
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
    ) {
        // Connection type toggle — custom pill pair (Figma spec: 20dp radius,
        // primary-tinted 14% fill + 1.5dp primary border when selected)
        // replacing the stock FilterChip pair.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            ModeToggleButton(
                label = "Relay Server",
                selected = !formState.useDirectConnection,
                onClick = { onFormStateChange(formState.copy(useDirectConnection = false)) },
                modifier = Modifier.weight(1f)
            )
            ModeToggleButton(
                label = "Direct Connection",
                selected = formState.useDirectConnection,
                onClick = { onFormStateChange(formState.copy(useDirectConnection = true)) },
                modifier = Modifier.weight(1f)
            )
        }

        // Server URL — static label above a fixed-height bordered box,
        // matching Figma's OutlinedField exactly (see PillTextField doc).
        Column {
            FieldLabel("Server URL")
            PillTextField(
                shape = FormFieldShape,
                value = formState.serverUrl,
                onValueChange = {
                    if (it.length <= 500) {
                        onFormStateChange(formState.copy(serverUrl = it))
                    }
                },
                placeholder = if (formState.useDirectConnection) "https://your-server.com"
                    else "https://oo.openonion.ai",
                label = "Server URL",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "${formState.serverUrl.length}/500",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.xxs)
            )
        }

        // Agent Address, with the standalone "discover" trigger that
        // navigates to the dedicated Discover Agents screen (relay mode only).
        AgentAddressField(
            value = formState.agentAddress,
            onValueChange = { onFormStateChange(formState.copy(agentAddress = it)) },
            savedAgents = savedAgents,
            useDirectConnection = formState.useDirectConnection,
            trailingIcon = if (!formState.useDirectConnection) {
                {
                    DiscoverTriggerIcon(
                        onClick = { onNavigateToDiscover(formState.serverUrl, formState.agentAddress) },
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else {
                null
            }
        )

        // Help text — placed directly under the Agent Address field (not at
        // the bottom of the form) so it reads as guidance for the address
        // input specifically, matching the Figma layout.
        Text(
            text = if (formState.useDirectConnection) {
                "Direct connection: enter your agent's server URL and address."
            } else {
                "Relay mode: enter the relay server URL and the agent's public address (0x...)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        AdvancedSettingsSection(
            apiKey = formState.apiKey,
            onApiKeyChange = { onFormStateChange(formState.copy(apiKey = it)) }
        )
    }
}

/**
 * Agent Address field: a [PillTextField] with a dropdown of [savedAgents]
 * (relay mode only — finding *new* agents on a relay happens elsewhere;
 * this dropdown is a quick-pick from agents already saved on this device),
 * plus an optional trailing slot (e.g. [DiscoverTriggerIcon]) rendered as a
 * sibling outside the dropdown anchor so it never also triggers the inline
 * picker on a single tap. Shared by [AgentFormContent] (Edit Agent) and
 * [EmbeddedDiscoveryPanel] (Onboarding / Add Agent).
 *
 * [enableDropdown] false drops the [ExposedDropdownMenuBox] entirely rather
 * than rendering an empty one — Add Agent picks from the radar above instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    savedAgents: List<AgentProfile>,
    useDirectConnection: Boolean,
    label: String = "Agent Address",
    placeholder: String = if (useDirectConnection) "0x1234..." else "0x1234... or pick a saved agent",
    trailingIcon: (@Composable () -> Unit)? = null,
    enableDropdown: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    val showDropdown = enableDropdown && !useDirectConnection

    Column(modifier = modifier) {
        FieldLabel(label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            if (showDropdown) {
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { expanded -> dropdownExpanded = expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    AgentAddressPill(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = placeholder,
                        label = label,
                        onFocusChanged = onFocusChanged,
                        trailingContent = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        // PrimaryEditable, not the no-arg overload: that one means
                        // PrimaryNotEditable, which turns the whole field into a menu
                        // toggle. This field is typed into, and the list below filters
                        // as you type, so a tap has to place a caret and open the menu.
                        anchorModifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Saved agents only — finding new ones happens on the
                    // Discover Agents screen. Live-filters by whatever's typed
                    // (name or address substring, case-insensitive) so the list
                    // narrows as you type instead of always showing every one.
                    val query = value.trim()
                    val filteredAgents = if (query.isEmpty()) {
                        savedAgents
                    } else {
                        savedAgents.filter {
                            it.name.contains(query, ignoreCase = true) ||
                                it.address.contains(query, ignoreCase = true)
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        if (filteredAgents.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (savedAgents.isEmpty()) "No saved agents yet" else "No matches",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = { dropdownExpanded = false }
                            )
                        } else {
                            filteredAgents.forEach { agent ->
                                val isSelected = value == agent.address
                                DropdownMenuItem(
                                    leadingIcon = {
                                        AgentInitialsAvatar(
                                            name = agent.name,
                                            size = 28.dp,
                                            textStyle = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    text = {
                                        Column {
                                            Text(
                                                agent.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                agent.address.truncateMiddle(10, 6),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            if (agent.description != null) {
                                                Text(
                                                    agent.description,
                                                    // Agent description reads at
                                                    // bodySmall on the agent cards too.
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        onValueChange(agent.address)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                AgentAddressPill(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    label = label,
                    onFocusChanged = onFocusChanged,
                    modifier = Modifier.weight(1f)
                )
            }
            trailingIcon?.invoke()
        }
    }
}

/** The address input itself, so the with- and without-dropdown branches share one field. */
@Composable
private fun AgentAddressPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    anchorModifier: Modifier = Modifier
) {
    PillTextField(
        shape = FormFieldShape,
        value = value,
        onValueChange = { onValueChange(it.take(200)) },
        placeholder = placeholder,
        label = label,
        monospace = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next
        ),
        trailingContent = trailingContent,
        anchorModifier = anchorModifier,
        onFocusChanged = onFocusChanged,
        modifier = modifier
    )
}

/**
 * Collapsible "Advanced Settings" card (API Key only, for now) — shared by
 * [AgentFormContent] (Edit Agent's classic form) and [EmbeddedDiscoveryPanel]
 * (Onboarding / Add Agent's discovery-first flow), so both surfaces render
 * an identical accordion instead of two copies of the same ~40 lines.
 */
@Composable
fun AdvancedSettingsSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdvanced by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    val advancedShape = if (showAdvanced) {
        RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    } else {
        RoundedCornerShape(12.dp)
    }
    val chevronRotation by animateFloatAsState(if (showAdvanced) 180f else 0f, label = "advancedChevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(advancedShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { showAdvanced = !showAdvanced }
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Advanced Settings", style = MaterialTheme.typography.bodyLarge)
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (showAdvanced) "Collapse" else "Expand",
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
            ) {
                Column {
                    FieldLabel("API Key")
                    PillTextField(
                        shape = FormFieldShape,
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        placeholder = "Enter API key if required",
                        label = "API Key",
                        visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingContent = {
                            // No explicit size: default 48dp touch target
                            // fits comfortably inside the pill's own
                            // heightIn(min = 52.dp).
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide API key" else "Show API key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Discover-agents trigger docked in the Agent Address field's trailing slot:
 * a 32dp primary-tinted rounded-square icon button (search icon, or a small
 * spinner while a discovery request is in flight), matching the Figma
 * design's standalone circular "Discover agents" button next to this field —
 * kept as an inline dropdown trigger rather than Figma's separate-screen
 * navigation so picking a result still fills this field directly.
 *
 * Needs its own explicit [onClick]: unlike [ExposedDropdownMenuDefaults.TrailingIcon],
 * a custom trailing composable isn't recognized by [ExposedDropdownMenuBox]'s
 * internal tap detection, so swapping in this icon without a click handler
 * silently disabled opening the dropdown once the field already had focus.
 */
@Composable
internal fun DiscoverTriggerIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(FormFieldShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)), FormFieldShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Discover agents",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Custom pill toggle button (20dp radius, primary-tinted 14% fill + 1.5dp
 * primary border when selected) used for the Relay/Direct connection-mode
 * choice, replacing the stock [FilterChip] pair to match the Figma design's
 * mode toggle.
 */
@Composable
internal fun ModeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .border(BorderStroke(1.5.dp, borderColor), MaterialTheme.shapes.large)
            // Relay/Direct is a mutually exclusive pair, so RadioButton — not
            // Role.Tab, since picking one doesn't switch panels within this
            // control, it drives the rest of the form below.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
