package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The tool-approval mode control that sits above the chat input.
 *
 * Two affordances, both Android-native, in place of the web client's
 * hover-tooltip + ⇧Tab + dropdown:
 *  - tapping the chip cycles the three base modes ([ApprovalMode.CYCLE]);
 *  - the trailing tune button opens [ApprovalModeSheet], the only way into ULW.
 *
 * The modes are told apart by icon, label and weight, never by hue. The one
 * colored state is ULW: the whole chip turns `error` because the agent is
 * running every tool without asking, and nothing else in this control may
 * compete with that signal. While ULW is active the chip stops cycling and
 * offers exit only.
 *
 * [pending] marks a request the agent hasn't confirmed yet — dimmed content
 * plus a small static sync glyph, no new hue. Static rather than an
 * indeterminate spinner: this chip can sit unconfirmed for a whole turn (see
 * [ai.openonion.oochat.data.repository.ConnectionRepository.modePending]),
 * long past the point a spinner would read as broken rather than busy.
 *
 * The chip used to go inert while the field had focus, to stop a mistap now
 * that it sits in the thumb's path to the keyboard. That cost the user the
 * ability to change mode *while composing the message that needs the new mode*,
 * which is when they want it; the reference client locks nothing. Mistap
 * protection is now the confirmation the destructive direction already needs —
 * ULW is only reachable through [ApprovalModeSheet], never by tapping the chip.
 */
@Composable
fun ApprovalModeChip(
    mode: ApprovalMode,
    enabled: Boolean,
    onCycle: () -> Unit,
    onOpenSheet: () -> Unit,
    pending: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUlw = mode == ApprovalMode.ULW

    val baseContentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        isUlw -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contentColor = if (pending) baseContentColor.copy(alpha = baseContentColor.alpha * 0.7f) else baseContentColor
    val containerColor =
        if (isUlw) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceContainerHighest

    Row(
        modifier = modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onCycle,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            color = containerColor,
            contentColor = contentColor,
            border = if (isUlw) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)) else null
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
            ) {
                Icon(
                    imageVector = mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isUlw) "${mode.shortLabel} · exit" else mode.shortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (pending) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Mode change pending",
                        modifier = Modifier.size(12.dp),
                        tint = contentColor
                    )
                }
            }
        }

        Spacer(Modifier.size(MaterialTheme.spacing.xxs))

        // A visible control rather than a long-press: ULW is the one thing
        // behind this sheet, and a gesture with no affordance would hide it.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onOpenSheet),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Approval mode options",
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * The full mode picker. The three base modes are plain rows; ULW is below a
 * divider in its own danger-styled block with an editable turn budget,
 * because it is the one mode that hands the agent every tool with no
 * approval gate.
 *
 * The budget is a field, not the reference client's hardcoded 100
 * (mode-switcher.tsx:147): the number is how long the agent runs unattended,
 * so it is exactly the part the user should be choosing.
 *
 * Its own [ModalBottomSheet] rather than the shared ConfirmationSheet: this
 * one opens fully expanded and scrolls, because the ULW block — the whole
 * reason the sheet exists — sits at the bottom and must never land below the
 * fold on a short screen or at a large font scale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalModeSheet(
    current: ApprovalMode,
    onSelect: (ApprovalMode, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var turnsText by remember { mutableStateOf(ULW_DEFAULT_TURNS.toString()) }
    val turns = turnsText.toIntOrNull()
    val turnsValid = turns != null && turns > 0
    val isUlw = current == ApprovalMode.ULW

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.lg)
                .padding(bottom = MaterialTheme.spacing.xl)
        ) {
            Text(
                text = "Approval mode",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "How much the agent may do before asking you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaterialTheme.spacing.sm))

            ApprovalMode.CYCLE.forEach { mode ->
                ModeRow(
                    mode = mode,
                    selected = mode == current,
                    onClick = {
                        onSelect(mode, null)
                        onDismiss()
                    }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = MaterialTheme.spacing.md),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            UlwBlock(
                active = isUlw,
                turnsText = turnsText,
                onTurnsChange = { input -> turnsText = input.filter(Char::isDigit).take(4) },
                turnsValid = turnsValid,
                onStart = {
                    onSelect(ApprovalMode.ULW, turns)
                    onDismiss()
                },
                onExit = {
                    onSelect(ApprovalMode.SAFE, null)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ModeRow(mode: ApprovalMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            // A mutually exclusive pick among the base modes, same as radio
            // buttons in a group — selected carries the checked state TalkBack
            // needs, which plain Role.RadioButton on .clickable never set.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        Icon(
            imageVector = mode.icon(),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Current mode",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun UlwBlock(
    active: Boolean,
    turnsText: String,
    onTurnsChange: (String) -> Unit,
    turnsValid: Boolean,
    onStart: () -> Unit,
    onExit: () -> Unit
) {
    val error = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .background(error.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(MaterialTheme.spacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.RocketLaunch,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = error
            )
            Text(
                text = ApprovalMode.ULW.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = error
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = error
            )
            Text(
                text = "Edits files and runs commands without asking, until the turn " +
                    "budget runs out. Only start this if you are willing to leave the " +
                    "agent unattended.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.md))

        if (active) {
            Button(
                onClick = onExit,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = error.copy(alpha = 0.16f),
                    contentColor = error
                )
            ) {
                Text("Exit to safe mode")
            }
        } else {
            OutlinedTextField(
                value = turnsText,
                onValueChange = onTurnsChange,
                label = { Text("Turns") },
                supportingText = {
                    Text(
                        if (turnsValid) "Pauses for you after this many turns."
                        else "Enter a turn budget of 1 or more."
                    )
                },
                isError = !turnsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                // The field's focused accent defaults to `primary`, which puts
                // a second hue inside the one block that is meant to read as a
                // single danger signal. Re-tinted to `error` so nothing in here
                // competes with it.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = error,
                    focusedLabelColor = error,
                    cursorColor = error
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Button(
                onClick = onStart,
                enabled = turnsValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = error.copy(alpha = 0.16f),
                    contentColor = error
                )
            ) {
                Text("Start ultra work")
            }
        }
    }
}

/**
 * The reference default (ulw.py's `ULW_DEFAULT_TURNS`, and what the server
 * falls back to when the frame omits `turns`). Only the field's initial
 * value here — the user picks the real number.
 */
private const val ULW_DEFAULT_TURNS = 100

private val ApprovalMode.shortLabel: String
    get() = when (this) {
        ApprovalMode.SAFE -> "safe"
        ApprovalMode.PLAN -> "plan"
        ApprovalMode.ACCEPT_EDITS -> "accept edits"
        ApprovalMode.ULW -> "ultra"
    }

private val ApprovalMode.label: String
    get() = when (this) {
        ApprovalMode.SAFE -> "Safe"
        ApprovalMode.PLAN -> "Plan"
        ApprovalMode.ACCEPT_EDITS -> "Accept edits"
        ApprovalMode.ULW -> "Ultra work"
    }

private val ApprovalMode.description: String
    get() = when (this) {
        ApprovalMode.SAFE -> "Ask before file edits and commands"
        ApprovalMode.PLAN -> "Research first, then approve a plan"
        ApprovalMode.ACCEPT_EDITS -> "Edit files without asking"
        ApprovalMode.ULW -> "Fully autonomous for a set number of turns"
    }

private fun ApprovalMode.icon(): ImageVector = when (this) {
    ApprovalMode.SAFE -> Icons.Default.Shield
    ApprovalMode.PLAN -> Icons.Default.Assignment
    ApprovalMode.ACCEPT_EDITS -> Icons.Default.Bolt
    ApprovalMode.ULW -> Icons.Default.RocketLaunch
}

@Preview(showBackground = true)
@Composable
private fun ApprovalModeChipPreview() {
    ConnectOnionTheme {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            ApprovalMode.entries.forEach { mode ->
                ApprovalModeChip(mode = mode, enabled = true, onCycle = {}, onOpenSheet = {})
            }
        }
    }
}
