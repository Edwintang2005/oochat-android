package ai.openonion.oochat.ui.common

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * Remembers a `(label, text) -> Unit` that writes [text] to the system
 * clipboard via [LocalClipboardManager] — the same platform clipboard both
 * of the app's previous idioms (`getSystemService(CLIPBOARD_SERVICE)` +
 * `ClipData.newPlainText`, and `LocalClipboardManager.setText` directly)
 * ultimately wrote to, so consolidating onto this one changes nothing
 * observable.
 *
 * Owns only the write. Every call site keeps showing its own "copied"
 * feedback exactly as before — a snackbar, a
 * [ai.openonion.oochat.ui.settings.components.CopyState]-driven
 * "Copied!" pill, or a Toast — since that feedback differs per site and was
 * never actually duplicated. [label] is accepted for call-site readability
 * (it mirrors the old `ClipData.newPlainText(label, text)` call) but isn't
 * surfaced anywhere the user can see it.
 */
@Composable
fun rememberClipboard(): (label: String, text: String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { label, text ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }
}
