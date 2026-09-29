package ai.openonion.oochat.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineUtilsTest {

    @Test
    fun `a block that returns normally becomes Result success`() = runTest {
        val result = runCatchingCancellable { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `an ordinary thrown exception becomes Result failure`() = runTest {
        val result = runCatchingCancellable { throw IllegalStateException("boom") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `a directly-thrown CancellationException is rethrown, not wrapped`() = runTest(UnconfinedTestDispatcher()) {
        var rethrown: Throwable? = null

        val job = launch {
            try {
                runCatchingCancellable { throw CancellationException("cancelled") }
                fail("expected CancellationException to propagate out, not return as a Result")
            } catch (e: CancellationException) {
                rethrown = e
            }
        }
        job.join()

        assertTrue(rethrown is CancellationException)
    }

    @Test
    fun `cancelling the enclosing coroutine propagates rather than resolving to a Result`() = runTest(UnconfinedTestDispatcher()) {
        var reachedCodeAfterTheBlock = false
        var completedViaCancellation = false

        val job = launch {
            runCatchingCancellable {
                // Suspends forever; job.cancel() below is what interrupts
                // this specific suspension point with a real
                // CancellationException.
                kotlinx.coroutines.awaitCancellation()
            }
            // Only reachable if runCatchingCancellable swallowed the
            // cancellation and returned a Result instead of rethrowing.
            reachedCodeAfterTheBlock = true
        }
        job.invokeOnCompletion { cause -> completedViaCancellation = cause is CancellationException }

        job.cancel()
        job.join()

        assertFalse("a real coroutine cancellation must not be swallowed", reachedCodeAfterTheBlock)
        assertTrue(completedViaCancellation)
    }
}
