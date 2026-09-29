package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.summarizeToolCall
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ThinkingBubble(item: ChatItem.Thinking, timestamp: String? = null, onRetry: (() -> Unit)? = null) {
    // A failure carries a whole sentence, which stretched StatusPill — sized
    // for "Thinking…"/"Done" — into the tallest, heaviest thing on screen, and
    // with no time on it a long-past connection error read as a live one.
    // Rendered as an inline status line instead, matching TurnThinkingFooter.
    if (item.status == ThinkingStatus.ERROR) {
        FailedStatusLine(item, timestamp, onRetry)
        return
    }
    // The done line carries the same trim as TurnThinkingFooter — no "Done",
    // no model name — so a standalone bubble and a turn footer read alike.
    val doneNumbers = turnFooterLabel(item).takeIf { it.isNotEmpty() }
    StatusPill(
        leading = {
            StatusIcon(
                status = when (item.status) {
                    ThinkingStatus.RUNNING -> IndicatorStatus.RUNNING
                    ThinkingStatus.DONE -> IndicatorStatus.DONE
                    ThinkingStatus.ERROR -> IndicatorStatus.ERROR
                },
                size = 14.dp,
                // A bare stroked tick in the outline colour, per the design:
                // ambient status, not a green announcement.
                doneIcon = Icons.Default.Check,
                doneTint = MaterialTheme.colorScheme.outline,
                // A checkmark reads as success even when tinted red, so ERROR uses Cancel (X) instead, matching the icon used elsewhere for failed/rejected states.
                errorIcon = Icons.Default.Cancel,
                errorTint = MaterialTheme.colorScheme.error
            )
        },
        text = when (item.status) {
            ThinkingStatus.RUNNING -> "Generating…"
            ThinkingStatus.DONE ->
                item.content?.takeIf { it.isNotBlank() } ?: doneNumbers ?: "Done"
            ThinkingStatus.ERROR ->
                item.content?.takeIf { it.isNotBlank() } ?: "Error"
        },
        trailingText = when (item.status) {
            // Already spent as the line's own text unless a message took that slot.
            ThinkingStatus.DONE -> doneNumbers?.takeIf { !item.content.isNullOrBlank() }
            else -> buildString {
                item.model?.takeIf { it.isNotBlank() }?.let { append("· $it") }
                item.tokensTotal?.let {
                    if (isNotEmpty()) append(" ")
                    append("· ${formatTokenCount(it)} tokens")
                }
            }.takeIf { it.isNotEmpty() }
        },
        trailingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // No context reading here any more either — the session usage strip on
        // the input bar is the one place context is surfaced.
        trailingAction = if (item.status == ThinkingStatus.ERROR && onRetry != null) {
            { TextButton(onClick = onRetry) { Text("Retry") } }
        } else null
    )
}

/**
 * A failed turn, as an inline status line rather than a pill: same shape as
 * [TurnThinkingFooter], since both say "here is how that turn ended". The
 * headline carries the time so a failure read months later is obviously an
 * old one; the message itself sits underneath in body text, where a long
 * sentence belongs.
 */
@Composable
private fun FailedStatusLine(
    item: ChatItem.Thinking,
    timestamp: String?,
    onRetry: (() -> Unit)?
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            StatusIcon(
                status = IndicatorStatus.ERROR,
                size = 10.dp,
                runningSize = 10.dp,
                strokeWidth = 1.5.dp,
                errorIcon = Icons.Default.Cancel,
                errorTint = MaterialTheme.colorScheme.error
            )
            Text(
                text = buildString {
                    append("Failed")
                    item.model?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    timestamp?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        item.content?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Indented under the headline text, clear of the icon.
                modifier = Modifier.padding(start = MaterialTheme.spacing.md)
            )
        }
    }
}

/**
 * Fallback rendering for a tool with no dedicated card — see
 * [ai.openonion.oochat.ui.chat.components.ToolCallDispatch].
 *
 * A [StatusPill] like its sibling [ThinkingBubble]. As the *fallback*, a card
 * here gave unrecognised tools more weight than the ones with real cards.
 */
@Composable
internal fun ToolCallBubble(item: ChatItem.ToolCall) {
    // What are we even looking at: the fallback used to show only the raw
    // tool name, so an unrecognised tool call was as blind to approve/read
    // as a bare "edit" with no file, or a bare "bash" with no command — see
    // summarizeToolCall's own doc.
    val argsSummary = summarizeToolCall(item.name, item.args)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        StatusPill(
            leading = {
                StatusIcon(
                    status = when (item.status) {
                        ToolStatus.RUNNING -> IndicatorStatus.RUNNING
                        ToolStatus.DONE -> IndicatorStatus.DONE
                        ToolStatus.ERROR -> IndicatorStatus.ERROR
                    },
                    size = 14.dp,
                    doneIcon = Icons.Default.CheckCircle,
                    doneTint = MaterialTheme.colorScheme.primary,
                    errorIcon = Icons.Default.Cancel,
                    errorTint = MaterialTheme.colorScheme.error
                )
            },
            text = item.name,
            // A hint that output exists, not the output itself.
            trailingText = item.result?.takeIf { it.isNotBlank() }?.let { "· ${it.take(60)}" }
        )
        if (argsSummary != null) {
            MonoText(
                text = argsSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MaterialTheme.spacing.md)
            )
        }
    }
}
