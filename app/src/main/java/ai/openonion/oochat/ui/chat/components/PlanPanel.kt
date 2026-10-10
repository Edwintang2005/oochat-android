package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.PlanEntry
import ai.openonion.oochat.domain.model.PlanPriority
import ai.openonion.oochat.domain.model.PlanStatus
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The agent's current Todo List, pinned between the top bar and the
 * transcript — the Android counterpart of oo-chat's current-plan-panel.tsx.
 *
 * Above the list rather than in it because a `plan` frame replaces the whole
 * plan: as a chat item it would either stay where the first one landed and
 * scroll away while the agent works through it, or jump to the bottom on every
 * update. Collapsed it is one line naming the step in progress; tapping shows
 * every entry. Nothing renders when there is no plan.
 */
@Composable
internal fun PlanPanel(entries: List<PlanEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val completed = entries.count { it.status == PlanStatus.COMPLETED }
    val done = completed == entries.size
    val current = entries.firstOrNull { it.status == PlanStatus.IN_PROGRESS }
        ?: entries.firstOrNull { it.status == PlanStatus.PENDING }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(PLAN_PANEL_TAG)
    ) {
        ExpandableSection(
            previewContent = { expanded ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = MaterialTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    if (done) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        StatusDot(MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = planHeadline(done, current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Announced as the agent moves on, like the web panel's aria-live.
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Text(
                        text = "$completed/${entries.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExpandChevron(expanded, Modifier.size(18.dp))
                }
            },
            expandedContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A long plan must not push the transcript off screen.
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = MaterialTheme.spacing.lg,
                            end = MaterialTheme.spacing.lg,
                            bottom = MaterialTheme.spacing.sm
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    entries.forEach { PlanEntryRow(it) }
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PlanEntryRow(entry: PlanEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        StatusDot(
            color = when (entry.status) {
                PlanStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                PlanStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                PlanStatus.PENDING -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.padding(top = 6.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (entry.status == PlanStatus.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
                color = if (entry.status == PlanStatus.COMPLETED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = "${planStatusLabel(entry.status)} · ${planPriorityLabel(entry.priority)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(6.dp).background(color, CircleShape))
}

/** The collapsed panel's one line: the step being worked on, else the next one. */
internal fun planHeadline(done: Boolean, current: PlanEntry?): String = when {
    done -> "Plan complete"
    current != null -> current.content
    else -> "Task plan"
}

internal fun planStatusLabel(status: PlanStatus): String = when (status) {
    PlanStatus.PENDING -> "Pending"
    PlanStatus.IN_PROGRESS -> "In progress"
    PlanStatus.COMPLETED -> "Completed"
}

internal fun planPriorityLabel(priority: PlanPriority): String = when (priority) {
    PlanPriority.HIGH -> "High priority"
    PlanPriority.MEDIUM -> "Medium priority"
    PlanPriority.LOW -> "Low priority"
}

internal const val PLAN_PANEL_TAG = "plan_panel"
