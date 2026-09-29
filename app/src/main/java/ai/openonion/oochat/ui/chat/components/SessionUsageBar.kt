package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Session-wide token/cost/context summary — the Android counterpart to
 * oo-chat-web's status-bar.tsx.
 *
 * Rendered inline at the end of the approval-mode row rather than as its own
 * full-width strip: both are session-scoped chrome about the current agent,
 * and a bar of its own cost a whole line to show at most nine characters.
 *
 * Deliberately narrower than the reference: web's left slot shows a
 * "reconnecting" pulse, which this app already surfaces via
 * [ConnectionBanner][ai.openonion.oochat.ui.components.ConnectionBanner]
 * above the message list — duplicating it here would just be two indicators
 * disagreeing about the same fact. Matching web's `!hasTokenData` early
 * return, nothing renders at all until there are tokens to show.
 */
@Composable
fun RowScope.SessionUsageSummary(usage: SessionUsageTotals) {
    if (usage.totalTokens <= 0) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildString {
                append(formatTokenCount(usage.totalTokens))
                append(" tokens")
                if (usage.totalCostUsd >= MIN_DISPLAY_COST_USD) append(" · ${formatCostUsd(usage.totalCostUsd)}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline()
        )

        // Two different silences, kept apart on purpose: no turn has reported a
        // reading yet, versus a reading too small to draw. Context windows now
        // run past a million tokens, so an ordinary conversation measures
        // thousandths of a percent and the ring would sit as a bare track for
        // the whole session.
        val reading = usage.latestContextPercent?.let { formatContextPercent(it) }
        when {
            reading == null -> Unit
            !isCtxRingLegible(reading) -> Unit
            else -> CtxRing(percent = reading, modifier = Modifier.alignByBaseline())
        }
    }
}

/** Standalone wrapper, for previews and screenshot tests. */
@Composable
fun SessionUsageBar(usage: SessionUsageTotals, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SessionUsageSummary(usage)
    }
}
