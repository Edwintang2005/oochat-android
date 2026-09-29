package ai.openonion.oochat.ui.logs

import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import ai.openonion.oochat.util.FileLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen to display app logs with color-coded levels: console-style
 * monospace view, refresh/copy/clear actions, and auto-scroll to the
 * latest line as new logs arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var logs by remember { mutableStateOf("") }
    // Empty means "no filter", not "hide everything" — a filter row that can
    // blank the screen just looks broken.
    var levelFilter by remember { mutableStateOf(emptySet<Char>()) }
    var showClearSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            // readLogs reads the whole file (capped at 5MB) before keeping the
            // last 500 lines, so this has to leave the main thread — a
            // LaunchedEffect body runs on the composition's dispatcher.
            logs = withContext(Dispatchers.IO) { FileLogger.readLogs(500) }
            kotlinx.coroutines.delay(2000)
        }
    }

    // FileLogger.readLogs() returns newest-first (it reverses the file's
    // natural oldest-first order), so the latest entry is the TOP item of this
    // list and a reader parked at scroll offset 0 sees new lines without any
    // scrolling. No forced animateScrollTo(0) here: it used to yank users
    // back to the top on every 2s poll while they were reading older lines
    // further down.

    val entries = remember(logs) { parseEntries(logs) }
    val levelCounts = remember(entries) { entries.groupingBy { it.level }.eachCount() }
    val visibleEntries = remember(entries, levelFilter) {
        if (levelFilter.isEmpty()) entries else entries.filter { it.level in levelFilter }
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { logs = FileLogger.readLogs(500) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Logs", logs))
                        scope.launch { snackbarHostState.showSnackbar("Logs copied to clipboard") }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = { showClearSheet = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            LogLevelFilterRow(
                counts = levelCounts,
                selected = levelFilter,
                onToggle = { level ->
                    levelFilter = if (level in levelFilter) levelFilter - level else levelFilter + level
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Uniform inset (was vertical = xs, half the horizontal) so the
                // panel's rounded corners clear the screen edge on every side
                // instead of being cut off top/bottom.
                .padding(MaterialTheme.spacing.sm)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "No logs yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.statusColors.logSurface,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(MaterialTheme.spacing.sm)
                ) {
                    itemsIndexed(visibleEntries) { index, entry ->
                        // Newest-first, so the day changes when this entry's date
                        // differs from the one *above* it in time — the next item
                        // in the list. The divider sits above the first entry of
                        // that older day, reading downward as you scroll back.
                        LogEntryRow(entry)
                        val older = visibleEntries.getOrNull(index + 1)
                        if (older != null && older.date != entry.date) {
                            LogDateDivider(older.date)
                        }
                    }
                }
            }
        }
    }

    if (showClearSheet) {
        LogsClearSheet(
            onConfirm = {
                FileLogger.clear()
                logs = ""
                showClearSheet = false
            },
            onDismiss = { showClearSheet = false }
        )
    }
}

/**
 * Bottom sheet confirming the destructive "clear logs" action. Mirrors
 * ChatTopBar's ClearChatSheet (drag handle, title, warning copy, outlined
 * Cancel + filled danger Clear) so the two destructive-action sheets in the
 * app look and behave the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsClearSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md)
        ) {
            Text(
                text = "Clear logs?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
            Text(
                text = "This permanently deletes the log file on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.padding(top = MaterialTheme.spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = MaterialTheme.spacing.xs)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = MaterialTheme.spacing.xs)
                ) {
                    Text("Clear")
                }
            }
            Spacer(modifier = Modifier.padding(bottom = MaterialTheme.spacing.lg))
        }
    }
}

/**
 * Level chips, severity-first, each carrying how many lines it matches. The
 * count is the point: it answers "are there any errors in here" without
 * reading a line. A level with none is disabled rather than hidden, so the row
 * does not reflow while the log polls.
 */
@Composable
private fun LogLevelFilterRow(
    counts: Map<Char, Int>,
    selected: Set<Char>,
    onToggle: (Char) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        LOG_LEVELS.forEach { (level, label) ->
            val count = counts[level] ?: 0
            FilterChip(
                selected = level in selected,
                onClick = { onToggle(level) },
                enabled = count > 0,
                label = { Text(if (count > 0) "$label $count" else label) }
            )
        }
    }
}

/** Severity-first, which is the order someone scanning a log actually wants. */
private val LOG_LEVELS = listOf('E' to "Error", 'W' to "Warn", 'I' to "Info", 'D' to "Debug")

/**
 * One parsed line, plus any continuation lines that followed it. A stack
 * trace arrives as many lines with no level of their own; they belong to the
 * entry above rather than being entries in their own right.
 */
private data class LogEntry(
    val date: String,
    val time: String,
    val level: Char,
    val tag: String,
    val message: String
)

/** Matches "2026-08-03 14:23:08.123 E/AgentConn: message". */
private val LOG_LINE_REGEX =
    Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.\d+ ([VDIWE])/([^:]*):\s?(.*)$""")

private fun parseEntries(logs: String): List<LogEntry> {
    val out = mutableListOf<LogEntry>()
    for (line in logs.lineSequence()) {
        val m = LOG_LINE_REGEX.find(line)
        if (m != null) {
            val (date, time, level, tag, message) = m.destructured
            out += LogEntry(date, time, level.first(), tag, message)
        } else if (line.isNotBlank() && out.isNotEmpty()) {
            // Continuation — fold it into the entry it belongs to.
            val last = out.removeAt(out.lastIndex)
            out += last.copy(message = last.message + "\n" + line)
        }
    }
    return out
}

/**
 * Header row then body, rather than one line. The timestamp is auxiliary once
 * you know roughly when — it is dimmed and pushed to the end of the header —
 * while the tag leads, because that is what you scan for. Splitting the body
 * onto its own full-width line is what makes that survive a 300-character
 * message: on one line the timestamp would be shoved off the far end.
 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = levelColor(entry.level)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Colour alone fails for a colour-blind reader, and this is the one
            // screen where spotting an error is the entire point.
            .drawBehind {
                if (entry.level == 'E' || entry.level == 'W') {
                    drawRect(
                        color = levelColor,
                        size = Size(2.dp.toPx(), size.height)
                    )
                }
            }
            .padding(start = MaterialTheme.spacing.sm, top = MaterialTheme.spacing.xxs)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = entry.tag.padEnd(TAG_COLUMN_WIDTH),
                style = LOG_MONO,
                color = TAG_COLOR,
                maxLines = 1
            )
            Text(
                text = levelLabel(entry.level),
                style = LOG_MONO,
                color = levelColor,
                fontWeight = if (entry.level == 'E' || entry.level == 'W') FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = entry.time, style = LOG_MONO, color = TIMESTAMP_COLOR, maxLines = 1)
        }
        Text(
            text = entry.message,
            style = LOG_MONO,
            color = if (entry.level == 'E') levelColor else LOG_BODY_COLOR,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.xs)
        )
    }
}

/** Only where the day actually turns over — the log file outlives a single day. */
@Composable
private fun LogDateDivider(date: String) {
    Text(
        text = date,
        style = LOG_MONO,
        color = TIMESTAMP_COLOR,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.sm)
    )
}

/** Widest tag in the codebase is VoiceTranscribe at 15 — pad to it so the columns line up. */
private const val TAG_COLUMN_WIDTH = 16

private val LOG_MONO
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

private const val LEVEL_LABEL_WIDTH = 6

private fun levelLabel(level: Char) = when (level) {
    'E' -> "ERROR"
    'W' -> "WARN"
    'I' -> "INFO"
    'D' -> "DEBUG"
    else -> "VERB"
}.padEnd(LEVEL_LABEL_WIDTH)

private fun levelColor(level: Char) = when (level) {
    'E' -> Color(0xFFF44747)
    'W' -> Color(0xFFFFA500)
    'I' -> Color(0xFF4EC9B0)
    'D' -> Color(0xFF9CDCFE)
    else -> Color(0xFF878787)
}

/** Tag gets its own fixed colour, independent of level — scannable across lines of any level. */
private val TAG_COLOR = Color(0xFFC586C0)

private val LOG_BODY_COLOR = Color(0xFFD4D4D4)

/** Dimmer than the body: once you know roughly when, the exact time is the least useful part of a line. */
private val TIMESTAMP_COLOR = Color(0xFF6B9166)
