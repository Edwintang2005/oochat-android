package ai.openonion.oochat.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grace period in [offlineSustainedFor], on virtual time — no
 * `ConnectivityManager`, no Robolectric, no real clock.
 *
 * `false` here means "say nothing", `true` means "tell the user they are
 * offline", so the expected lists below read as what the banner was told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {

    private val grace = 2_000L

    /**
     * Collects [offlineSustainedFor]'s output into a list that [body] can
     * assert against while driving [online] and advancing the clock. The
     * collector runs unconfined on the same scheduler, so an emission lands
     * before the next assertion rather than a `runCurrent()` later.
     */
    private fun withGrace(
        body: suspend kotlinx.coroutines.test.TestScope.(
            online: MutableStateFlow<Boolean>,
            announced: List<Boolean>
        ) -> Unit
    ) = runTest {
        val online = MutableStateFlow(true)
        val announced = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            online.offlineSustainedFor(grace).toList(announced)
        }
        body(online, announced)
        job.cancel()
    }

    @Test
    fun `a live network announces nothing`() = withGrace { _, announced ->
        testScheduler.advanceTimeBy(grace * 5)
        assertEquals(listOf(false), announced)
    }

    @Test
    fun `an outage inside the grace period is never announced`() = withGrace { online, announced ->
        online.value = false
        // Exactly the threshold: advanceTimeBy runs what is scheduled strictly
        // before it, so the grace timer has not fired yet.
        testScheduler.advanceTimeBy(grace)

        assertEquals(listOf(false), announced)
    }

    @Test
    fun `an outage that outlasts the grace period is announced`() = withGrace { online, announced ->
        online.value = false
        testScheduler.advanceTimeBy(grace + 1)

        assertEquals(listOf(false, true), announced)
    }

    @Test
    fun `the network returning clears the announcement immediately`() = withGrace { online, announced ->
        online.value = false
        testScheduler.advanceTimeBy(grace + 1)
        assertEquals(listOf(false, true), announced)

        online.value = true
        // No advance at all: recovery is not debounced, because a banner that
        // has stopped being true has nothing to gain from lingering.
        testScheduler.runCurrent()

        assertEquals(listOf(false, true, false), announced)
    }

    @Test
    fun `a network that keeps flapping under the grace period announces nothing`() =
        withGrace { online, announced ->
            // A Wi-Fi-to-cellular handover, repeated. Each gap ends before its
            // own timer fires, and mapLatest cancels the pending one — the
            // outage never accumulates across gaps.
            repeat(5) {
                online.value = false
                testScheduler.advanceTimeBy(grace / 2)
                online.value = true
                testScheduler.advanceTimeBy(grace / 2)
            }

            assertEquals(listOf(false), announced)
        }

    @Test
    fun `an outage still announces after an earlier flap reset the timer`() =
        withGrace { online, announced ->
            online.value = false
            testScheduler.advanceTimeBy(grace / 2)
            online.value = true
            testScheduler.runCurrent()

            online.value = false
            testScheduler.advanceTimeBy(grace + 1)

            assertEquals(listOf(false, true), announced)
        }
}
