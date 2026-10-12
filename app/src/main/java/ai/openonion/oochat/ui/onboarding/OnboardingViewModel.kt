package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.domain.model.canonicalAgentAddress
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.ui.agent.components.AgentFormState
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Validation + persistence for the Setup form.
 *
 * Loads any previously-saved [ConnectionConfig] on init so OnboardingScreen can prefill its form —
 * critical for returning users (reached via Recovery/LoadingScreen's "Edit configuration" button)
 * who would otherwise have their saved values silently discarded.
 *
 * @JvmOverloads so the AndroidX default [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory]
 * can locate a `(Application)` constructor via reflection.
 */
class OnboardingViewModel @JvmOverloads constructor(
    application: Application,
    private val configRepository: ConnectionConfigRepository =
        application.appContainer.configRepository,
) : AndroidViewModel(application) {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _prefillConfig = MutableStateFlow<ConnectionConfig?>(null)
    /**
     * The previously-saved [ConnectionConfig], if any, loaded once on init.
     * Null both while the (suspend) load is still in flight and — its
     * permanent value — when no config was ever saved (genuine first run).
     * Use [isPrefillLoaded] to tell those two "null" cases apart.
     */
    val prefillConfig: StateFlow<ConnectionConfig?> = _prefillConfig.asStateFlow()

    private val _isPrefillLoaded = MutableStateFlow(false)
    /**
     * Flips to true once the [prefillConfig] load has resolved, whether or
     * not a config was actually found. OnboardingScreen waits for this
     * before first composing its form, so the form's very first composition
     * already reflects any saved config — critical for the Relay/Direct mode
     * toggle, whose own mode-switch handling in
     * [ai.openonion.oochat.ui.agent.components.AgentFormContent]
     * clears the URL/address fields the moment `useDirectConnection` changes
     * *after* first composition. Prefilling post-composition would trip
     * that guard and wipe the very values being restored.
     */
    val isPrefillLoaded: StateFlow<Boolean> = _isPrefillLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            _prefillConfig.value = configRepository.getConfig()
            _isPrefillLoaded.value = true
        }
    }

    /**
     * Validates [formState]; on success persists [ConnectionConfig] and
     * invokes [onSaved] (the caller navigates to the shared LoadingScreen).
     * On failure sets [errorMessage] and returns.
     */
    fun connect(formState: AgentFormState, onSaved: () -> Unit) {
        if (!formState.isValid) return

        val trimmedUrl = formState.serverUrl.trim().trimEnd('/')
        // The chat screen saves this agent from the config, so it gets the
        // same form as everywhere else (W16AAPPLE-57).
        val trimmedAddress = canonicalAgentAddress(formState.agentAddress.trim())
        val trimmedApiKey = formState.apiKey.trim().takeIf { it.isNotBlank() }

        val error = validate(trimmedUrl, trimmedAddress)
        _errorMessage.value = error
        if (error != null) return

        viewModelScope.launch {
            val config = ConnectionConfig(
                serverUrl = trimmedUrl,
                apiKey = trimmedApiKey,
                agentAddress = trimmedAddress.takeIf { it.isNotBlank() }
            )
            configRepository.saveConfig(config)
            onSaved()
        }
    }

    /**
     * What Setup's QR scanner found. A code that names an agent is returned for the form to take,
     * and clears any earlier message. Anything else is refused: nothing is returned and
     * [errorMessage] says so. [contents] is null when the user backed out of the scanner, which is
     * neither, so nothing changes.
     */
    internal fun onScanned(contents: String?): ScannedAgent? {
        if (contents == null) return null
        val agent = AgentQrCode.parse(contents)
        _errorMessage.value = if (agent == null) {
            "That QR code isn't a ConnectOnion agent. Scan the code behind an agent's Share button, or paste its address."
        } else {
            null
        }
        return agent
    }

    private fun validate(trimmedUrl: String, trimmedAddress: String): String? = when {
        trimmedUrl.length > 500 ->
            "URL is too long (maximum 500 characters)"
        trimmedAddress.length > 200 ->
            "Agent address is too long (maximum 200 characters)"
        !trimmedUrl.startsWith("wss://") && !trimmedUrl.startsWith("ws://") &&
            !trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://") ->
            "Please enter a valid URL (e.g., https://server.com)"
        // Agent address should be 0x... hex, not a URL
        trimmedAddress.startsWith("wss://") || trimmedAddress.startsWith("ws://") ||
            trimmedAddress.startsWith("https://") || trimmedAddress.startsWith("http://") ->
            "Agent address should be a hex address (0x...), not a URL. Please select from the dropdown."
        // The check Add Agent makes (AgentViewModel.validateAgentInput). Setup
        // used to save any 0x… string, which can never be dialled (W16AAPPLE-61).
        !AgentDiscoveryService.ADDRESS_REGEX.matches(trimmedAddress) ->
            "Please enter a valid agent address (0x followed by 64 hex characters)"
        else -> null
    }
}
