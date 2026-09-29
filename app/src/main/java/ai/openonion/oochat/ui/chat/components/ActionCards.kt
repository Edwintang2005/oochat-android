package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.BatchToolPreview
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.summarizeToolCall
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun AskUserCard(
    item: ChatItem.AskUser,
    onRespond: (String) -> Unit
) {
    var submitted by remember(item.id) { mutableStateOf(false) }

    // No Card: the option buttons already carry the weight. [ApprovalCard]
    // keeps its card — that one is a decision gate, this is a question.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
        item.options.forEachIndexed { index, option ->
            val onClick = {
                submitted = true
                onRespond(option)
            }
            // One filled button, not N. No "recommended" flag exists on the
            // wire, so first-listed leads and the rest are peers of it.
            // Material's default Button/TextButton min height is 40dp, not
            // 48dp — heightIn keeps it a floor so long option text can still
            // wrap taller.
            if (index == 0) {
                Button(
                    onClick = onClick,
                    enabled = !submitted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) {
                    Text(option)
                }
            } else {
                TextButton(
                    onClick = onClick,
                    enabled = !submitted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) {
                    Text(option)
                }
            }
        }
    }
}

/**
 * No riskLevel field exists on the wire protocol, so the Figma badge/coloring is dropped. Buttons instead port oo-chat-web's approval-buttons.tsx: two allow tiers, three reject modes matching APPROVAL_RESPONSE.scope/mode.
 */
private enum class ApprovalOutcome { APPROVED, APPROVED_SESSION, SKIPPED, STOPPED }

/** The wire-level answer, mapped to the confirmation copy shown in its place. */
private fun ApprovalDecision.toOutcome(): ApprovalOutcome = when {
    approved && scope == "session" -> ApprovalOutcome.APPROVED_SESSION
    approved -> ApprovalOutcome.APPROVED
    mode == "reject_hard" -> ApprovalOutcome.STOPPED
    else -> ApprovalOutcome.SKIPPED // reject_soft, reject_explain
}

@Composable
internal fun ApprovalCard(
    item: ChatItem.ApprovalNeeded,
    onApprove: (approved: Boolean, scope: String, mode: String?, feedback: String?) -> Unit
) {
    // What the user is actually approving — the real gap found on device:
    // "Allowed: edit — running…" with no file, no command, no way to
    // recall the decision after the fact. See summarizeToolCall's own doc.
    val argsSummary = remember(item.id) { summarizeToolCall(item.tool, item.arguments) }

    // Once answered the card becomes a read-only record of what was decided —
    // it stays in the transcript, it just stops offering the buttons.
    item.decision?.let {
        ApprovalConfirmationBar(tool = item.tool, outcome = it.toOutcome(), argsSummary = argsSummary)
        return
    }

    fun decide(approved: Boolean, scope: String, mode: String?, feedback: String? = null) {
        onApprove(approved, scope, mode, feedback)
    }

    // Reject is the only path that opens an inline reason field — Stop and
    // Explain are already a complete statement on their own (redirect now /
    // asking to have this explained), so a second prompt for "why" would be
    // asking the user to justify themselves twice. null = the button row;
    // non-null = the button just tapped, showing the feedback field for it.
    var pendingRejectMode by remember(item.id) { mutableStateOf<String?>(null) }
    var rejectFeedback by remember(item.id) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // A tier above the agent bubble's surfaceContainer, plus a primary
        // hairline: this one is waiting on the user, not just talking to them.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md2)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(
                    text = item.tool,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.xs,
                        vertical = MaterialTheme.spacing.xxs
                    )
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = item.description?.takeIf { it.isNotBlank() } ?: item.tool,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // The description above is often just a generic verb ("Edit a
            // file"); this is the concrete value — which file, which
            // command — the user actually needs to decide on.
            if (argsSummary != null) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxs))
                Text(
                    text = argsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Waiting for your decision",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            if (pendingRejectMode != null) {
                RejectFeedbackField(
                    feedback = rejectFeedback,
                    onFeedbackChange = { rejectFeedback = it },
                    onCancel = {
                        pendingRejectMode = null
                        rejectFeedback = ""
                    },
                    onSend = {
                        decide(false, "once", pendingRejectMode, rejectFeedback.trim().ifBlank { null })
                    }
                )
            } else {
                Button(
                    onClick = { decide(true, "once", null) },
                    shape = MaterialTheme.shapes.small,
                    // 48dp touch target — was 44dp; full-width, so the 4dp grow
                    // has no layout fallout.
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.Compact)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Allow once")
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                TextButton(
                    onClick = { decide(true, "session", null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Trust ${item.tool} for this session")
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
                    RejectActionButton(
                        icon = Icons.Default.Cancel,
                        label = "Reject",
                        // Only Reject opens the reason field — Stop/Explain
                        // already say why on their own (see the field above).
                        onClick = { pendingRejectMode = "reject_soft" },
                        modifier = Modifier.weight(1f)
                    )
                    RejectActionButton(
                        icon = Icons.Default.Stop,
                        label = "Stop",
                        onClick = { decide(false, "once", "reject_hard") },
                        modifier = Modifier.weight(1f)
                    )
                    RejectActionButton(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        label = "Explain",
                        onClick = { decide(false, "once", "reject_explain") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!item.batchRemaining.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
                ApprovalBatchPreview(item.batchRemaining)
            }
        }
    }
}

/**
 * Inline reason field the Reject button opens, in place of the button row —
 * same swap-in-place pattern as [PlanReviewCard]'s own feedback field. A
 * single line, not multi-line: this is a short "why", not a plan critique.
 */
@Composable
private fun RejectFeedbackField(
    feedback: String,
    onFeedbackChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = feedback,
            onValueChange = onFeedbackChange,
            label = { Text("Reason (optional)") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onSend,
                modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
            ) {
                Text("Reject")
            }
        }
    }
}

@Composable
private fun RejectActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonToken.Compact),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = MaterialTheme.spacing.xs)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxs))
            // Button level, same as this card's "Allow once" / "Trust …".
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Preview of queued follow-up tool calls in the same approval batch — ports
 * approval-buttons.tsx's "Up Next (N)" list, including its getToolSummary
 * heuristic for picking a representative argument to show per tool kind.
 */
@Composable
private fun ApprovalBatchPreview(items: List<BatchToolPreview>) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs)) {
            Text(
                text = "UP NEXT (${items.size})",
                style = MaterialTheme.typography.sectionLabel,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxs))
            items.forEach { preview ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.xxs)
                ) {
                    Text(
                        text = preview.tool,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = summarizeBatchTool(preview),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private val batchArgsJson = Json { ignoreUnknownKeys = true }

private fun summarizeBatchTool(preview: BatchToolPreview): String {
    val baseTool = preview.tool.substringBefore(':')
    return try {
        val obj = batchArgsJson.parseToJsonElement(preview.arguments).jsonObject
        when (baseTool) {
            "bash", "shell", "run", "run_background" -> obj["command"]?.jsonPrimitive?.contentOrNull
            "write", "edit", "read" -> obj["file_path"]?.jsonPrimitive?.contentOrNull ?: obj["path"]?.jsonPrimitive?.contentOrNull
            "send_email" -> obj["to"]?.jsonPrimitive?.contentOrNull
            else -> obj.values.firstOrNull { it is JsonPrimitive && it.isString }?.jsonPrimitive?.contentOrNull
        } ?: ""
    } catch (e: Exception) {
        preview.arguments
    }
}

/**
 * Collapsed single-line state an [ApprovalCard] replaces itself with once
 * the user decides. The TSX reference auto-removes the whole card ~1.2s
 * later; this app keeps chat-history items in place (no removal channel
 * exists for a delivered ChatItem, same as AskUserCard/OnboardGateCard), so
 * the bar simply stays as the permanent record of the decision instead.
 */
@Composable
private fun ApprovalConfirmationBar(tool: String, outcome: ApprovalOutcome, argsSummary: String?) {
    val (icon, tint, baseText) = when (outcome) {
        ApprovalOutcome.APPROVED -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Allowed: $tool — running…"
        )
        ApprovalOutcome.APPROVED_SESSION -> Triple(
            Icons.Default.VerifiedUser,
            MaterialTheme.colorScheme.primary,
            "Session authorized: $tool — running…"
        )
        ApprovalOutcome.SKIPPED -> Triple(
            Icons.Default.Cancel,
            MaterialTheme.colorScheme.outline,
            "Rejected: $tool"
        )
        ApprovalOutcome.STOPPED -> Triple(
            Icons.Default.Block,
            MaterialTheme.colorScheme.error,
            "Execution stopped"
        )
    }
    // The receipt this leaves behind is otherwise indistinguishable from
    // every other "Allowed: edit" in the transcript — this is the only
    // record of what was actually approved once the decision has scrolled
    // past the tool's own card.
    val text = if (argsSummary != null) "$baseText  ·  $argsSummary" else baseText
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        }
    }
}
