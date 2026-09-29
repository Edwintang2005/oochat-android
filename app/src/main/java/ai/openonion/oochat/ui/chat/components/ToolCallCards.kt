package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import ai.openonion.oochat.util.DiffLineType
import ai.openonion.oochat.util.DiffUtil
import ai.openonion.oochat.util.GrepResultParser
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Tool-name dispatch for [ChatItem.ToolCall], mirroring oo-chat-web's
 * per-tool card components (bash-card / file-card / file-diff-card /
 * grep-card.tsx). The `:` split matches [ActionCards.kt]'s
 * `summarizeBatchTool`'s `baseTool` convention (e.g. "bash:ls" -> "bash").
 * Tool names with no dedicated card fall back to the existing generic
 * [ToolCallBubble].
 */
@Composable
internal fun ToolCallDispatch(item: ChatItem.ToolCall) {
    when (item.name.substringBefore(':').lowercase()) {
        "bash", "shell", "run", "run_background" -> BashCallCard(item)
        "write", "read", "read_file" -> FileCallCard(item)
        "edit" -> FileDiffCallCard(item)
        "grep", "glob", "search", "find" -> GrepCallCard(item)
        else -> ToolCallBubble(item)
    }
}

/**
 * Shared shell for the 4 P0 tool cards: icon + title + status indicator in
 * an always-visible header row, with an [ExpandableSection] body for the
 * tool's output. Approval-pending affordances from the Figma mockup are
 * intentionally dropped — this app surfaces that as a separate
 * [ChatItem.ApprovalNeeded] item, not a [ChatItem.ToolCall] state.
 */
@Composable
private fun ToolCardShell(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    status: ToolStatus,
    hasContent: Boolean,
    body: @Composable () -> Unit
) {
    // Bare row + divider, not a Card: a working turn emits these by the dozen,
    // and 20 stacked containers is the uniform-table problem again (see AgentItem).
    Column(modifier = Modifier.fillMaxWidth()) {
        ExpandableSection(
            showToggle = hasContent,
            previewContent = { expanded ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    ToolStatusIndicator(status)
                    if (hasContent) {
                        ExpandChevron(expanded = expanded)
                    }
                }
            },
            expandedContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MaterialTheme.spacing.md)
                ) {
                    body()
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ToolStatusIndicator(status: ToolStatus) {
    StatusIcon(
        status = when (status) {
            ToolStatus.RUNNING -> IndicatorStatus.RUNNING
            ToolStatus.DONE -> IndicatorStatus.DONE
            ToolStatus.ERROR -> IndicatorStatus.ERROR
        },
        doneContentDescription = "Done",
        errorContentDescription = "Error"
    )
}

@Composable
internal fun BashCallCard(item: ChatItem.ToolCall) {
    val command = item.args?.get("command")
    val output = item.result

    ToolCardShell(
        icon = Icons.Default.Terminal,
        title = command?.takeIf { it.isNotBlank() } ?: item.name,
        subtitle = item.args?.get("description"),
        status = item.status,
        hasContent = !output.isNullOrBlank()
    ) {
        if (!output.isNullOrBlank()) {
            MonoText(text = output, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FileCallCard(item: ChatItem.ToolCall) {
    val isWrite = item.name.substringBefore(':').lowercase() == "write"
    val path = item.args?.get("file_path") ?: item.args?.get("path") ?: item.args?.get("filename")
    val content = if (isWrite) item.args?.get("content") else item.result

    ToolCardShell(
        icon = Icons.Default.Description,
        // Middle truncation, not end-ellipsis: for a path the filename at
        // the end is usually the identifying part (matches DiffPreviewCard's
        // own item.path.truncateMiddle convention).
        title = path?.truncateMiddle(prefix = 20, suffix = 16) ?: item.name,
        subtitle = if (isWrite) "Write" else "Read",
        status = item.status,
        hasContent = !content.isNullOrBlank()
    ) {
        if (!content.isNullOrBlank()) {
            MonoText(text = content, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FileDiffCallCard(item: ChatItem.ToolCall) {
    val path = item.args?.get("file_path") ?: item.args?.get("path") ?: item.args?.get("filename")
    val oldString = item.args?.get("old_string").orEmpty()
    val newString = item.args?.get("new_string").orEmpty()
    val hunks = remember(oldString, newString) { DiffUtil.diffLines(oldString, newString) }

    ToolCardShell(
        icon = Icons.Default.Edit,
        title = path?.truncateMiddle(prefix = 20, suffix = 16) ?: item.name,
        subtitle = "Edit",
        status = item.status,
        hasContent = hunks.isNotEmpty()
    ) {
        Column {
            hunks.forEach { hunk ->
                hunk.lines.forEach { line ->
                    val (prefix, color) = when (line.type) {
                        DiffLineType.ADDED -> "+ " to MaterialTheme.statusColors.success
                        DiffLineType.REMOVED -> "- " to MaterialTheme.colorScheme.error
                        DiffLineType.CONTEXT -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    MonoText(text = prefix + line.content, color = color)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FileDiffCallCardErrorPreview() {
    ConnectOnionTheme {
        FileDiffCallCard(
            ChatItem.ToolCall(
                id = "diff-2",
                name = "edit",
                args = mapOf(
                    "file_path" to "src/utils/broken.ts",
                    "old_string" to "return null",
                    "new_string" to "return undefined"
                ),
                status = ToolStatus.ERROR,
                result = "Error: file not found"
            )
        )
    }
}

@Composable
internal fun GrepCallCard(item: ChatItem.ToolCall) {
    val tool = item.name.substringBefore(':').lowercase()
    val pattern = item.args?.get("pattern")
    val path = item.args?.get("path")
    val matches = remember(item.result, tool) { GrepResultParser.parse(item.result.orEmpty(), tool) }

    ToolCardShell(
        icon = Icons.Default.Search,
        title = pattern ?: item.name,
        subtitle = path,
        status = item.status,
        hasContent = matches.isNotEmpty()
    ) {
        Column {
            matches.forEach { match ->
                MonoText(
                    text = if (match.line > 0) "${match.file}:${match.line}: ${match.content}" else match.file,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BashCallCardRunningPreview() {
    ConnectOnionTheme {
        BashCallCard(
            ChatItem.ToolCall(
                id = "bash-1",
                name = "bash",
                args = mapOf("command" to "npm install", "description" to "Install dependencies"),
                status = ToolStatus.RUNNING
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BashCallCardDonePreview() {
    ConnectOnionTheme {
        BashCallCard(
            ChatItem.ToolCall(
                id = "bash-2",
                name = "bash",
                args = mapOf("command" to "ls -la"),
                status = ToolStatus.DONE,
                result = "total 24\ndrwxr-xr-x  5 user  staff  160 Jul 19 10:00 .\n-rw-r--r--  1 user  staff  512 Jul 19 10:00 README.md"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FileCallCardWritePreview() {
    ConnectOnionTheme {
        FileCallCard(
            ChatItem.ToolCall(
                id = "file-1",
                name = "write",
                args = mapOf("file_path" to "src/App.tsx", "content" to "export default function App() {\n  return <div />\n}"),
                status = ToolStatus.DONE
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FileCallCardReadPreview() {
    ConnectOnionTheme {
        FileCallCard(
            ChatItem.ToolCall(
                id = "file-2",
                name = "read",
                args = mapOf("path" to "package.json"),
                status = ToolStatus.DONE,
                result = "{\n  \"name\": \"app\"\n}"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FileDiffCallCardDonePreview() {
    ConnectOnionTheme {
        FileDiffCallCard(
            ChatItem.ToolCall(
                id = "diff-1",
                name = "edit",
                args = mapOf(
                    "file_path" to "src/utils/math.ts",
                    "old_string" to "function add(a, b) {\n  return a + b\n}",
                    "new_string" to "function add(a: number, b: number): number {\n  return a + b\n}"
                ),
                status = ToolStatus.DONE
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun GrepCallCardPreview() {
    ConnectOnionTheme {
        GrepCallCard(
            ChatItem.ToolCall(
                id = "grep-1",
                name = "grep",
                args = mapOf("pattern" to "TODO", "path" to "src/"),
                status = ToolStatus.DONE,
                result = "src/App.tsx:12:// TODO: add error boundary\nsrc/utils.ts:4:// TODO: memoize this"
            )
        )
    }
}
