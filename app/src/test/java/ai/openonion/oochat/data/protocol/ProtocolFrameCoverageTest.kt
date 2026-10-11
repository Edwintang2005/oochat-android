package ai.openonion.oochat.data.protocol

import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.ProtocolParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One representative fixture for every inbound frame handled by
 * [ProtocolParser]. Detailed field and status assertions remain in the
 * parser-specific test classes.
 */
class ProtocolFrameCoverageTest {

    private data class FrameFixture(
        val type: String,
        val json: String,
        val shouldBeIgnored: Boolean = false
    )

    private val frames = listOf(
        FrameFixture("PING", """{"type":"PING"}"""),
        FrameFixture("CONNECTED", """{"type":"CONNECTED","status":"connected"}"""),
        FrameFixture("AGENT_PROFILE", """{"type":"AGENT_PROFILE","name":"agent","model":"model"}"""),
        FrameFixture("DASHBOARD_SNAPSHOT", """{"type":"DASHBOARD_SNAPSHOT","html":"<p>Home</p>"}"""),
        FrameFixture("llm_call", """{"type":"llm_call","id":"llm-1","model":"model"}"""),
        FrameFixture("llm_result", """{"type":"llm_result","id":"llm-1","status":"success"}"""),
        FrameFixture("thinking", """{"type":"thinking","id":"thought-1","content":"Planning"}"""),
        FrameFixture(
            "tool_call",
            """{"type":"tool_call","id":"event-1","tool_id":"tool-1","name":"bash","args":{}}"""
        ),
        FrameFixture(
            "tool_result",
            """{"type":"tool_result","id":"event-2","tool_id":"tool-1","status":"done","result":"ok"}"""
        ),
        FrameFixture(
            "plan",
            """{"type":"plan","entries":[{"content":"Build","priority":"high","status":"pending"}]}"""
        ),
        FrameFixture("agent_image", """{"type":"agent_image","image":"data:image/png;base64,image"}"""),
        FrameFixture("assistant", """{"type":"assistant","content":"Hello"}"""),
        FrameFixture(
            "session_sync",
            """{"type":"session_sync","session":{"session_id":"session-1","messages":[{"role":"assistant","content":"Hello"}]}}"""
        ),
        FrameFixture(
            "user_input",
            """{"type":"user_input","content":"Hello"}""",
            shouldBeIgnored = true
        ),
        FrameFixture("ask_user", """{"type":"ask_user","id":"ask-1","question":"Continue?"}"""),
        FrameFixture("approval_needed", """{"type":"approval_needed","id":"approval-1","tool":"bash"}"""),
        FrameFixture("ONBOARD_REQUIRED", """{"type":"ONBOARD_REQUIRED","id":"onboard-1","methods":[]}"""),
        FrameFixture("ONBOARD_SUCCESS", """{"type":"ONBOARD_SUCCESS","id":"onboard-2","level":"trusted"}"""),
        FrameFixture("intent", """{"type":"intent","id":"intent-1","status":"understood"}"""),
        FrameFixture("eval", """{"type":"eval","id":"eval-1","status":"done","passed":true}"""),
        FrameFixture("compact", """{"type":"compact","id":"compact-1","status":"done"}"""),
        FrameFixture(
            "tool_blocked",
            """{"type":"tool_blocked","id":"blocked-1","tool":"bash","reason":"denied","message":"Not allowed"}"""
        ),
        FrameFixture(
            "plan_review",
            """{"type":"plan_review","id":"review-1","plan_content":"Build the project"}"""
        ),
        FrameFixture("files_received", """{"type":"files_received","id":"files-1","files":[]}"""),
        FrameFixture(
            "diff_preview",
            """{"type":"diff_preview","id":"diff-1","path":"src/Main.kt","preview":"diff"}"""
        ),
        FrameFixture(
            "ulw_turns_reached",
            """{"type":"ulw_turns_reached","id":"ulw-1","turns_used":5,"max_turns":10}"""
        ),
        FrameFixture(
            "EXEC_RESULT",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"success","result":"ok"}"""
        ),
        FrameFixture("OUTPUT", """{"type":"OUTPUT","result":"Finished"}"""),
        FrameFixture("RUNTIME_INPUT_ACK", """{"type":"RUNTIME_INPUT_ACK","id":"host-input-1"}"""),
        FrameFixture("ERROR", """{"type":"ERROR","message":"Something failed"}"""),
        FrameFixture("mode_changed", """{"type":"mode_changed","id":"mode-1","mode":"plan"}"""),
        FrameFixture(
            "SESSION_STATUS",
            """{"type":"SESSION_STATUS","session_id":"session-1","status":"connected"}"""
        )
    )

    @Test
    fun `every inbound frame has a parser fixture`() {
        frames.forEach { fixture ->
            val result = ProtocolParser.parse(
                text = fixture.json,
                outstandingInputIds = emptySet(),
                isDirect = true
            )

            if (fixture.shouldBeIgnored) {
                assertTrue(
                    "${fixture.type} should be intentionally ignored",
                    result.event is ConnectionEvent.Unknown
                )
            } else {
                assertFalse(
                    "${fixture.type} was not recognized by ProtocolParser",
                    result.event is ConnectionEvent.Unknown
                )
            }
        }
    }

    @Test
    fun `unknown frame types are ignored safely`() {
        val result = ProtocolParser.parse(
            text = """{"type":"FUTURE_OIP_FRAME","value":"ignored"}""",
            outstandingInputIds = emptySet(),
            isDirect = true
        )

        assertTrue(result.event is ConnectionEvent.Unknown)
    }
}
