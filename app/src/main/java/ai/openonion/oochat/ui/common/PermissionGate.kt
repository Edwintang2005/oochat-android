package ai.openonion.oochat.ui.common

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * A single "check permission → show rationale once → request it → say so if
 * refused" flow, shared by every runtime-permission gate in the app (the input
 * bar's mic and camera, and onboarding's QR-scan camera). Each of those used to
 * hand-roll its own `showXRationale` state + [AlertDialog] + permission
 * launcher — three copies of the same five moving parts, differing only in
 * the permission string and the dialog copy.
 *
 * Returns a `request()` lambda: call it from a click handler. It checks the
 * permission immediately and either runs [onGranted] synchronously (already
 * granted) or shows the rationale dialog first, deferring to the system
 * prompt only after the user taps "Continue" — so the OS dialog never fires
 * with zero context on the very first tap.
 *
 * Refusal is handled here rather than left to each caller. Android tells an
 * app nothing after a second refusal — the system prompt simply stops
 * appearing — so a gate that only acts on `granted` leaves the button looking
 * live and doing nothing, with the one route back (the app's own settings
 * page) unreachable from inside the app. The refusal dialog is that route.
 * Callers deliberately do not disable the control that opens this gate: a
 * greyed-out button explains nothing and would take the recovery path with it.
 */
@Composable
fun rememberPermissionGate(
    permission: String,
    rationaleTitle: String,
    rationaleMessage: String,
    deniedMessage: String,
    onGranted: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onGranted() else showDenied = true
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(rationaleTitle) },
            text = { Text(rationaleMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(permission)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Not now") }
            }
        )
    }

    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text(rationaleTitle) },
            text = { Text(deniedMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showDenied = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    // Not every ROM ships this activity; losing the shortcut is
                    // survivable, taking the app down with it is not.
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        FileLogger.w(LogTags.PERMISSION, "No app-settings activity to open: ${e.message}")
                    }
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showDenied = false }) { Text("Not now") }
            }
        )
    }

    return {
        val hasPermission = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            onGranted()
        } else {
            showRationale = true
        }
    }
}
