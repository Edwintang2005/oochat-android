package ai.openonion.oochat.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * File-based logger for persistent debugging.
 * Writes logs to app's internal storage: /data/data/ai.openonion.oochat/files/logs/
 *
 * Usage:
 *   FileLogger.init(context)
 *   FileLogger.i("AgentConnection", "Connected to agent")
 *
 * Read via adb:
 *   adb shell run-as ai.openonion.oochat cat files/logs/app.log
 * Or live, without waiting on the file at all:
 *   adb logcat
 *
 * Every call is mirrored to android.util.Log (same tag/level) so logcat gives
 * a live view. File writes happen on a single dedicated background thread fed
 * by an ordered queue, so callers never block on disk I/O and cross-level
 * ordering is preserved (one queue, one writer). Reading back is the other
 * half of that promise: [readLogs] suspends onto IO rather than reading the
 * file on its caller's thread. D lines batch and flush once
 * the queue drains; I/W/E flush the instant the writer thread reaches them —
 * both fall out of the same drain loop, see [runLoop].
 */
object FileLogger {

    // android.util.Log is a throwing stub off-device: Robolectric shadows it,
    // but the plain-JUnit tests do not, and every call threw there. The mirror
    // is a convenience channel, so one failure retires it for the process
    // rather than taking the caller down or costing a throw per log line.
    @Volatile private var logcatAvailable = true

    private fun mirror(emit: () -> Unit) {
        if (!logcatAvailable) return
        try {
            emit()
        } catch (_: Throwable) {
            logcatAvailable = false
        }
    }

    private var logFile: File? = null
    private var writer: BufferedWriter? = null

    /** Only ever touched from [writerThread] — single-writer confinement needs no lock. */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024L
    private const val MAX_QUEUE_SIZE = 20_000

    private val queue = LinkedBlockingQueue<Command>(MAX_QUEUE_SIZE)
    private val writerThread by lazy { thread(name = "FileLogger-writer", isDaemon = true) { runLoop() } }

    private sealed class Command {
        class Entry(val timestampMs: Long, val level: String, val tag: String, val msg: String, val flushNow: Boolean) : Command()
        class Reopen(val logDir: File, val file: File) : Command()
        object Clear : Command()
        class Barrier(val latch: CountDownLatch) : Command()
    }

    /**
     * Initialize with application context. Called once from
     * [ai.openonion.oochat.MainActivity.onCreate], on the main thread
     * and ahead of the first frame — so it queues the reopen and returns
     * rather than waiting for the mkdirs/rotate/open to finish. Ordering comes
     * from the single FIFO queue, not from a barrier: everything logged after
     * this call is written after the file is open.
     */
    fun init(context: Context) {
        val logDir = File(context.filesDir, "logs")
        val file = File(logDir, "app.log")
        logFile = file
        writerThread // ensure the single background thread exists
        queue.put(Command.Reopen(logDir, file))
        i("FileLogger", "Logger initialized: ${file.absolutePath}")
    }

    fun d(tag: String, msg: String) {
        mirror { Log.d(tag, msg) }
        enqueueEntry("D", tag, msg, flushNow = false)
    }

    fun i(tag: String, msg: String) {
        mirror { Log.i(tag, msg) }
        enqueueEntry("I", tag, msg, flushNow = true)
    }

    fun w(tag: String, msg: String) {
        mirror { Log.w(tag, msg) }
        enqueueEntry("W", tag, msg, flushNow = true)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        mirror { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) }
        enqueueEntry("E", tag, msg, flushNow = true)
        t?.let { enqueueEntry("E", tag, "  ${it.message}\n${it.stackTraceToString().take(500)}", flushNow = true) }
    }

    /**
     * Error log for a message a retry loop can replay: the first occurrence is
     * written in full, then at most one line per [repeatWindowMs] per distinct
     * (tag, message), carrying the count of what was collapsed into it. Nothing
     * is dropped silently — the diagnostic survives, its repetition does not.
     */
    @Synchronized
    fun eRepeating(tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val entry = repeats["$tag|$msg"]
        if (entry != null && now - entry.lastLoggedAt < repeatWindowMs) {
            entry.suppressed++
            return
        }
        val collapsed = entry?.suppressed ?: 0
        if (entry == null) {
            // Bounded rather than evicted one-by-one: the map only ever holds
            // in-flight failure messages, and losing a count is harmless.
            if (repeats.size >= MAX_REPEAT_KEYS) repeats.clear()
            repeats["$tag|$msg"] = RepeatEntry(now)
        } else {
            entry.lastLoggedAt = now
            entry.suppressed = 0
        }
        val line = if (collapsed > 0) "$msg [+$collapsed identical suppressed]" else msg
        mirror { Log.e(tag, line) }
        enqueueEntry("E", tag, line, flushNow = true)
    }

    private class RepeatEntry(var lastLoggedAt: Long, var suppressed: Int = 0)

    private val repeats = HashMap<String, RepeatEntry>()
    private const val MAX_REPEAT_KEYS = 64

    /** How long one distinct [eRepeating] message is collapsed for; shortened by tests. */
    @Volatile internal var repeatWindowMs = 30_000L

    /** Drops the rate-limiter's memory, so the next [eRepeating] logs in full. Tests only. */
    @Synchronized
    internal fun resetRepeatLimiter() {
        repeats.clear()
        repeatWindowMs = 30_000L
    }

    /**
     * The last [lines] lines of the log file, newest first.
     *
     * Suspending, on [Dispatchers.IO]: this is a disk read of a file allowed to
     * reach [MAX_FILE_SIZE], and LogsScreen re-reads it every two seconds. Only
     * the tail is retained while streaming — the previous `readLines()`
     * materialized the whole file to then throw all but [lines] of it away.
     */
    suspend fun readLogs(lines: Int = 200): String = withContext(Dispatchers.IO) {
        if (lines <= 0) return@withContext ""
        try {
            val file = logFile ?: return@withContext "Logger not initialized"
            if (!file.exists()) return@withContext "Log file not found"
            val tail = ArrayDeque<String>()
            file.useLines { seq ->
                seq.forEach { line ->
                    if (tail.size == lines) tail.removeFirst()
                    tail.addLast(line)
                }
            }
            tail.reversed().joinToString("\n")
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    /** Truncates the file and reopens the writer; queued so it can't race a pending write. */
    fun clear() {
        queue.put(Command.Clear)
        awaitDrain()
    }

    /**
     * Blocks until every command enqueued before this call has been written
     * (and, for the batch it lands in, flushed to disk). Used by [clear] —
     * a user action that should not return while the old lines are still on
     * disk — and by tests to assert promptness without sleeping on wall-clock
     * time. Deliberately not used by [init]: see its doc.
     */
    internal fun awaitDrain(timeoutMs: Long = 2_000) {
        val latch = CountDownLatch(1)
        queue.put(Command.Barrier(latch))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Never blocks the caller: drops the oldest queued line rather than waiting for room. */
    private fun enqueueEntry(level: String, tag: String, msg: String, flushNow: Boolean) {
        writerThread // ensure the single background thread exists
        val entry = Command.Entry(System.currentTimeMillis(), level, tag, msg, flushNow)
        if (!queue.offer(entry)) {
            queue.poll()
            queue.offer(entry)
        }
    }

    /** Body of the single writer thread. All file/writer state is confined here. */
    private fun runLoop() {
        while (true) {
            val first = queue.take()
            var flushed = process(first)
            while (true) {
                val next = queue.poll() ?: break
                if (process(next)) flushed = true
            }
            // Nothing in this batch forced a flush (pure debug traffic) — flush
            // now that the producer has paused, instead of leaving it buffered.
            if (!flushed) safeFlush()
        }
    }

    private fun process(cmd: Command): Boolean = when (cmd) {
        is Command.Entry -> {
            try {
                val timestamp = dateFormat.format(Date(cmd.timestampMs))
                writer?.write("$timestamp ${cmd.level}/${cmd.tag}: ${cmd.msg}\n")
            } catch (_: Exception) {}
            if (cmd.flushNow) { safeFlush(); true } else false
        }
        is Command.Reopen -> { handleReopen(cmd.logDir, cmd.file); true }
        Command.Clear -> { handleClear(); true }
        is Command.Barrier -> { safeFlush(); cmd.latch.countDown(); true }
    }

    private fun handleReopen(logDir: File, file: File) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            writer?.close()
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                file.renameTo(File(logDir, "app.log.bak"))
            }
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true)))
        } catch (_: Exception) {}
    }

    private fun handleClear() {
        try {
            writer?.close()
            logFile?.let { file ->
                file.writeText("")
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true)))
            }
        } catch (_: Exception) {}
    }

    private fun safeFlush() {
        try { writer?.flush() } catch (_: Exception) {}
    }
}
