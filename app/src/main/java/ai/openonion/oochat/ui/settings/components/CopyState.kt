package ai.openonion.oochat.ui.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * Single canonical implementation of the copy-then-reset-after-2s pattern,
 * shared across all copy sites.
 */
class CopyState internal constructor(private val state: MutableState<Boolean>) {
    val copied: Boolean get() = state.value

    /** Call after performing the actual clipboard write to show "Copied!" for [rememberCopyState]'s delay. */
    fun markCopied() {
        state.value = true
    }
}

/**
 * Remembers a [CopyState] that flips back to `copied = false` [resetDelayMillis]
 * after [CopyState.markCopied] is called — each call restarts the timer.
 */
@Composable
fun rememberCopyState(resetDelayMillis: Long = 2000L): CopyState {
    val state = remember { mutableStateOf(false) }
    LaunchedEffect(state.value) {
        if (state.value) {
            delay(resetDelayMillis)
            state.value = false
        }
    }
    return remember { CopyState(state) }
}
