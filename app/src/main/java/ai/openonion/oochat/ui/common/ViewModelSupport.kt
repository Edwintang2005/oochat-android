package ai.openonion.oochat.ui.common

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Resolves a [ViewModel] via [ViewModelProvider.AndroidViewModelFactory],
 * scoped to the current [LocalContext]'s Application.
 *
 * Replaces the identical
 * `viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
 * LocalContext.current.applicationContext as Application))` block that was
 * repeated as a default parameter value across every screen wrapper — the
 * factory every [androidx.lifecycle.AndroidViewModel] subclass in this app
 * needs, since none of them use Hilt/other DI for construction.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(): VM = viewModel(
    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
        LocalContext.current.applicationContext as Application
    )
)

/**
 * Fire-and-forget launch on this ViewModel's [viewModelScope].
 *
 * Promotes the one-line `viewModelScope.launch { … }` delegator repeated
 * across the ViewModels (kick off a suspend call, don't await it) into a
 * single named helper. Intended only for those trivial wrappers — launches
 * that carry their own structure (a `collect` loop, `withContext`, explicit
 * cancellation handling, or a retained [Job]) keep calling `viewModelScope`
 * directly so that structure stays visible at the call site.
 *
 * Named `launchScoped` (not `launch`/`launchIn`) to avoid shadowing
 * kotlinx.coroutines' `CoroutineScope.launch` or `Flow.launchIn`.
 */
fun ViewModel.launchScoped(block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch(block = block)
