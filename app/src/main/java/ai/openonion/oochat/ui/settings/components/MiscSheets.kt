package ai.openonion.oochat.ui.settings.components

import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.components.DangerConfirmActions
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Bottom sheet with a free-text field for custom AI instructions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomInstructionsSheet(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }

    ConfirmationSheet(
        title = "Custom instructions",
        subtitle = "Set default behavior and tone",
        columnVerticalPadding = 0.dp,
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                // Input level = bodyMedium; a bare field inherits bodyLarge
                // (16sp), which would outsize this sheet's own subtitle.
                placeholder = {
                    Text(
                        "e.g. Be concise and use bullet points",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        },
        actions = {
            Button(
                onClick = { onSave(text); onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    )
}

/** Bottom sheet confirming the destructive "delete all data" action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteAllDataSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Clear connection data?",
        body = "This clears your saved connection so you'll need to reconnect to an agent. Saved agents and chat history are not affected.",
        onDismiss = onDismiss,
        actions = {
            DangerConfirmActions(confirmLabel = "Delete", onConfirm = onConfirm, onDismiss = onDismiss)
        }
    )
}

/** Bottom sheet confirming deletion of every saved conversation (real — cascades to messages). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteAllConversationsSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Delete all conversations?",
        body = "This permanently deletes every saved conversation and its messages on this device. Your agents and connection settings are not affected.",
        onDismiss = onDismiss,
        actions = {
            DangerConfirmActions(confirmLabel = "Delete", onConfirm = onConfirm, onDismiss = onDismiss)
        }
    )
}

/**
 * Bottom sheet with honest, app-appropriate placeholder copy for Terms &
 * Privacy — ConnectOnion is a client for agents/servers the user configures
 * themselves, so it has no independent terms of service to fabricate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TermsAndPrivacySheet(onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Terms & Privacy",
        body = "ConnectOnion is a client app: it connects directly to the AI agents and servers you configure yourself, and does not operate its own backend service. " +
            "There are no separate ConnectOnion terms of service or privacy policy — review the terms and privacy practices of the agent provider and server you connect to. " +
            "Data this app stores locally on your device (conversations, saved agents, connection settings) is described in the Data & Privacy section above.",
        titleToBodySpacing = MaterialTheme.spacing.md,
        onDismiss = onDismiss,
        actions = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    )
}
