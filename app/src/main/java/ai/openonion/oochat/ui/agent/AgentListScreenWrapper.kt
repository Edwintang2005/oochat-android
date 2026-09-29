package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.di.appContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Wrapper for AgentListScreen with navigation and ViewModel support.
 *
 * Uses AgentViewModel for state management.
 * No temporary implementations or fake data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreenWrapper(
    onAgentSelect: (String) -> Unit,
    onEditAgent: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val configRepository = remember { context.appContainer.configRepository }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDiscovery) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Discover agents"
                        )
                    }
                    IconButton(onClick = onCreateAgent) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add agent",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        AgentListScreen(
            agents = uiState.agents,
            selectedAgentId = uiState.selectedAgent?.id,
            defaultAgentId = uiState.defaultAgentId,
            snackbarHostState = snackbarHostState,
            onAgentSelect = { agentAddress ->
                // Find the agent and save its config for auto-connect
                val agent = uiState.agents.find { it.address == agentAddress }
                if (agent != null) {
                    scope.launch {
                        val config = ConnectionConfig(
                            serverUrl = agent.serverUrl,
                            agentAddress = agent.address
                        )
                        configRepository.saveConfig(config)
                        onAgentSelect(agentAddress)
                    }
                }
            },
            onCreateAgent = onCreateAgent,
            onEditAgent = { agent ->
                onEditAgent(agent.id)
            },
            onDeleteAgent = { agentId ->
                viewModel.deleteAgent(agentId)
            },
            onSetDefault = { agentId ->
                viewModel.setDefaultAgent(agentId)
            },
            onReorderAgents = { orderedIds ->
                viewModel.reorderAgents(orderedIds)
            },
            modifier = modifier.padding(paddingValues)
        )
    }
}
