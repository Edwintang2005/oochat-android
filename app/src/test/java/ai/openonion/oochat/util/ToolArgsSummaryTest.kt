package ai.openonion.oochat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [summarizeToolCall] — the "what am I looking
 * at" summary shared by [ai.openonion.oochat.ui.chat.components.ToolCallBubble]
 * (the generic fallback card) and the approval gate/receipt in
 * ActionCards.kt. The dedicated per-tool cards in ToolCallCards.kt
 * (BashCallCard, FileCallCard, ...) extract their own title directly and
 * don't go through this — see ToolCallCardsComposeTest for those.
 */
class ToolArgsSummaryTest {

    @Test
    fun `bash tool summarizes to its command`() {
        val summary = summarizeToolCall("bash", mapOf("command" to "ls -la", "description" to "list files"))

        assertEquals("ls -la", summary)
    }

    @Test
    fun `bash tool without a command key falls back to the compact summary`() {
        val summary = summarizeToolCall("bash", mapOf("description" to "list files"))

        assertEquals("description=list files", summary)
    }

    @Test
    fun `file-shaped tools summarize to their path, trying file_path then path then filename`() {
        assertEquals("src/App.tsx", summarizeToolCall("edit", mapOf("file_path" to "src/App.tsx")))
        assertEquals("package.json", summarizeToolCall("read", mapOf("path" to "package.json")))
        assertEquals("out.txt", summarizeToolCall("write", mapOf("filename" to "out.txt", "content" to "hi")))
    }

    @Test
    fun `an unrecognised tool falls back to a compact key=value summary`() {
        val summary = summarizeToolCall("send_email", mapOf("to" to "a@b.com", "subject" to "hi"))

        assertEquals("to=a@b.com, subject=hi", summary)
    }

    @Test
    fun `blank-valued entries are dropped from the compact summary`() {
        val summary = summarizeToolCall("send_email", mapOf("to" to "a@b.com", "cc" to ""))

        assertEquals("to=a@b.com", summary)
    }

    @Test
    fun `null or empty args summarize to null`() {
        assertNull(summarizeToolCall("bash", null))
        assertNull(summarizeToolCall("bash", emptyMap()))
    }

    @Test
    fun `all-blank args summarize to null instead of a trailing comma`() {
        assertNull(summarizeToolCall("send_email", mapOf("to" to "", "cc" to "")))
    }

    @Test
    fun `a summary longer than maxLength is truncated with a trailing ellipsis`() {
        val longValue = "x".repeat(100)

        val summary = summarizeToolCall("unknown_tool", mapOf("value" to longValue), maxLength = 20)

        assertEquals(20, summary?.length)
        assertTrue(summary!!.endsWith("…"))
    }
}
