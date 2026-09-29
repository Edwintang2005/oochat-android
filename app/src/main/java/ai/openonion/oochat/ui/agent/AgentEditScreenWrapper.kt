package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.ui.common.appViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Wrapper for AgentFormScreen with navigation and ViewModel support.
 */
@Composable
fun AgentEditScreenWrapper(
    agentId: String?,
    onNavigateBack: () -> Unit,
    onNavigateToDiscover: (serverUrl: String, agentAddress: String) -> Unit = { _, _ -> },
    pendingDiscoveredAddress: String? = null,
    onDiscoveredAddressConsumed: () -> Unit = {},
    viewModel: AgentViewModel = appViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectedAgentAddress by viewModel.connectedAgentAddress.collectAsState()
    val liveAgentProfile by viewModel.liveAgentProfile.collectAsState()
    val scope = rememberCoroutineScope()

    val agent = if (agentId != null && agentId != "new") {
        uiState.agents.find { it.id == agentId }
    } else {
        null
    }

    // Only surface the live AGENT_PROFILE when it's actually this agent's —
    // liveAgentProfile is connection-scoped, not keyed by agent, so a match
    // has to be confirmed by address before showing tools/skills that might
    // belong to a different saved agent this app happens to be connected to.
    val liveProfileForThisAgent = liveAgentProfile.takeIf { agent != null && agent.address == connectedAgentAddress }

    AgentFormScreen(
        existingAgent = agent,
        savedAgents = uiState.agents,
        liveProfile = liveProfileForThisAgent,
        // Only navigate away once the save actually succeeded — createAgent/updateAgent
        // can fail validation (blank/malformed fields, duplicate address) and report
        // that via uiState.error, which AgentFormScreen now surfaces inline
        // instead of the screen silently popping back as if nothing went wrong.
        errorMessage = uiState.error,
        onSave = { savedAgent ->
            scope.launch {
                val success = if (agent == null) {
                    viewModel.createAgent(
                        name = savedAgent.name,
                        address = savedAgent.address,
                        serverUrl = savedAgent.serverUrl,
                        description = savedAgent.description,
                        apiKey = savedAgent.apiKey,
                        connectionMode = savedAgent.connectionMode
                    )
                } else {
                    viewModel.updateAgent(savedAgent)
                }
                if (success) onNavigateBack()
            }
        },
        onCancel = onNavigateBack,
        onNavigateToDiscover = onNavigateToDiscover,
        pendingDiscoveredAddress = pendingDiscoveredAddress,
        onDiscoveredAddressConsumed = onDiscoveredAddressConsumed,
        // Create-mode-only: drives AgentFormScreen's radar-first
        // EmbeddedDiscoveryPanel. A picked result is saved directly (it
        // already has a name/address from discovery) and the screen pops
        // back to the agent list immediately.
        discoveredAgents = uiState.discoveredAgents,
        discoveryPhase = uiState.discoveryPhase,
        discoveryError = uiState.discoveryError,
        onDiscover = { url -> viewModel.discoverAgents(url) },
        onSelectDiscovered = { discovered ->
            viewModel.addDiscoveredAgent(discovered)
            onNavigateBack()
        }
    )
}
