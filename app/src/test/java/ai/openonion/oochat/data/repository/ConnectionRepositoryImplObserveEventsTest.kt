package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.ChatEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the bug where ConnectionRepositoryImpl.observeEvents()
 * returned emptyFlow() when connection was null, causing ChatViewModel to
 * silently miss all chat events (thinking / agent reply / tool call).
 *
 * Before the fix: observeEvents() returns emptyFlow() → .collect completes
 * immediately, ChatViewModel's collector exits and never re-subscribes.
 *
 * After the fix: observeEvents() returns a hot MutableSharedFlow that stays
 * open across the "not yet connected" window and forwards every chat event
 * emitted by startCollectingEvents().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryImplObserveEventsTest {

    private lateinit var repository: ConnectionRepositoryImpl
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // keyManager = null is allowed by the test-only seam on the
        // production constructor. Tests do not call connect(), so the
        // nullable seam is sufficient — production callers still pass a
        // real KeyManager.
        repository = ConnectionRepositoryImpl(
            keyManager = null,
            relayUrl = "ws://test.invalid"
        )
    }

    // ── Bug regression: observeEvents must be hot, not emptyFlow ────

    @Test
    fun `observeEvents returns a hot flow even when connection is null`() = runTest(testDispatcher) {
        // Before the fix: observeEvents() returned emptyFlow(), which means
        // .take(1).toList() completes immediately and the collector's job
        // is no longer active after one dispatcher tick.
        //
        // After the fix: observeEvents() returns a hot MutableSharedFlow
        // that stays open — the collector's job remains active while we
        // wait for an event.
        val collectJob: Job = launch {
            repository.observeEvents().take(1).toList()
        }

        // Yield once so the collector subscribes. With a hot flow the
        // collector is parked on the SharedFlow buffer; with emptyFlow()
        // it would already have completed.
        testScheduler.runCurrent()
        assertTrue(
            "observeEvents() must be a hot flow, not an emptyFlow that completes immediately",
            collectJob.isActive
        )

        // Cleanly stop the collector so runTest can finish.
        collectJob.cancel()
    }

    @Test
    fun `observeEvents does not complete silently when not connected`() = runTest(testDispatcher) {
        // Counter-test: pre-fix, .first() would throw NoSuchElementException
        // because emptyFlow() completed without emitting. Post-fix it stays
        // parked, so we use a cancel-based assertion: the collector job
        // should still be active after runCurrent().
        val collectJob = launch {
            // This will only return when an event is emitted. We cancel
            // before any event is emitted to assert the flow stayed open.
            repository.observeEvents().first()
        }
        testScheduler.runCurrent()
        assertTrue(
            "observeEvents() collector must remain active while disconnected",
            collectJob.isActive
        )
        collectJob.cancel()
    }

    // Suppress unused-import warning for ChatEvent reference (kept for
    // clarity of intent; future tests will assert on concrete ChatEvent
    // values once an AgentConnection mock seam lands).
    @Suppress("unused")
    private val _eventTypeRef: Class<ChatEvent> = ChatEvent::class.java
}
