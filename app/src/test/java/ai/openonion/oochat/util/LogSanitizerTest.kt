package ai.openonion.oochat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    // ── contentSummary ───────────────────────────────────────────────

    @Test
    fun `contentSummary drops the raw text, keeping only a length`() {
        val secret = LogSanitizer.contentSummary("my password is hunter2")

        assertFalse(secret.contains("password"))
        assertFalse(secret.contains("hunter2"))
    }

    @Test
    fun `contentSummary's reported length matches the input`() {
        assertEquals("len=5", LogSanitizer.contentSummary("hello"))
        assertEquals("len=0", LogSanitizer.contentSummary(""))
    }

    // ── jsonTypeSummary ──────────────────────────────────────────────

    @Test
    fun `jsonTypeSummary surfaces only the type field from a well-formed frame`() {
        val frame = """{"type":"assistant","content":"the secret answer","session_id":"sid-123"}"""

        val summary = LogSanitizer.jsonTypeSummary(frame)

        assertTrue(summary.contains("type=assistant"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("sid-123"))
    }

    @Test
    fun `a frame with no type field falls back to a length-only summary`() {
        val frame = """{"foo":"bar"}"""

        val summary = LogSanitizer.jsonTypeSummary(frame)

        assertFalse(summary.contains("type="))
        assertTrue(summary.contains("len=${frame.length}"))
    }

    @Test
    fun `malformed or adversarial input never crashes jsonTypeSummary`() {
        // Every inbound WebSocket frame passes through here, including
        // malformed ones from a buggy or hostile relay — throwing is not
        // an option regardless of what shape the input takes.
        val hostileInputs = listOf(
            "",
            "not json at all",
            "{".repeat(10_000),
            "\"type\":",
            "{\"type\": \"" + "x".repeat(5_000) + "\"}"
        )

        hostileInputs.forEach { LogSanitizer.jsonTypeSummary(it) }
    }

    @Test
    fun `a secret sitting right next to the type field still doesn't leak`() {
        val frame = """{"type":"ERROR","error":"invite_code=SUPER-SECRET-CODE-123"}"""

        val summary = LogSanitizer.jsonTypeSummary(frame)

        assertTrue(summary.contains("type=ERROR"))
        assertFalse(summary.contains("SUPER-SECRET-CODE-123"))
    }

    // ── decodeErrorSummary ───────────────────────────────────────────

    @Test
    fun `decodeErrorSummary keeps the diagnostic and drops the frame copy`() {
        val message = "Unexpected JSON token at offset 40: Failed to parse type 'double' " +
            "for input 'abc' at path: \$.duration_ms\n" +
            """JSON input: {"type":"llm_result","content":"the whole transcript"}"""

        val summary = LogSanitizer.decodeErrorSummary(message)

        assertTrue(summary.contains("\$.duration_ms"))
        assertFalse(summary.contains("JSON input:"))
        assertFalse(summary.contains("the whole transcript"))
    }

    @Test
    fun `decodeErrorSummary drops the windowed frame copy too`() {
        // Over 200 chars the decoder appends a 60-char window instead of the
        // whole frame — still content, still must not survive.
        val message = "Unexpected JSON token at offset 40: boom at path: \$.x\n" +
            "JSON input: .....\"content\":\"a window of the transcript\"....."

        assertFalse(LogSanitizer.decodeErrorSummary(message).contains("window of the transcript"))
    }

    @Test
    fun `decodeErrorSummary survives a null or blank message`() {
        assertEquals("no detail", LogSanitizer.decodeErrorSummary(null))
        assertEquals("no detail", LogSanitizer.decodeErrorSummary(""))
        assertEquals("no detail", LogSanitizer.decodeErrorSummary("\n JSON input: secret"))
    }
}
