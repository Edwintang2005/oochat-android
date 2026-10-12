package ai.openonion.oochat.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * One representative fixture for every inbound frame handled by
 * [ProtocolParser]. Detailed field and status assertions remain in the
 * parser-specific test classes.
 */
class ProtocolFrameCoverageTest {

    private data class FrameFixture(
        val name: String,
        val json: String,
        val expectedEvent: KClass<out ConnectionEvent>
    )

    private val frames = listOf(
        FrameFixture("PING", """{"type":"PING"}""", ConnectionEvent.Ping::class),
        FrameFixture("CONNECTED", """{"type":"CONNECTED","status":"connected"}""", ConnectionEvent.Connected::class),
        FrameFixture("AGENT_PROFILE", """{"type":"AGENT_PROFILE","name":"agent","model":"model"}""", ConnectionEvent.AgentProfileReceived::class),
        FrameFixture("DASHBOARD_SNAPSHOT", """{"type":"DASHBOARD_SNAPSHOT","html":"<p>Home</p>"}""", ConnectionEvent.DashboardSnapshotReceived::class),
        FrameFixture("llm_call", """{"type":"llm_call","id":"llm-1","model":"model"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("llm_result", """{"type":"llm_result","id":"llm-1","status":"success"}""", ConnectionEvent.ChatItemUpdated::class),
        FrameFixture("thinking", """{"type":"thinking","id":"thought-1","content":"Planning"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture(
            "tool_call",
            """{"type":"tool_call","id":"event-1","tool_id":"tool-1","name":"bash","args":{}}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture(
            "tool_result",
            """{"type":"tool_result","id":"event-2","tool_id":"tool-1","status":"done","result":"ok"}""",
            ConnectionEvent.ChatItemUpdated::class
        ),
        FrameFixture(
            "plan",
            """{"type":"plan","entries":[{"content":"Build","priority":"high","status":"pending"}]}""",
            ConnectionEvent.PlanUpdated::class
        ),
        FrameFixture("agent_image", """{"type":"agent_image","image":"data:image/png;base64,image"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("assistant", """{"type":"assistant","content":"Hello"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture(
            "session_sync",
            """{"type":"session_sync","session":{"session_id":"session-1","messages":[{"role":"assistant","content":"Hello"}]}}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture(
            "user_input",
            """{"type":"user_input","content":"Hello"}""",
            ConnectionEvent.Unknown::class
        ),
        FrameFixture("ask_user", """{"type":"ask_user","id":"ask-1","question":"Continue?"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("approval_needed", """{"type":"approval_needed","id":"approval-1","tool":"bash"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("ONBOARD_REQUIRED", """{"type":"ONBOARD_REQUIRED","id":"onboard-1","methods":[]}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("ONBOARD_SUCCESS", """{"type":"ONBOARD_SUCCESS","id":"onboard-2","level":"trusted"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture("intent", """{"type":"intent","id":"intent-1","status":"understood"}""", ConnectionEvent.ChatItemUpdated::class),
        FrameFixture("eval", """{"type":"eval","id":"eval-1","status":"done","passed":true}""", ConnectionEvent.ChatItemUpdated::class),
        FrameFixture("compact", """{"type":"compact","id":"compact-1","status":"done"}""", ConnectionEvent.ChatItemUpdated::class),
        FrameFixture(
            "tool_blocked",
            """{"type":"tool_blocked","id":"blocked-1","tool":"bash","reason":"denied","message":"Not allowed"}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture(
            "plan_review",
            """{"type":"plan_review","id":"review-1","plan_content":"Build the project"}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture("files_received", """{"type":"files_received","id":"files-1","files":[]}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture(
            "diff_preview",
            """{"type":"diff_preview","id":"diff-1","path":"src/Main.kt","preview":"diff"}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture(
            "ulw_turns_reached",
            """{"type":"ulw_turns_reached","id":"ulw-1","turns_used":5,"max_turns":10}""",
            ConnectionEvent.ChatItemReceived::class
        ),
        FrameFixture(
            "EXEC_RESULT",
            """{"type":"EXEC_RESULT","exec_id":"exec-1","status":"success","result":"ok"}""",
            ConnectionEvent.ExecResultReceived::class
        ),
        FrameFixture("OUTPUT", """{"type":"OUTPUT","result":"Finished"}""", ConnectionEvent.OutputReceived::class),
        FrameFixture("RUNTIME_INPUT_ACK", """{"type":"RUNTIME_INPUT_ACK","id":"host-input-1"}""", ConnectionEvent.RuntimeInputAcknowledged::class),
        FrameFixture("ERROR: generic", """{"type":"ERROR","message":"Something failed"}""", ConnectionEvent.ConnectionError::class),
        FrameFixture(
            "ERROR: unsupported protocol",
            """{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","protocol":{"name":"oip","version":"0.2"}}""",
            ConnectionEvent.ProtocolRejected::class
        ),
        FrameFixture(
            "ERROR: runtime input rejected",
            """{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED","message":"Runtime input is not accepted","retryable":true}""",
            ConnectionEvent.RuntimeInputRejected::class
        ),
        FrameFixture("mode_changed", """{"type":"mode_changed","id":"mode-1","mode":"plan"}""", ConnectionEvent.ChatItemReceived::class),
        FrameFixture(
            "SESSION_STATUS",
            """{"type":"SESSION_STATUS","session_id":"session-1","status":"connected"}""",
            ConnectionEvent.SessionStatusReceived::class
        ),
        FrameFixture(
            "mode_budget_changed",
            """{"type":"mode_budget_changed","mode":"full-access","turns_left":4}""",
            ConnectionEvent.ModeBudgetChanged::class
        )
    )

    @Test
    fun `listed inbound frames map to their expected parser events`() {
        frames.forEach { fixture ->
            val result = ProtocolParser.parse(
                text = fixture.json,
                outstandingInputIds = emptySet(),
                isDirect = true
            )

            assertTrue(
                "${fixture.name} returned ${result.event::class.simpleName}, " +
                    "expected ${fixture.expectedEvent.simpleName}",
                fixture.expectedEvent.isInstance(result.event)
            )
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

    @Test
    fun `fixture list stays in sync with the documented server frame table`() {
        val protocolPath = sequenceOf(
            Path.of("docs/protocol.md"),
            Path.of("../docs/protocol.md")
        ).firstOrNull(Files::isRegularFile)
            ?: error("Could not find docs/protocol.md from the test working directory")

        val documentedFrames = Files.readAllBytes(protocolPath).toString(Charsets.UTF_8)
            .substringAfter("## Server to client events")
            .substringBefore("### `EXEC_RESULT`")
            .lineSequence()
            .mapNotNull { line ->
                Regex("^\\| `([^`]+)` \\|").find(line)?.groupValues?.get(1)
            }
            .toSet()

        val fixtureFrames = frames
            .map { it.name.substringBefore(":") }
            .toSet()

        assertTrue(
            "Protocol fixtures do not match the server-frame table. " +
                "Missing fixtures: ${documentedFrames - fixtureFrames}; " +
                "undocumented fixtures: ${fixtureFrames - documentedFrames}",
            documentedFrames == fixtureFrames
        )
    }
}
