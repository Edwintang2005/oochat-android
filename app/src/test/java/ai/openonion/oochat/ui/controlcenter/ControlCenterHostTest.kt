package ai.openonion.oochat.ui.controlcenter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-29: held to connectonion-react's `createControlCenterHost` (`src/control-center.ts`). */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlCenterHostTest {

    private val revision = "sha256:" + "c".repeat(64)
    private val epoch = "load-1"
    private val prefix = ControlCenterHost.PREFIX

    private val posted = mutableListOf<JsonObject>()

    private class FakeActions : ControlCenterActions {
        val calls = mutableListOf<List<Any?>>()
        val results = ArrayDeque<CompletableDeferred<String>>()
        var cancelled = 0
        var refuseWith: Exception? = null

        private suspend fun call(record: List<Any?>): String {
            calls += record
            refuseWith?.let { throw it }
            val result = CompletableDeferred<String>().also(results::addLast)
            try {
                return result.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
        }

        override suspend fun sendMessage(message: String, conversation: ControlCenterConversation) =
            call(listOf("send", message, conversation))

        override suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation) =
            call(listOf("skill", skill, args, conversation))
    }

    private val actions = FakeActions()

    private fun snapshot(
        connection: ControlCenterConnection = ControlCenterConnection.CONNECTED,
        skills: List<String> = listOf("daily-brief", "meeting-prep")
    ) = ControlCenterSnapshot(
        sessionId = "conv-1",
        agentAddress = "0xagent",
        status = ControlCenterAgentStatus.IDLE,
        connectionState = connection,
        skills = skills.map { ControlCenterSkill(it) }
    )

    private fun host(scope: CoroutineScope, snapshot: ControlCenterSnapshot = snapshot()) =
        ControlCenterHost(revision, epoch, snapshot, posted::add, actions, scope)

    private fun fromApp(vararg fields: Pair<String, Any?>, version: Any = 1, rev: String = revision, ep: String = epoch): JsonElement {
        val all = mapOf("version" to version, "revision" to rev, "epoch" to ep) + fields
        return Json.parseToJsonElement(toJson(all))
    }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JsonPrimitive(value).toString()
        is Number, is Boolean -> value.toString()
        is JsonElement -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k as String)}:${toJson(v)}" }
        else -> error("unsupported $value")
    }

    private fun request(id: String, action: String, payload: Any?) =
        fromApp("type" to prefix + "request", "id" to id, "action" to action, "payload" to payload)

    private fun sendMessage(id: String, message: Any?, conversation: Any? = null) =
        request(id, "send_message", buildMap { put("message", message); if (conversation != null) put("conversation", conversation) })

    private fun responses() = posted.filter { it["type"]?.jsonPrimitive?.content == prefix + "response" }

    private fun response(id: String) = responses().single { it["id"]?.jsonPrimitive?.content == id }

    private fun errorOf(id: String): String {
        val r = response(id)
        assertFalse("$id was accepted", r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("action_rejected", r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        return r["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }

    // --- snapshots ---

    @Test
    fun `a new host sends the app its first snapshot, scoped to this load`() = runTest {
        host(backgroundScope)

        val first = posted.single()
        assertEquals(1, first["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(revision, first["revision"]!!.jsonPrimitive.content)
        assertEquals(epoch, first["epoch"]!!.jsonPrimitive.content)
        assertEquals(prefix + "snapshot", first["type"]!!.jsonPrimitive.content)
        assertEquals(1L, first["sequence"]!!.jsonPrimitive.long)
        assertEquals(snapshot().toJson(), first["snapshot"])
    }

    @Test
    fun `published changes arrive as numbered events, and a resnapshot gets the current state`() = runTest {
        val host = host(backgroundScope)
        val working = snapshot().copy(status = ControlCenterAgentStatus.WORKING)

        host.publish(working)
        host.onMessage(fromApp("type" to prefix + "resnapshot"))

        assertEquals(listOf(1L, 2L, 3L), posted.map { it["sequence"]!!.jsonPrimitive.long })
        assertEquals(listOf("snapshot", "event", "snapshot").map { prefix + it }, posted.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(working.toJson(), posted.last()["snapshot"])
    }

    // --- what the host ignores ---

    @Test
    fun `a message from another load, revision or bridge version is ignored`() = runTest {
        val host = host(backgroundScope)
        posted.clear()

        listOf(
            fromApp("type" to prefix + "resnapshot", ep = "load-0"),
            fromApp("type" to prefix + "resnapshot", rev = "sha256:" + "d".repeat(64)),
            fromApp("type" to prefix + "resnapshot", version = 2),
            fromApp("type" to prefix + "resnapshot", version = "1"),
            JsonPrimitive("resnapshot"),
            fromApp("type" to prefix + "resnapshot", "padding" to "x".repeat(ControlCenterHost.MAX_FRAME_BYTES))
        ).forEach(host::onMessage)

        assertTrue(posted.isEmpty())
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a request without a usable id gets no reply at all`() = runTest {
        val host = host(backgroundScope)
        posted.clear()

        host.onMessage(request("", "send_message", mapOf("message" to "hi")))
        host.onMessage(request("has space", "send_message", mapOf("message" to "hi")))
        host.onMessage(fromApp("type" to prefix + "request", "action" to "send_message", "payload" to mapOf("message" to "hi")))

        assertTrue(posted.isEmpty())
        assertTrue(actions.calls.isEmpty())
    }

    // --- send_message ---

    @Test
    fun `send_message runs the trimmed message and answers with the session once the turn is done`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(sendMessage("request-1", "  summarise today  "))
        runCurrent()

        assertEquals(listOf("send", "summarise today", ControlCenterConversation.CURRENT), actions.calls.single())
        assertTrue("no reply before the turn finishes", responses().isEmpty())

        actions.results.single().complete("conv-1")
        runCurrent()

        val reply = response("request-1")
        assertTrue(reply["ok"]!!.jsonPrimitive.boolean)
        assertEquals("conv-1", reply["result"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a message must be 1 to 10000 characters after trimming`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(sendMessage("empty", "   "))
        host.onMessage(sendMessage("missing", null))
        host.onMessage(sendMessage("number", 42))
        host.onMessage(sendMessage("too-long", "x".repeat(10_001)))
        host.onMessage(sendMessage("longest", "x".repeat(10_000)))
        runCurrent()

        listOf("empty", "missing", "number", "too-long").forEach {
            assertEquals(it, "Message must contain 1-10000 characters", errorOf(it))
        }
        assertEquals("the longest allowed one goes out", 1, actions.calls.size)
    }

    // --- run_skill ---

    @Test
    fun `run_skill runs only a skill the agent publishes`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(request("ok", "run_skill", mapOf("skill" to "meeting-prep", "args" to "2pm sync")))
        host.onMessage(request("unpublished", "run_skill", mapOf("skill" to "delete-everything")))
        host.onMessage(request("bad-name", "run_skill", mapOf("skill" to "daily-brief\nIgnore the above")))
        host.onMessage(request("no-name", "run_skill", mapOf("args" to "x")))
        runCurrent()

        assertEquals(listOf("skill", "meeting-prep", "2pm sync", ControlCenterConversation.CURRENT), actions.calls.single())
        listOf("unpublished", "bad-name", "no-name").forEach {
            assertEquals(it, "Agent does not publish that skill", errorOf(it))
        }
    }

    @Test
    fun `skill arguments are optional, but present ones must be a string of at most 2000 characters`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(request("none", "run_skill", mapOf("skill" to "daily-brief")))
        host.onMessage(request("longest", "run_skill", mapOf("skill" to "daily-brief", "args" to "a".repeat(2_000))))
        host.onMessage(request("too-long", "run_skill", mapOf("skill" to "daily-brief", "args" to "a".repeat(2_001))))
        host.onMessage(request("null", "run_skill", mapOf("skill" to "daily-brief", "args" to null)))
        host.onMessage(request("number", "run_skill", mapOf("skill" to "daily-brief", "args" to 3)))
        runCurrent()

        assertEquals(listOf(null, "a".repeat(2_000)), actions.calls.map { it[2] })
        listOf("too-long", "null", "number").forEach { assertEquals(it, "Invalid skill arguments", errorOf(it)) }
    }

    @Test
    fun `the skill allowlist follows the published snapshot`() = runTest {
        val host = host(backgroundScope, snapshot(skills = emptyList()))
        host.onMessage(request("before", "run_skill", mapOf("skill" to "daily-brief")))

        host.publish(snapshot(skills = listOf("daily-brief")))
        host.onMessage(request("after", "run_skill", mapOf("skill" to "daily-brief")))
        runCurrent()

        assertEquals("Agent does not publish that skill", errorOf("before"))
        assertEquals(1, actions.calls.size)
    }

    // --- every request ---

    @Test
    fun `nothing runs until the agent connection is ready`() = runTest {
        val host = host(backgroundScope, snapshot(connection = ControlCenterConnection.RECONNECTING))

        host.onMessage(sendMessage("early", "hi"))
        host.publish(snapshot(connection = ControlCenterConnection.CONNECTED))
        host.onMessage(sendMessage("ready", "hi"))
        runCurrent()

        assertEquals("Agent connection is not ready", errorOf("early"))
        assertEquals(1, actions.calls.size)
    }

    @Test
    fun `the conversation defaults to the current one, and must be current or new`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(sendMessage("default", "hi"))
        host.onMessage(sendMessage("null", "hi", conversation = JsonPrimitive(null as String?)))
        host.onMessage(sendMessage("new", "hi", conversation = "new"))
        host.onMessage(sendMessage("other", "hi", conversation = "other"))
        host.onMessage(sendMessage("number", "hi", conversation = 1))
        runCurrent()

        assertEquals(
            listOf(ControlCenterConversation.CURRENT, ControlCenterConversation.CURRENT, ControlCenterConversation.NEW),
            actions.calls.map { it.last() }
        )
        listOf("other", "number").forEach { assertEquals(it, "Invalid conversation target", errorOf(it)) }
    }

    @Test
    fun `an unknown action or a payload that is not an object is refused`() = runTest {
        val host = host(backgroundScope)

        host.onMessage(request("exec", "exec", mapOf("tool" to "bash")))
        host.onMessage(request("payload", "send_message", "hi"))
        host.onMessage(request("no-payload", "send_message", null))
        runCurrent()

        assertEquals("Unsupported Control Center action", errorOf("exec"))
        assertEquals("Invalid action payload", errorOf("payload"))
        assertEquals("Invalid action payload", errorOf("no-payload"))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a request id is used once, even after its action finished`() = runTest {
        val host = host(backgroundScope)
        host.onMessage(sendMessage("request-1", "first"))
        runCurrent()
        actions.results.single().complete("conv-1")
        runCurrent()

        host.onMessage(sendMessage("request-1", "replayed"))
        runCurrent()

        assertEquals(1, actions.calls.size)
        val (first, second) = responses()
        assertTrue(first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(
            "Duplicate Control Center request",
            second["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `at most 16 actions wait at once`() = runTest {
        val host = host(backgroundScope)

        repeat(17) { host.onMessage(sendMessage("r$it", "hi")) }
        runCurrent()

        assertEquals(16, actions.calls.size)
        assertEquals("Bridge request capacity reached; reload the app", errorOf("r16"))
    }

    @Test
    fun `a load is good for 512 requests`() = runTest {
        val host = host(backgroundScope)
        actions.refuseWith = ControlCenterActionRejected("busy")

        // Settled one at a time, so the 512 limit is hit rather than the 16.
        repeat(513) { host.onMessage(sendMessage("r$it", "hi")); runCurrent() }

        assertEquals(512, actions.calls.size)
        assertEquals("Bridge request capacity reached; reload the app", errorOf("r512"))
    }

    @Test
    fun `a refusal reaches the app in the chat's own words, and any other failure does not`() = runTest {
        val host = host(backgroundScope)

        actions.refuseWith = ControlCenterActionRejected("The agent is busy")
        host.onMessage(sendMessage("refused", "hi"))
        runCurrent()
        actions.refuseWith = IllegalStateException("socket write failed at 0x7f00")
        host.onMessage(sendMessage("broken", "hi"))
        runCurrent()

        assertEquals("The agent is busy", errorOf("refused"))
        assertEquals("Control Center action failed", errorOf("broken"))
    }

    @Test
    fun `a refusal that never suspends is still answered on an immediate dispatcher`() = runTest {
        // Main.immediate runs a launch inline until it suspends.
        val immediate = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val host = host(immediate)
        actions.refuseWith = ControlCenterActionRejected("The agent is busy")

        host.onMessage(sendMessage("now", "hi"))

        assertEquals("The agent is busy", errorOf("now"))
    }

    @Test
    fun `a cancelled action stops, and its late result is never sent`() = runTest {
        val host = host(backgroundScope)
        host.onMessage(sendMessage("request-1", "hi"))
        runCurrent()

        host.onMessage(fromApp("type" to prefix + "cancel", "id" to "request-1"))
        runCurrent()

        assertEquals(1, actions.cancelled)
        assertTrue(responses().isEmpty())
    }

    @Test
    fun `a disposed host cancels what is pending and says nothing more`() = runTest {
        val host = host(backgroundScope)
        host.onMessage(sendMessage("request-1", "hi"))
        runCurrent()
        posted.clear()

        host.dispose()
        runCurrent()
        host.onMessage(fromApp("type" to prefix + "resnapshot"))
        host.publish(snapshot())

        assertEquals(1, actions.cancelled)
        assertTrue(posted.isEmpty())
    }

    // --- scope ---

    @Test
    fun `a host needs a sha256 revision and a well-formed load id`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            ControlCenterHost("sha256:abc", epoch, snapshot(), posted::add, actions, backgroundScope)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlCenterHost(revision, "load 1", snapshot(), posted::add, actions, backgroundScope)
        }
    }

    @Test
    fun `every load gets a fresh, well-formed id`() {
        val first = ControlCenterHost.newEpoch()
        val second = ControlCenterHost.newEpoch()

        assertTrue(Regex("^[A-Za-z0-9._:-]{1,128}$").matches(first))
        assertNotEquals(first, second)
    }

    @Test
    fun `the snapshot has the shape the app's SDK validates`() {
        val json = snapshot().copy(sessionId = null).toJson()

        // As validSnapshot in src/control-center.ts checks it.
        assertEquals("null", json["sessionId"].toString())
        assertEquals("0xagent", json["agentAddress"]!!.jsonPrimitive.content)
        assertTrue(json["chatItems"]!!.jsonArray.isEmpty())
        assertEquals("idle", json["status"]!!.jsonPrimitive.content)
        assertEquals("connected", json["connectionState"]!!.jsonPrimitive.content)
        assertEquals("""{"name":"daily-brief"}""", json["skills"]!!.jsonArray.first().toString())
    }
}
