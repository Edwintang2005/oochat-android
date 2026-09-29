package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.AgentDiscoveryRepository
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.DefaultAgentRepositoryContract
import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AppError
import ai.openonion.oochat.domain.model.createError
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.ui.common.launchScoped
import ai.openonion.oochat.util.runCatchingCancellable
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * How far the relay scan has got.
 *
 * Deliberately not folded into [AgentUiState.isLoading]: that field tracks the
 * saved-agent list, and `loadAgents()` collects a Room Flow that clears it on
 * every emission — so a scan sharing the field had its radar switched off by
 * an unrelated database write. Same reason [AgentUiState.discoveryError] is
 * kept apart from [AgentUiState.error].
 *
 * [Idle] is not [Done] with no results. Only [Done] means a server actually
 * answered, so only [Done] may claim there are no agents on it.
 */
enum class DiscoveryPhase { Idle, Scanning, Done }

/**
 * UI state for agent management.
 */
data class AgentUiState(
    val agents: List<AgentProfile> = emptyList(),
    val selectedAgent: AgentProfile? = null,
    val defaultAgentId: String? = null,
    val discoveredAgents: List<AgentProfile> = emptyList(),
    val isLoading: Boolean = false,
    // [AppError] rather than a raw String so this ViewModel classifies
    // failures the same way [ai.openonion.oochat.ui.chat.ChatViewModel]
    // does; the screen renders [AppError.message] at the wrapper boundary.
    val error: AppError? = null,
    // Separate from [error]: a discoverAgents() failure must only ever show
    // up as the discovery panel's own inline "Discovery failed" state, never
    // as the createAgent/updateAgent validation snackbar (or vice versa) —
    // sharing one field between both meant a stale createAgent error (e.g.
    // "Agent with this address already exists") would flip the discovery
    // panel above it into an error state it never actually hit.
    val discoveryError: AppError? = null,
    val discoveryPhase: DiscoveryPhase = DiscoveryPhase.Idle
)

/**
 * ViewModel for agent management.
 *
 * Provides agent list, creation, editing, deletion, and discovery.
 * Uses AgentRepository and DefaultAgentRepository for data access.
 *
 * @JvmOverloads so the AndroidX default
 * [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory] can still
 * locate a `(Application)` constructor via reflection — same pattern as
 * [ai.openonion.oochat.ui.chat.ChatViewModel]. Default-value
 * expressions pull from the app-scoped [ai.openonion.oochat.di.AppContainer]
 * instead of constructing repositories inline, giving tests the same
 * fake-injection seam ChatViewModelTest already relies on.
 */
class AgentViewModel @JvmOverloads constructor(
    application: Application,
    private val agentRepository: AgentRepository = application.appContainer.agentRepository,
    private val defaultAgentRepository: DefaultAgentRepositoryContract = application.appContainer.defaultAgentRepository,
    private val discoveryRepository: AgentDiscoveryRepository = application.appContainer.discoveryRepository,
    private val configRepository: ConnectionConfigRepository = application.appContainer.configRepository,
    // Shared app-wide use case (same instance ChatViewModel talks to) — lets
    // the edit screen show the *live* AGENT_PROFILE for whichever saved
    // agent happens to be the one currently connected, without this
    // ViewModel owning a connection of its own.
    private val connectToAgentUseCase: ConnectToAgentUseCaseContract = application.appContainer.connectToAgentUseCase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    /**
     * The address of the agent the app is currently connected to, or null.
     * Combined with [liveAgentProfile] by [AgentEditScreenWrapper] to decide
     * whether the agent being edited is the live one — see
     * [ai.openonion.oochat.domain.model.AgentLiveProfile]'s doc for
     * why this is live connection state, not something this ViewModel
     * fetches on its own.
     */
    val connectedAgentAddress: StateFlow<String?> = connectToAgentUseCase.connectionState
        .map { it.addressOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The connected agent's own name card, if any — see [connectedAgentAddress]. */
    val liveAgentProfile: StateFlow<AgentLiveProfile?> = connectToAgentUseCase.agentProfile

    init {
        loadAgents()
        loadDefaultAgent()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            agentRepository.getAllAgents().collect { agentList ->
                _uiState.update {
                    it.copy(
                        agents = agentList,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun loadDefaultAgent() {
        launchScoped {
            val defaultAgent = defaultAgentRepository.getDefaultAgent()
            _uiState.update {
                it.copy(
                    defaultAgentId = defaultAgent?.id,
                    selectedAgent = defaultAgent
                )
            }
        }
    }

    fun selectAgent(agentId: String) {
        launchScoped {
            val agent = agentRepository.getAgentById(agentId)
            _uiState.update { it.copy(selectedAgent = agent) }
        }
    }

    /**
     * Shared submit-time validation for [createAgent] and [updateAgent] — mirrors
     * [ai.openonion.oochat.ui.onboarding.OnboardingViewModel]'s length caps and
     * URL/address format checks so the two screens agree on what's a valid agent.
     */
    private fun validateAgentInput(name: String, address: String, serverUrl: String): String? = when {
        name.isBlank() || address.isBlank() || serverUrl.isBlank() ->
            "All fields are required"
        serverUrl.length > 500 ->
            "URL is too long (maximum 500 characters)"
        address.length > 200 ->
            "Agent address is too long (maximum 200 characters)"
        !serverUrl.startsWith("https://") && !serverUrl.startsWith("http://") &&
            !serverUrl.startsWith("wss://") && !serverUrl.startsWith("ws://") ->
            "Please enter a valid URL (e.g., https://server.com)"
        address.startsWith("https://") || address.startsWith("http://") ||
            address.startsWith("wss://") || address.startsWith("ws://") ->
            "Agent address should be a hex address (0x...), not a URL. Please select from the dropdown."
        !AgentDiscoveryService.ADDRESS_REGEX.matches(address) ->
            "Please enter a valid agent address (0x followed by 64 hex characters)"
        else -> null
    }

    /**
     * @return `true` if the agent was created, `false` if validation/duplicate
     * checks failed — in which case [AgentUiState.error] explains why, so the
     * caller can keep the user on the form instead of navigating away from a
     * failed submission.
     */
    suspend fun createAgent(
        name: String,
        address: String,
        serverUrl: String,
        description: String? = null,
        apiKey: String? = null,
        connectionMode: String = "relay"
    ): Boolean {
        val validationError = validateAgentInput(name, address, serverUrl)
        if (validationError != null) {
            _uiState.update { it.copy(error = createError(validationError)) }
            return false
        }

        // Check for duplicate address
        val existing = agentRepository.getAgentByAddress(address)
        if (existing != null) {
            _uiState.update { it.copy(error = createError("Agent with this address already exists")) }
            return false
        }

        val agent = AgentProfile(
            id = UUID.randomUUID().toString(),
            address = address,
            name = name,
            description = description,
            serverUrl = serverUrl,
            apiKey = apiKey,
            avatarUrl = null,
            createdAt = System.currentTimeMillis(),
            connectionMode = connectionMode,
            lastConnectedAt = null,
            isActive = true
        )

        // Captured before the repository call (not after) since createAgent()
        // triggers the getAllAgents() flow collector that updates
        // _uiState.agents — checking post-insert would race that collector
        // and could see the just-created agent already counted.
        val wasFirstAgent = _uiState.value.agents.isEmpty()

        agentRepository.createAgent(agent)

        if (wasFirstAgent) {
            defaultAgentRepository.setDefaultAgent(agent.id)
            _uiState.update { it.copy(defaultAgentId = agent.id) }
        }
        return true
    }

    /**
     * @return `true` if the agent was updated, `false` if validation failed —
     * see [createAgent]'s return-value doc; the edit form uses the same
     * stay-on-failure pattern via [AgentUiState.error].
     */
    suspend fun updateAgent(agent: AgentProfile): Boolean {
        val validationError = validateAgentInput(agent.name, agent.address, agent.serverUrl)
        if (validationError != null) {
            _uiState.update { it.copy(error = createError(validationError)) }
            return false
        }

        agentRepository.updateAgent(agent)

        // Refresh selected agent if updated
        if (_uiState.value.selectedAgent?.id == agent.id) {
            selectAgent(agent.id)
        }
        return true
    }

    /**
     * Persists the tapped agent's [ConnectionConfig] (the auto-connect
     * target), then invokes [onSaved] — the caller navigates to chat.
     */
    fun selectAgentForConnection(agentAddress: String, onSaved: () -> Unit) {
        val agent = _uiState.value.agents.find { it.address == agentAddress } ?: return
        launchScoped {
            configRepository.saveConfig(
                ConnectionConfig(
                    serverUrl = agent.serverUrl,
                    agentAddress = agent.address
                )
            )
            onSaved()
        }
    }

    fun deleteAgent(agentId: String) {
        launchScoped {
            // If deleting default agent, clear default
            if (_uiState.value.defaultAgentId == agentId) {
                defaultAgentRepository.clearDefaultAgent()
                _uiState.update { it.copy(defaultAgentId = null) }
            }

            // If deleting selected agent, clear selection
            if (_uiState.value.selectedAgent?.id == agentId) {
                _uiState.update { it.copy(selectedAgent = null) }
            }

            agentRepository.deleteAgent(agentId)
        }
    }

    fun setDefaultAgent(agentId: String) {
        launchScoped {
            defaultAgentRepository.setDefaultAgent(agentId)
            _uiState.update { it.copy(defaultAgentId = agentId) }
        }
    }

    /**
     * Persist a new drag-to-reorder order for the agent list. [orderedIds]
     * is the full agent id list in its new display order — matches the
     * order NavDrawer's agent-grouped sidebar reads from the same
     * `position`-ordered [AgentRepository.getAllAgents] query.
     */
    fun reorderAgents(orderedIds: List<String>) {
        launchScoped {
            agentRepository.reorderAgents(orderedIds)
        }
    }

    fun discoverAgents(serverUrl: String) {
        launchScoped {
            _uiState.update {
                it.copy(
                    discoveryPhase = DiscoveryPhase.Scanning,
                    discoveredAgents = emptyList(),
                    discoveryError = null
                )
            }

            runCatchingCancellable {
                discoveryRepository.discoverFromRelay(serverUrl)
            }.onSuccess { agents ->
                _uiState.update {
                    it.copy(
                        discoveredAgents = agents,
                        discoveryPhase = DiscoveryPhase.Done
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        discoveryError = createError("Discovery failed: ${e.message}"),
                        discoveryPhase = DiscoveryPhase.Done
                    )
                }
            }
        }
    }

    fun addDiscoveredAgent(agent: AgentProfile) {
        launchScoped {
            agentRepository.createAgent(agent)
            _uiState.update {
                it.copy(
                    discoveredAgents = _uiState.value.discoveredAgents.filter { it.id != agent.id }
                )
            }
        }
    }

    /**
     * Saves the agent (same dedupe/default logic as [createAgent]) and also persists
     * [ConnectionConfig] so the caller can proceed to LoadingScreen, unlike [addDiscoveredAgent].
     */
    fun connectToDiscoveredAgent(agent: AgentProfile, onSaved: () -> Unit) {
        launchScoped {
            val alreadySaved = agentRepository.getAgentByAddress(agent.address) != null
            if (!alreadySaved) {
                agentRepository.createAgent(agent)
                if (_uiState.value.agents.isEmpty()) {
                    defaultAgentRepository.setDefaultAgent(agent.id)
                    _uiState.update { it.copy(defaultAgentId = agent.id) }
                }
            }
            _uiState.update {
                it.copy(discoveredAgents = it.discoveredAgents.filter { d -> d.id != agent.id })
            }
            configRepository.saveConfig(
                ConnectionConfig(
                    serverUrl = agent.serverUrl,
                    agentAddress = agent.address,
                    apiKey = agent.apiKey
                )
            )
            onSaved()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
