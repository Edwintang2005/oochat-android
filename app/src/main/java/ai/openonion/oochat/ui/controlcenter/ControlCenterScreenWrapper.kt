package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.ui.chat.ChatViewModel
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Binds [ControlCenterScreen] to the chat it belongs to. As with the Dashboard,
 * [chatViewModelStoreOwner] is the chat destination's back-stack entry, so the app's actions go
 * through the same [ChatViewModel] as the conversation underneath.
 */
@Composable
fun ControlCenterScreenWrapper(
    chatViewModelStoreOwner: ViewModelStoreOwner?,
    onNavigateBack: () -> Unit
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = chatViewModelStoreOwner
            ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )

    val app by viewModel.controlCenterApp.collectAsState()
    val snapshot by viewModel.controlCenterSnapshot.collectAsState()
    val profile by viewModel.agentProfile.collectAsState()
    val awaitsUser by viewModel.chatAwaitsUser.collectAsState()

    ControlCenterScreen(
        app = app,
        snapshot = snapshot,
        actions = viewModel.controlCenterActions,
        onNavigateBack = onNavigateBack,
        agentName = profile?.name,
        chatAwaitsUser = awaitsUser
    )
}
