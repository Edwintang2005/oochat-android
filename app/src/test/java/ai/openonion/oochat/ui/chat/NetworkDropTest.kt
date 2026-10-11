package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.network.AgentConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The phone loses its network mid-turn and gets it back, and the app must
 * reconnect, be replayed what it missed, and show and save the turn once.
 *
 * Unlike a relay failure, the phone knows first: the OS reports the network
 * gone, every dial fails at the DNS lookup, and the moment it comes back the
 * chat screen retries at once instead of waiting out the ladder's rung (the
 * web client's `online` listener does the same). A drop with no FIN leaves the
 * socket looking open until OkHttp's ping goes unanswered, and whatever the
 * Host sent into it is lost.
 *
 * The harness is in [RelayResumeTestBase].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkDropTest : RelayResumeTestBase() {

    /**
     * The phone's network goes. The live socket either fails at once, as
     * Android does when the interface it ran on goes ([abort]), or goes silent.
     */
    private suspend fun cutNetwork(abort: Boolean) {
        val socket = liveSocket
        online.emit(false)
        relay.dialFailure = UnknownHostException("Unable to resolve host \"$RELAY_HOST\": No address associated with hostname")
        if (abort) relay.drop(socket) else socket.unreachable = true
    }

    private suspend fun restoreNetwork() {
        relay.dialFailure = null
        online.emit(true)
    }

    /**
     * Keep the relay answering with the reconnect clock stopped, so nothing
     * waiting out a rung can be what makes [condition] hold.
     */
    private suspend fun pumpWithClockStopped(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            relay.pump()
            if (condition()) return
            scheduler.runCurrent()
            delay(20)
        }
        fail("timed out after ${TIMEOUT_MS}ms waiting for: $what")
    }

    @Test
    fun `the network drops mid-turn and the app resumes with nothing lost or doubled`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val dialsBefore = relay.dialledAt.size

        val droppedAt = scheduler.currentTime
        cutNetwork(abort = true)
        missWhileDown()

        pumpUntil("two dials to fail with no network") { relay.dialledAt.size == dialsBefore + 2 }
        val gaps = (listOf(droppedAt) + relay.dialledAt.drop(dialsBefore)).zipWithNext { a, b -> b - a }
        gaps.forEachIndexed { i, gap ->
            val rung = AgentConnection.reconnectBackoffMs(i + 1)
            assertTrue(
                "dial ${i + 1} must wait out its ${rung}ms rung (and not much more): gaps were $gaps",
                gap in rung until rung + SLACK_MS
            )
        }
        assertEquals("nothing may reach the Host while the network is down", 1, relay.connects.size)

        // Partway into the third rung, the network comes back.
        pumpFor(AgentConnection.reconnectBackoffMs(3) / 2)
        restoreNetwork()
        pumpWithClockStopped("the app to dial as soon as the network is back") {
            relay.dialledAt.size == dialsBefore + 3
        }

        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `a silent drop is found by the ping timeout and what was sent into it is replayed`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        val socket = liveSocket
        val dialsBefore = relay.dialledAt.size

        cutNetwork(abort = false)
        // The Host sends the tool_result down a socket it still thinks is open.
        missWhileDown()

        // OkHttp's ping goes unanswered, and it fails the socket.
        relay.fail(socket, SocketTimeoutException("sent ping but didn't receive pong within 15000ms (after 0 successful ping/pongs)"))
        pumpUntil("a dial to fail with no network") { relay.dialledAt.size == dialsBefore + 1 }

        restoreNetwork()
        pumpUntil("the reconnect to CONNECT") { relay.connects.size == 2 }
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }
}
