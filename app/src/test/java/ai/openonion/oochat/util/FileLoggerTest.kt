package ai.openonion.oochat.util

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Covers [FileLogger.eRepeating]'s rate limiter — the thing standing between a
 * retry ladder replaying one rejection and `app.log` filling with it — plus the
 * async write queue: I/W/E must reach the file promptly and level ordering must
 * survive D batching. [FileLogger.awaitDrain] is the mechanism under test for
 * promptness, not a wall-clock sleep: it blocks until everything enqueued
 * before it has actually been written (and flushed), so a passing assertion
 * right after it proves the write happened, not that we waited long enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileLoggerTest {

    @Before
    fun setUp() {
        FileLogger.init(ApplicationProvider.getApplicationContext())
        FileLogger.clear()
        FileLogger.resetRepeatLimiter()
    }

    /** [FileLogger] is a process-wide object; don't leave a shortened window behind. */
    @org.junit.After
    fun tearDown() = FileLogger.resetRepeatLimiter()

    private fun linesMentioning(needle: String): List<String> =
        runBlocking { FileLogger.readLogs(500) }.lines().filter { it.contains(needle) }

    @Test
    fun `a repeated identical error is written once, not once per occurrence`() {
        repeat(20) { FileLogger.eRepeating("TestTag", "Agent not connected: 0xAGENT") }
        FileLogger.awaitDrain()

        assertEquals(
            "20 identical failures must collapse to the one line that carries the information",
            1,
            linesMentioning("Agent not connected").size
        )
    }

    @Test
    fun `the collapsed occurrences are counted, not silently dropped`() {
        FileLogger.repeatWindowMs = 50L
        repeat(5) { FileLogger.eRepeating("TestTag", "WS failed: null") }
        // Once the window closes, the next line reports what it absorbed.
        Thread.sleep(80)
        FileLogger.eRepeating("TestTag", "WS failed: null")
        FileLogger.awaitDrain()

        val lines = linesMentioning("WS failed")
        assertEquals(2, lines.size)
        assertTrue(
            "the count of suppressed repeats must survive into the next line; got $lines",
            lines.any { it.contains("[+4 identical suppressed]") }
        )
    }

    @Test
    fun `different messages rate-limit independently`() {
        repeat(3) {
            FileLogger.eRepeating("TestTag", "first failure")
            FileLogger.eRepeating("TestTag", "second failure")
        }
        FileLogger.awaitDrain()

        assertEquals(1, linesMentioning("first failure").size)
        assertEquals(
            "interleaving a second message must not reset the first one's window",
            1,
            linesMentioning("second failure").size
        )
    }

    @Test
    fun `info, warn, and error lines are readable from the file right after awaitDrain`() {
        FileLogger.i("TestTag", "prompt-info")
        FileLogger.w("TestTag", "prompt-warn")
        FileLogger.e("TestTag", "prompt-error")
        FileLogger.awaitDrain()

        assertTrue(linesMentioning("prompt-info").isNotEmpty())
        assertTrue(linesMentioning("prompt-warn").isNotEmpty())
        assertTrue(linesMentioning("prompt-error").isNotEmpty())
    }

    @Test
    fun `level ordering survives debug batching once the queue drains`() {
        FileLogger.d("TestTag", "ORDER_D1")
        FileLogger.i("TestTag", "ORDER_I")
        FileLogger.d("TestTag", "ORDER_D2")
        FileLogger.w("TestTag", "ORDER_W")
        FileLogger.e("TestTag", "ORDER_E")
        FileLogger.awaitDrain()

        val callOrder = runBlocking { FileLogger.readLogs(500) }
            .lines()
            .reversed() // readLogs is newest-first; put back in call order
            .filter { it.contains("ORDER_") }
            .map { line -> Regex("ORDER_\\w+").find(line)!!.value }

        assertEquals(listOf("ORDER_D1", "ORDER_I", "ORDER_D2", "ORDER_W", "ORDER_E"), callOrder)
    }

    @Test
    fun `a line logged the instant init returns still lands after the file is open`() {
        // init() no longer drains before returning — MainActivity.onCreate goes
        // straight on to setContent. What makes that safe is the single FIFO
        // queue: the reopen is already ahead of this line, so the line can't be
        // written before the file exists.
        FileLogger.init(ApplicationProvider.getApplicationContext())
        FileLogger.i("TestTag", "AFTER_INIT_LINE")
        FileLogger.awaitDrain()

        val ordered = runBlocking { FileLogger.readLogs(500) }
            .lines()
            .reversed() // readLogs is newest-first; put back in call order
            .filter { it.contains("Logger initialized") || it.contains("AFTER_INIT_LINE") }

        assertEquals(2, ordered.size)
        assertTrue("the reopen's own line must be written first", ordered[0].contains("Logger initialized"))
        assertTrue(ordered[1].contains("AFTER_INIT_LINE"))
    }

    @Test
    fun `readLogs keeps the newest N lines and drops the rest, streaming the file`() {
        // The tail read replaced readLines()+takeLast() — same answer, without
        // holding a file allowed to reach 5 MB in memory to discard most of it.
        repeat(20) { FileLogger.i("TestTag", "TAIL_$it") }
        FileLogger.awaitDrain()

        val kept = runBlocking { FileLogger.readLogs(5) }
            .lines()
            .mapNotNull { line -> Regex("TAIL_\\d+").find(line)?.value }

        assertEquals(listOf("TAIL_19", "TAIL_18", "TAIL_17", "TAIL_16", "TAIL_15"), kept)
    }

    @Test
    fun `every FileLogger call is mirrored to logcat at the same level`() {
        ShadowLog.reset()

        FileLogger.i("MirrorTag", "mirror-info")
        FileLogger.w("MirrorTag", "mirror-warn")
        FileLogger.e("MirrorTag", "mirror-error")
        FileLogger.awaitDrain()

        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { it.tag == "MirrorTag" && it.type == Log.INFO && it.msg == "mirror-info" })
        assertTrue(logs.any { it.tag == "MirrorTag" && it.type == Log.WARN && it.msg == "mirror-warn" })
        assertTrue(logs.any { it.tag == "MirrorTag" && it.type == Log.ERROR && it.msg == "mirror-error" })
    }

    @Test
    fun `suppressed eRepeating calls do not spam logcat, only the collapsed line does`() {
        ShadowLog.reset()

        repeat(20) { FileLogger.eRepeating("MirrorTag", "spammy failure") }
        FileLogger.awaitDrain()

        val matching = ShadowLog.getLogs().filter { it.tag == "MirrorTag" && it.msg.contains("spammy failure") }
        assertEquals(1, matching.size)
    }
}
