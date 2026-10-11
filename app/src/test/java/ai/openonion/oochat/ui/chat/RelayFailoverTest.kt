package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.testing.FakeOipHost
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The relay link at `wss://oo.openonion.ai/ws/input` fails mid-turn, and the
 * app must get back to the same running turn through it with nothing lost and
 * nothing doubled.
 *
 * What a relay failure leaves behind is a Host whose turn is still running
 * (`ws_router/session.py` cancels only the forwarder when a client socket
 * goes) and a client that must climb back onto the relay and CONNECT with
 * `session_id` and `last_msg_id`. So the test fails the relay in the ways a
 * real one fails, in front of [FakeOipHost], and checks what reaches Room and
 * the screen.
 *
 * The harness, and why the ladder runs on a virtual clock, is in
 * [RelayResumeTestBase].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayFailoverTest : RelayResumeTestBase() {

    // ── the tests ───────────────────────────────────────────────────────

    @Test
    fun `the relay socket dies mid-turn and the app resumes through the relay with nothing lost or doubled`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.drop(liveSocket)
        missWhileDown()

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay restarts mid-turn and the app resumes after its close frame`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1012, reason = "Service Restart")
        missWhileDown()

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay going away mid-turn is a restart too`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1001, reason = "Going Away")
        missWhileDown()

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay is down for three dials and the ladder backs off until it is back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val dialsBefore = relay.dialledAt.size
        relay.refuseUpgrades = 3

        val droppedAt = scheduler.currentTime
        relay.drop(liveSocket)
        missWhileDown()

        pumpUntil("the fourth dial to CONNECT") { relay.connects.size == 2 }
        assertEquals("three refused upgrades, then the one that got through", dialsBefore + 4, relay.dialledAt.size)
        val times = listOf(droppedAt) + relay.dialledAt.drop(dialsBefore)
        val gaps = times.zipWithNext { a, b -> b - a }
        gaps.forEachIndexed { i, gap ->
            val rung = AgentConnection.reconnectBackoffMs(i + 1)
            assertTrue(
                "dial ${i + 1} must wait out its ${rung}ms rung (and not much more): gaps were $gaps",
                gap in rung until rung + SLACK_MS
            )
        }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `the relay is back before the Host and the app keeps trying until the Host re-announces`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.agentAway = 2

        relay.drop(liveSocket)
        missWhileDown()

        pumpUntil("the Host to be reached again") { relay.connects.size == 2 }
        assertEquals("both CONNECTs before the Host was back were turned away", 2, relay.connectsTurnedAway)
        assertResumedThroughRelay(conversation)

        finishAndAssertArrivedOnce(app, conversation)

        assertTrue(
            "the relay's offline error must not be left in the conversation: ${app.items.map { it.agentText() }}",
            app.items.none { it.agentText()?.contains("offline", ignoreCase = true) == true }
        )
    }

    @Test
    fun `the Host loses the relay mid-turn and the app resumes once it is back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val socket = liveSocket

        // The relay says nothing when the Host goes; the app finds out from
        // the answer to its PONG.
        relay.hostGone = true
        relay.ping(socket)
        pumpUntil("the relay to drop the socket") { socket.dead }
        missWhileDown()
        // The Host's own supervisor re-announces within a second, well inside
        // the app's first 2 s rung; its turn kept running throughout.
        relay.hostGone = false

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)

        assertTrue(
            "the relay's offline error must not be left in the conversation: ${app.items.map { it.agentText() }}",
            app.items.none { it.agentText()?.contains("offline", ignoreCase = true) == true }
        )
    }

    @Test
    fun `the relay is down longer than the ladder and the app resumes when the network comes back`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.refuseUpgrades = Int.MAX_VALUE

        relay.drop(liveSocket)
        missWhileDown()

        pumpUntil("the ladder to give up") { app.state is ConnectionState.Disconnected || app.state is ConnectionState.Error }
        assertEquals("the app stops after its last rung", 1, relay.connects.size)

        // The relay is back; the phone tells the app its network is too.
        relay.refuseUpgrades = 0
        online.emit(false)
        online.emit(true)

        pumpUntil("the retry to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `a turn that finished while the relay was down arrives once from the Host's newer session`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        relay.refuseUpgrades = 1

        relay.drop(liveSocket)
        relay.finishUnheard(finishedSession.replace("SID", conversation))

        pumpUntil("the reply to be saved") {
            runBlocking { app.rows(conversation) }.any { it.role == Role.ASSISTANT && it.content == reply }
        }
        assertEquals(conversation, relay.connects.last().string("session_id"))
        val rows = app.rows(conversation)
        assertEquals("the question must survive, exactly once", listOf(question), rows.filter { it.role == Role.USER }.map { it.content })
        assertEquals("the Host's copy of the reply must be saved exactly once", 1, rows.count { it.role == Role.ASSISTANT && it.content == reply })
        pumpUntil("the reply to render") { app.items.any { it.agentText() == reply } }
        assertEquals(1, app.items.count { it.agentText() == reply })
        assertEquals(1, app.items.count { it is ChatItem.User && it.content == question })

        // The tool call streaming when the relay failed never gets its
        // tool_result; CONNECTED saying the turn is over must settle it.
        pumpUntil("the screen to settle", timeoutMs = 2_000L) { app.items.none { it.isInProgress() } }
    }

    @Test
    fun `a normal close from the relay mid-turn waits for the user to reconnect`() = runBlocking {
        val (app, conversation) = connectedMidTurn()

        relay.restart(liveSocket, code = 1000, reason = "")
        missWhileDown()

        // A 1000 close is taken as deliberate: no retry on its own, as the
        // ConnectOnion web client does after any close.
        pumpUntil("the app to show it is disconnected") { app.state is ConnectionState.Disconnected }
        pumpFor(AgentConnection.reconnectBackoffMs(1) * 2)
        assertEquals("nothing may redial before the user asks", 1, relay.connects.size)

        // The user taps Reconnect.
        app.vm.connectToAgent(agent)
        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertEquals("the reconnect must resume the conversation's own session", conversation, relay.connects.last().string("session_id"))
        finishAndAssertArrivedOnce(app, conversation)
    }
}
