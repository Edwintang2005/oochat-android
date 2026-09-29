package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * P1 non-interactive status cards: [IntentCard], [EvalCard], [CompactDivider],
 * [ToolBlockedCard], [FilesReceivedCard], [DiffPreviewCard]. All render a
 * single wire event (or an id-upserted sequence of them, for Intent/Eval/
 * Compact) with no outbound response — the interactive PlanReview/
 * UlwTurnsReached cards live separately since they carry response callbacks.
 */
@Composable
internal fun IntentCard(item: ChatItem.IntentItem) {
    StatusPill(
        leading = {
            if (item.status == IntentStatus.ANALYZING) {
                StatusIcon(status = IndicatorStatus.RUNNING, size = 14.dp)
            } else {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = item.ack?.takeIf { it.isNotBlank() } ?: when (item.status) {
            IntentStatus.ANALYZING -> "Analyzing intent…"
            IntentStatus.UNDERSTOOD -> "Understood"
        },
        trailingText = "· build".takeIf { item.isBuild == true }
    )
}

@Composable
internal fun EvalCard(item: ChatItem.EvalItem) {
    // Bare row, matching [IntentCard] — same status tier. Containers are
    // reserved for the interactive gates below.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            when {
                item.status == EvalStatus.EVALUATING -> StatusIcon(status = IndicatorStatus.RUNNING)
                item.passed == true -> StatusIcon(
                    status = IndicatorStatus.DONE,
                    doneContentDescription = "Passed"
                )
                item.passed == false -> StatusIcon(
                    status = IndicatorStatus.ERROR,
                    errorIcon = Icons.Default.Cancel,
                    errorContentDescription = "Failed"
                )
                // Neither evaluating nor passed/failed yet decided — a 4th,
                // neutral state with no RUNNING/DONE/ERROR equivalent, so
                // it's left as a direct Icon call rather than forced into
                // StatusIcon.
                else -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        item.status == EvalStatus.EVALUATING -> "Evaluating…"
                        item.summary?.isNotBlank() == true -> item.summary
                        item.passed == true -> "Eval passed"
                        item.passed == false -> "Eval failed"
                        else -> "Eval complete"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (!item.expected.isNullOrBlank()) {
                    Text(
                        text = "Expected: ${item.expected}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!item.evalPath.isNullOrBlank()) {
                    MonoText(text = item.evalPath, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
internal fun CompactDivider(item: ChatItem.CompactItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Compress,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (item.status == CompactStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        )
        Text(
            text = when (item.status) {
                CompactStatus.COMPACTING -> "Compacting context…"
                CompactStatus.DONE -> {
                    val before = item.contextBefore
                    val after = item.contextAfter
                    if (before != null && after != null) {
                        // Rounded to match the server's own display convention
                        // (auto_compact.py logs "{percent:.0f}%").
                        "Context compacted: ${before.roundToInt()}% → ${after.roundToInt()}%"
                    } else {
                        item.message?.takeIf { it.isNotBlank() } ?: "Context compacted"
                    }
                }
                CompactStatus.ERROR -> item.error?.takeIf { it.isNotBlank() } ?: "Compaction failed"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (item.status == CompactStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = MaterialTheme.spacing.xs)
        )
    }
}

/**
 * Inline status marker for a `mode_changed` server event — same "thin
 * divider running through the chat flow" shape as [CompactDivider], since
 * this too is a one-shot notification about the agent's own state, not
 * something the user answers.
 */
@Composable
internal fun ModeChangedDivider(item: ChatItem.ModeChangedItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            text = buildString {
                append("Switched to ${modeLabel(item.mode)}")
                triggeredBySource(item.triggeredBy)?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = MaterialTheme.spacing.xs)
        )
    }
}

/** Human label for a `mode_changed` frame's raw `mode` string. */
private fun modeLabel(mode: String): String = when (mode) {
    "safe" -> "safe mode"
    "plan" -> "plan mode"
    "accept_edits" -> "accept-edits mode"
    "ulw" -> "ULW mode"
    else -> "$mode mode"
}

/** Human label for a `mode_changed` frame's raw `triggered_by` string, or
 * null when the field was absent (e.g. plan_mode.py's exit path). */
private fun triggeredBySource(triggeredBy: String?): String? = when (triggeredBy) {
    null -> null
    "user" -> "you"
    "agent" -> "agent"
    "ulw_checkpoint" -> "checkpoint"
    else -> triggeredBy
}

@Composable
internal fun ToolBlockedCard(item: ChatItem.ToolBlockedItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.warningContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = MaterialTheme.statusColors.warning,
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.tool,
                    // Chat card title level, same as the other status cards.
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.statusColors.warning
                )
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.command.isNullOrBlank()) {
                    MonoText(
                        text = item.command,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.xxs)
                    )
                }
            }
        }
    }
}

@Composable
internal fun FilesReceivedCard(item: ChatItem.FilesReceivedItem) {
    // A passive notice, not a gate — no container, same as [EvalCard].
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.sm)) {
            Text(
                text = "Received ${item.files.size} file${if (item.files.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            item.files.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
                        MonoText(text = file.path, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/**
 * Preview of a pending file write's unified diff, sent by diff_writer.py
 * right before the paired [ChatItem.ApprovalNeeded]/[ChatItem.AskUser]
 * request for the same write (see DiffWriter._send_preview). Without this
 * card the user approves that request blind to what it actually changes.
 *
 * The diff body is collapsed by default (same [ExpandableSection] pattern as
 * the tool cards in [ToolCallCards.kt]) — a large write's preview can run to
 * the configured `preview_limit` (2000 chars server-side), which would
 * otherwise dominate the chat stream.
 */
@Composable
internal fun DiffPreviewCard(item: ChatItem.DiffPreviewItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ExpandableSection(
            showToggle = item.preview.isNotBlank(),
            previewContent = { expanded ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Default.Difference,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.path.truncateMiddle(prefix = 20, suffix = 16),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(if (item.fileExists) "Preview" else "New file")
                                if (item.truncated) append(" · Preview truncated")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.preview.isNotBlank()) {
                        ExpandChevron(expanded = expanded)
                    }
                }
            },
            expandedContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MaterialTheme.spacing.md)
                ) {
                    item.preview.lines().forEach { line ->
                        val color = when {
                            line.startsWith("+") -> MaterialTheme.statusColors.success
                            line.startsWith("-") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        MonoText(text = line, color = color)
                    }
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private enum class PlanReviewOutcome { APPROVED, REJECTED }

/**
 * Interactive checkpoint gate for a `plan_review` event. Approve forwards the
 * plan verbatim with a fixed instruction prefix; reject opens an inline
 * feedback field. Both paths byte-match web's plan-card.tsx handleApprove/
 * handleReject message strings so the agent-side prompt parsing stays intact.
 */
@Composable
internal fun PlanReviewCard(
    item: ChatItem.PlanReviewItem,
    onPlanReviewResponse: (String) -> Unit
) {
    var outcome by remember(item.id) { mutableStateOf<PlanReviewOutcome?>(null) }
    var rejecting by remember(item.id) { mutableStateOf(false) }
    var feedback by remember(item.id) { mutableStateOf("") }

    val decided = outcome
    if (decided != null) {
        StatusPill(
            leading = {
                StatusIcon(
                    status = if (decided == PlanReviewOutcome.APPROVED) IndicatorStatus.DONE else IndicatorStatus.ERROR,
                    size = 14.dp,
                    errorIcon = Icons.Default.Cancel
                )
            },
            text = if (decided == PlanReviewOutcome.APPROVED) "Plan approved" else "Plan rejected"
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // Same decision-gate tier as ApprovalCard — above the agent bubble.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md2)) {
            Text(
                text = "Plan Review",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = item.planContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            if (rejecting) {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    label = { Text("Feedback") },
                    // Input level = bodyMedium, matching this card's body copy
                    // instead of the bodyLarge a bare field would inherit.
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                    TextButton(
                        onClick = { rejecting = false; feedback = "" },
                        modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            outcome = PlanReviewOutcome.REJECTED
                            onPlanReviewResponse("Plan rejected. Revise with write_plan(). Feedback: $feedback")
                        },
                        modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
                    ) {
                        Text("Send")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                    // Text, matching this card's other branch — an outline next
                    // to the filled Approve read as a second primary.
                    TextButton(
                        onClick = { rejecting = true },
                        modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                        Text("Reject")
                    }
                    Button(
                        onClick = {
                            outcome = PlanReviewOutcome.APPROVED
                            onPlanReviewResponse(
                                "Plan approved. Implement now. Do NOT re-enter plan mode.\n\n---\n\n${item.planContent}"
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = ButtonToken.Compact)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                        Text("Approve")
                    }
                }
            }
        }
    }
}

/**
 * Interactive checkpoint gate for a `ulw_turns_reached` event. Web's real
 * ChatUlwCheckpoint offers exactly these 3 choices — no "Stop" button.
 */
@Composable
internal fun UlwTurnsReachedCard(
    item: ChatItem.UlwTurnsReachedItem,
    onUlwResponse: (action: String, turns: Int?, mode: String?) -> Unit
) {
    var submitted by remember(item.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.warningContainer)
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md2)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.statusColors.warning,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Turn limit reached",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.statusColors.warning
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            Text(
                text = "${item.turnsUsed} of ${item.maxTurns} turns used in this run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            if (submitted) {
                Text(
                    text = "Choice sent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(
                    onClick = { submitted = true; onUlwResponse("continue", 100, null) },
                    // 48dp touch target — was 44dp; full-width, so the 4dp
                    // grow has no layout fallout.
                    modifier = Modifier.fillMaxWidth().height(ButtonToken.Compact)
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Continue (+100 turns)")
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                // Text so the filled button above stays the single lead.
                TextButton(
                    onClick = { submitted = true; onUlwResponse("switch_mode", null, "accept_edits") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Switch to Accept Edits")
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                TextButton(
                    onClick = { submitted = true; onUlwResponse("switch_mode", null, "safe") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text("Switch to Safe Mode")
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun IntentCardAnalyzingPreview() {
    ConnectOnionTheme {
        IntentCard(ChatItem.IntentItem(id = "intent-1", status = IntentStatus.ANALYZING))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun IntentCardUnderstoodPreview() {
    ConnectOnionTheme {
        IntentCard(
            ChatItem.IntentItem(
                id = "intent-2",
                status = IntentStatus.UNDERSTOOD,
                ack = "Got it — refactoring the auth flow",
                isBuild = true
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EvalCardEvaluatingPreview() {
    ConnectOnionTheme {
        EvalCard(ChatItem.EvalItem(id = "eval-1", status = EvalStatus.EVALUATING))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EvalCardPassedPreview() {
    ConnectOnionTheme {
        EvalCard(
            ChatItem.EvalItem(
                id = "eval-2",
                status = EvalStatus.DONE,
                passed = true,
                summary = "All 12 checks passed",
                evalPath = "evals/auth_flow.yaml"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EvalCardFailedPreview() {
    ConnectOnionTheme {
        EvalCard(
            ChatItem.EvalItem(
                id = "eval-3",
                status = EvalStatus.DONE,
                passed = false,
                expected = "HTTP 200",
                evalPath = "evals/api_status.yaml"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CompactDividerCompactingPreview() {
    ConnectOnionTheme {
        CompactDivider(ChatItem.CompactItem(id = "compact-1", status = CompactStatus.COMPACTING))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CompactDividerDonePreview() {
    ConnectOnionTheme {
        CompactDivider(
            ChatItem.CompactItem(id = "compact-2", status = CompactStatus.DONE, contextBefore = 92.0, contextAfter = 34.0)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CompactDividerErrorPreview() {
    ConnectOnionTheme {
        CompactDivider(
            ChatItem.CompactItem(id = "compact-3", status = CompactStatus.ERROR, error = "Compaction timed out")
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ModeChangedDividerAgentPreview() {
    ConnectOnionTheme {
        ModeChangedDivider(ChatItem.ModeChangedItem(id = "mode-1", mode = "plan", triggeredBy = "agent"))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ModeChangedDividerNoSourcePreview() {
    ConnectOnionTheme {
        ModeChangedDivider(ChatItem.ModeChangedItem(id = "mode-2", mode = "safe"))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ToolBlockedCardPreview() {
    ConnectOnionTheme {
        ToolBlockedCard(
            ChatItem.ToolBlockedItem(
                id = "blocked-1",
                tool = "bash",
                reason = "policy_denied",
                message = "This command is not allowed in safe mode.",
                command = "rm -rf /tmp/cache"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FilesReceivedCardPreview() {
    ConnectOnionTheme {
        FilesReceivedCard(
            ChatItem.FilesReceivedItem(
                id = "files-1",
                files = listOf(
                    ReceivedFile(name = "report.pdf", path = "/outputs/report.pdf"),
                    ReceivedFile(name = "chart.png", path = "/outputs/chart.png")
                )
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DiffPreviewCardExistingFilePreview() {
    ConnectOnionTheme {
        DiffPreviewCard(
            ChatItem.DiffPreviewItem(
                id = "diff-1",
                path = "src/main/java/ai/openonion/oochat/util/StringFormat.kt",
                preview = "--- a/StringFormat.kt\n+++ b/StringFormat.kt\n@@ -1,3 +1,4 @@\n" +
                    " package ai.openonion.oochat.util\n" +
                    "-fun truncateMiddle(s: String) = s\n" +
                    "+fun truncateMiddle(s: String, prefix: Int, suffix: Int) = s",
                truncated = false,
                fileExists = true
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DiffPreviewCardNewFileTruncatedPreview() {
    ConnectOnionTheme {
        DiffPreviewCard(
            ChatItem.DiffPreviewItem(
                id = "diff-2",
                path = "src/main/java/ai/openonion/oochat/util/NewFile.kt",
                preview = "+ package ai.openonion.oochat.util\n+ fun greet() = \"hi\"",
                truncated = true,
                fileExists = false
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PlanReviewCardPreview() {
    ConnectOnionTheme {
        PlanReviewCard(
            ChatItem.PlanReviewItem(
                id = "plan-1",
                planContent = "1. Add DiffUtil.kt\n2. Add GrepResultParser.kt\n3. Wire up the 4 P0 tool cards"
            ),
            onPlanReviewResponse = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun UlwTurnsReachedCardPreview() {
    ConnectOnionTheme {
        UlwTurnsReachedCard(
            ChatItem.UlwTurnsReachedItem(id = "ulw-1", turnsUsed = 100, maxTurns = 100),
            onUlwResponse = { _, _, _ -> }
        )
    }
}
