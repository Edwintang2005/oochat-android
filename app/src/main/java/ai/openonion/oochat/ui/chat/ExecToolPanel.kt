package ai.openonion.oochat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A developer-only phone-side form for sending a direct EXEC command. */
@Composable
internal fun ExecToolPanel(
    tools: List<String>,
    onExecute: (tool: String, argsJson: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var toolMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedTool by rememberSaveable(tools) {
        mutableStateOf(tools.firstOrNull().orEmpty())
    }
    var argsJson by rememberSaveable { mutableStateOf("{}") }
    val argsError = remember(argsJson) { validateExecArguments(argsJson) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Direct tool execution",
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }

        if (expanded) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { toolMenuExpanded = true },
                    enabled = tools.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedTool.ifBlank { "Select a tool" })
                }

                DropdownMenu(
                    expanded = toolMenuExpanded,
                    onDismissRequest = { toolMenuExpanded = false }
                ) {
                    tools.forEach { toolName ->
                        DropdownMenuItem(
                            text = { Text(toolName) },
                            onClick = {
                                selectedTool = toolName
                                toolMenuExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = argsJson,
                onValueChange = { argsJson = it },
                label = { Text("Arguments JSON") },
                supportingText = {
                    Text(argsError ?: "Enter a JSON object, for example: {\"command\":\"pwd\"}")
                },
                isError = argsError != null,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                enabled = selectedTool.isNotBlank() && argsError == null,
                onClick = { onExecute(selectedTool, argsJson) }
            ) {
                Text("Execute")
            }
        }
    }
}

private fun validateExecArguments(argsJson: String): String? {
    if (argsJson.isBlank()) return "Arguments are required"

    val element = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
        ?: return "Arguments must be valid JSON"
    return if (element is JsonObject) {
        null
    } else {
        "Arguments must be a JSON object"
    }
}
