package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.ui.common.appViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Wrapper for AgentDiscoveryScreen with navigation and ViewModel support.
 */
@Composable
fun AgentDiscoveryScreenWrapper(
    onNavigateBack: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    selectionMode: Boolean = false,
    onAgentSelected: (ai.openonion.oochat.domain.model.AgentProfile) -> Unit = {},
    initialServerUrl: String? = null,
    initialAgentAddress: String? = null,
    viewModel: AgentViewModel = appViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    AgentDiscoveryScreen(
        discoveredAgents = uiState.discoveredAgents,
        isLoading = uiState.discoveryPhase == DiscoveryPhase.Scanning,
        // AgentDiscoveryScreen displays the raw message string; unwrap the
        // AppError to its user-facing message, same as ChatScreen does.
        error = uiState.discoveryError?.message,
        savedAddresses = uiState.agents.map { it.address }.toSet(),
        onDiscover = { serverUrl ->
            viewModel.discoverAgents(serverUrl)
        },
        onAddAgent = { agent ->
            viewModel.addDiscoveredAgent(agent)
        },
        onBack = onNavigateBack,
        onNavigateToLogs = onNavigateToLogs,
        selectionMode = selectionMode,
        onSelectAgent = onAgentSelected,
        initialServerUrl = initialServerUrl,
        initialAgentAddress = initialAgentAddress
    )
}
