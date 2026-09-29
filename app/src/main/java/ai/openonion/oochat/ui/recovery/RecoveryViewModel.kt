package ai.openonion.oochat.ui.recovery

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.di.appContainer
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [RecoveryScreen] with the saved connection config it needs to
 * display (server URL / agent address the failed auto-connect was using).
 *
 * `@JvmOverloads` so the AndroidX default
 * [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory] can still
 * find a `(Application)` constructor via reflection.
 */
class RecoveryViewModel @JvmOverloads constructor(
    application: Application,
    private val configRepository: ConnectionConfigRepository = application.appContainer.configRepository
) : AndroidViewModel(application) {

    private val _config = MutableStateFlow<ConnectionConfig?>(null)
    val config: StateFlow<ConnectionConfig?> = _config.asStateFlow()

    init {
        loadSavedConfig()
    }

    private fun loadSavedConfig() {
        viewModelScope.launch {
            _config.value = configRepository.getConfig()
        }
    }
}
