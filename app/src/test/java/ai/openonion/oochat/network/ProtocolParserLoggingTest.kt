package ai.openonion.oochat.network

import ai.openonion.oochat.util.FileLogger
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts on the line [ProtocolParser.parse] actually writes when a frame
 * fails to decode. The sanitiser reduces the frame to type+length, so the
 * only way payload can still reach `app.log` (and, via the logcat mirror,
 * anyone with adb) is through the decoder's own exception message —
 * kotlinx-serialization appends a `JSON input:` copy of the frame to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocolParserLoggingTest {

    /** Mistyped `duration_ms`: a hard decode failure no schema widening removes. */
    private val secret = "SECRET_TRANSCRIPT_PAYLOAD"
    private val malformedFrame =
        """{"type":"llm_result","duration_ms":"abc","content":"$secret","session_id":"sid-42"}"""

    @Before
    fun setUp() {
        FileLogger.init(ApplicationProvider.getApplicationContext())
        FileLogger.clear()
    }

    // readLogs is suspend since the 5 MB read moved off the caller's thread;
    // runBlocking keeps these assertions synchronous without reintroducing that.
    private fun parseAndReadLog(frame: String): String = runBlocking {
        ProtocolParser.parse(frame, outstandingInputIds = emptySet(), isDirect = false)
        FileLogger.awaitDrain()
        FileLogger.readLogs(500)
    }

    @Test
    fun `a rejected frame's payload never reaches the log`() {
        val logs = parseAndReadLog(malformedFrame)

        assertTrue("the frame must have been logged at all: $logs", logs.contains("Malformed server event"))
        assertFalse("the frame's content leaked into app.log: $logs", logs.contains(secret))
        assertFalse("the session id leaked into app.log: $logs", logs.contains("sid-42"))
        assertFalse("the decoder's frame copy leaked into app.log: $logs", logs.contains("JSON input:"))
    }

    @Test
    fun `the diagnostic that names the offending field survives`() {
        val logs = parseAndReadLog(malformedFrame)

        assertTrue("the sanitised frame summary must survive: $logs", logs.contains("type=llm_result"))
        assertTrue("the offending field must still be named: $logs", logs.contains("duration_ms"))
        assertTrue("the expected type must still be named: $logs", logs.contains("double"))
    }

    @Test
    fun `dropping an unusable nested entry is logged as a count, not as content`() {
        val logs = parseAndReadLog(
            """{"type":"files_received","id":"f1","files":[{"path":"/tmp/$secret"},{"name":"b.txt","path":"/tmp/b.txt"}]}"""
        )

        assertTrue("the drop must be visible at all: $logs", logs.contains("Dropped 1 of 2 unusable `files` entries"))
        assertFalse("the dropped entry must not be logged: $logs", logs.contains(secret))
    }
}
