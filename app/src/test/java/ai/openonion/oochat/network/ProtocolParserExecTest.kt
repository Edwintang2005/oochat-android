package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolParserExecTest {

    private fun parse(frame: String): ParseResult =
        ProtocolParser.parse(
            text = frame,
            outstandingInputIds = setOf("unrelated-input"),
            isDirect = false
        )

    @Test
    fun `success preserves execution identifier output and data`() {
        val actual = parse(
            """
            {
                "type": "EXEC_RESULT",
                "exec_id": "exec-123",
                "tool": "bash",
                "status": "success",
                "result": "ready",
                "duration_ms": 42
            }
            """.trimIndent()
        )

        assertEquals(
            ParseResult(
                event = ConnectionEvent.ExecResultReceived(
                    execId = "exec-123",
                    status = "success",
                    result = "ready",
                    tool = "bash",
                    durationMs = 42.0
                )
            ),
            actual
        )
    }

    @Test
    fun `error preserves host message and data`() {
        val actual = parse(
            """
            {
                "type": "EXEC_RESULT",
                "exec_id": "exec-456",
                "tool": "bash",
                "status": "error",
                "error": "Command failed",
                "duration_ms": 12
            }
            """.trimIndent()
        )

        assertEquals(
            ParseResult(
                event = ConnectionEvent.ExecResultReceived(
                    execId = "exec-456",
                    status = "error",
                    error = "Command failed",
                    tool = "bash",
                    durationMs = 12.0
                )
            ),
            actual
        )
    }

    @Test
    fun `error accepts missing data for tool and durationMs`() {
        val actual = parse(
            """
            {
                "type": "EXEC_RESULT",
                "exec_id": "exec-789",
                "status": "error",
                "error": "Tool not allowed"
            }
            """.trimIndent()
        )

        assertEquals(
            ParseResult(
                event = ConnectionEvent.ExecResultReceived(
                    execId = "exec-789",
                    status = "error",
                    error = "Tool not allowed"
                )
            ),
            actual
        )
    }

    @Test
    fun `success accepts empty output`() {
        val actual = parse(
            """
            {
                "type": "EXEC_RESULT",
                "exec_id": "exec-empty",
                "status": "success",
                "result": ""
            }
            """.trimIndent()
        )

        assertEquals(
            ParseResult(
                event = ConnectionEvent.ExecResultReceived(
                    execId = "exec-empty",
                    status = "success",
                    result = ""
                )
            ),
            actual
        )
    }

    @Test
    fun `incomplete or unsupported responses are ignored`() {
        val frames = listOf(
            """{"type":"EXEC_RESULT","status":"success","result":"ok"}""",
            """{"type":"EXEC_RESULT","exec_id":" ","status":"success","result":"ok"}""",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","result":"ok"}""",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"pending","result":"ok"}""",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"success"}""",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"error"}"""
        )

        for (frame in frames) {
            assertEquals(
                "Unexpected handling of $frame",
                ParseResult(event = ConnectionEvent.Unknown),
                parse(frame)
            )
        }
    }
}
