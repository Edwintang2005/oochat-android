package ai.openonion.oochat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A preview row that expands to reveal more content on tap, via a
 * rotating chevron + [AnimatedVisibility] — extracted from
 * [ai.openonion.oochat.ui.agent.components.AgentFormContent]'s
 * "Advanced Settings" pattern for reuse across the tool/status cards
 * (bash output, file content, diff hunks, grep matches, eval detail,
 * plan content).
 *
 * @param previewContent The always-visible row; receives the current
 *   `expanded` state so it can render an [ExpandChevron] reflecting it.
 * @param showToggle When false, the row isn't clickable and [expandedContent]
 *   is never shown — for callers where there's nothing worth expanding
 *   (e.g. a tool call with no output yet).
 */
@Composable
internal fun ExpandableSection(
    previewContent: @Composable (expanded: Boolean) -> Unit,
    expandedContent: @Composable () -> Unit,
    showToggle: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Column(
            modifier = if (showToggle) Modifier.clickable { expanded = !expanded } else Modifier
        ) {
            previewContent(expanded && showToggle)
        }
        if (showToggle) {
            AnimatedVisibility(visible = expanded) {
                expandedContent()
            }
        }
    }
}

/** Standard rotating expand/collapse chevron, matching [ExpandableSection]'s animation. */
@Composable
internal fun ExpandChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expandChevron")
    Icon(
        imageVector = Icons.Default.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.graphicsLayer { rotationZ = chevronRotation }
    )
}
