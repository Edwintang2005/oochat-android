package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.AgentStatus
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for status color, shared by the agent list and nav
 * drawer so the two can't drift.
 */
@Composable
fun agentStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.Active, AgentStatus.Connected -> MaterialTheme.colorScheme.primary
    AgentStatus.Connecting -> MaterialTheme.colorScheme.tertiary
    is AgentStatus.Error -> MaterialTheme.colorScheme.error
    AgentStatus.Disabled -> MaterialTheme.colorScheme.outline
}
