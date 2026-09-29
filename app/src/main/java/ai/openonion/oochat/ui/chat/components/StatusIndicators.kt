package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 3 states shared by [ToolStatus]/[ThinkingStatus]/etc.-driven
 * indicators across the chat cards. Kept separate from those domain enums
 * so [StatusIcon] doesn't couple to any one of them — call sites map their
 * own status enum onto this one.
 */
internal enum class IndicatorStatus { RUNNING, DONE, ERROR }

/**
 * Shared running/done/error indicator: a small [CircularProgressIndicator]
 * while [IndicatorStatus.RUNNING], else a tinted [Icon] for DONE/ERROR.
 * Icon, tint, size and content description are all overridable since call
 * sites disagree on exactly what "done"/"error" should look like — e.g.
 * EvalCard's passed/failed uses CheckCircle/Cancel with "Passed"/"Failed"
 * descriptions, while [ToolCallCards.kt]'s tool status uses
 * CheckCircle/ErrorOutline with "Done"/"Error".
 */
@Composable
internal fun StatusIcon(
    status: IndicatorStatus,
    size: Dp = 16.dp,
    runningSize: Dp = 14.dp,
    strokeWidth: Dp = 2.dp,
    doneIcon: ImageVector = Icons.Default.CheckCircle,
    errorIcon: ImageVector = Icons.Default.ErrorOutline,
    doneTint: Color = MaterialTheme.statusColors.success,
    errorTint: Color = MaterialTheme.colorScheme.error,
    doneContentDescription: String? = null,
    errorContentDescription: String? = null
) {
    when (status) {
        IndicatorStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(runningSize),
            strokeWidth = strokeWidth
        )
        IndicatorStatus.DONE -> Icon(
            imageVector = doneIcon,
            contentDescription = doneContentDescription,
            tint = doneTint,
            modifier = Modifier.size(size)
        )
        IndicatorStatus.ERROR -> Icon(
            imageVector = errorIcon,
            contentDescription = errorContentDescription,
            tint = errorTint,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * The pill row repeated by [ThinkingBubble], [IntentCard] and
 * [PlanReviewCard]'s decided-state bar: a clipped, outlined row holding a
 * leading spinner-or-icon, a labelMedium status line, and an optional trailing
 * "· xxx" annotation.
 */
@Composable
internal fun StatusPill(
    leading: @Composable () -> Unit,
    text: String,
    trailingText: String? = null,
    // Call sites disagree here: IntentCard's "· build" uses outline, while
    // ThinkingBubble's "· $model" uses onSurfaceVariant.
    trailingColor: Color = MaterialTheme.colorScheme.outline,
    // Optional slot rendered after the trailing text — used by ThinkingBubble
    // to offer a "Retry" action on a failed turn without every other
    // StatusPill call site (IntentCard, etc.) needing to know about it.
    trailingAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2),
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            // Outlined, not filled: this is ambient status. Filled, it carried
            // the same visual weight as an agent reply or a decision gate.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
    ) {
        leading()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = trailingColor
            )
        }
        trailingAction?.invoke()
    }
}

/**
 * `bodySmall` in [FontFamily.Monospace], repeated for every raw tool/eval
 * output line (bash stdout, file contents, diff hunks, grep matches...).
 * Only the tint consistently differs between call sites, so it's the one
 * exposed parameter.
 */
@Composable
internal fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = modifier
    )
}

/**
 * Compact token count for a Thinking footer's "· N tokens" suffix — matches
 * oo-chat-web's thinking.tsx formatTokens (1 decimal place above 1000, no
 * rounding surprises at exact thousands: 1000 -> "1.0k").  Written without
 * String.format to avoid locale-dependent decimal separators (some locales
 * would render "1,2k" instead of "1.2k").
 */
internal fun formatTokenCount(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    val tenths = Math.round(tokens / 100.0)
    return "${tenths / 10}.${tenths % 10}k"
}

/**
 * Rounds a [ChatItem.Thinking.contextPercent] for the footer's "· N% ctx".
 *
 * The server already sends a percentage, not a fraction: core/agent.py's
 * `context_percent` is documented "(0-100)" and computed as
 * `input_tokens / limit * 100`. The reference web client rounds it and
 * nothing more (status-bar.tsx). This used to multiply by 100 again, so a
 * third of the window read as "3295% ctx".
 */
internal fun formatContextPercent(percent: Double): Int =
    Math.round(percent).toInt()

/**
 * Below this a cost renders as "$0.0000" — four decimal places of zero, which
 * reads as a number rather than as "too small to matter". Every caller that
 * shows a cost gates on it.
 */
internal const val MIN_DISPLAY_COST_USD = 0.0001

/**
 * USD for one LLM call, from core/usage.py's cost field. Sub-cent calls are
 * the common case, so this keeps enough places to stay non-zero rather than
 * rounding an honest $0.004 down to "$0.00".
 */
internal fun formatCostUsd(cost: Double): String =
    if (cost >= 0.01) "$%.2f".format(cost) else "$%.4f".format(cost)
