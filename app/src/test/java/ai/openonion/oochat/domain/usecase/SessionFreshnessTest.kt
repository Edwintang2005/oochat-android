package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.domain.model.stableAssistantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SessionFreshness] — the "is the local copy the latest
 * version" decision, kept as a pure function so it can be exercised without
 * Room, a WebSocket, or Robolectric.
 */
class SessionFreshnessTest {

    private fun idsOf(vararg contents: String) = contents.map { stableAssistantId(it) }.toSet()

    @Test
    fun `a local copy holding every server entry is up to date`() {
        val server = listOf("first reply", "second reply")

        val missing = SessionFreshness.missingEntries(server, idsOf("first reply", "second reply"))

        assertTrue(missing.isEmpty())
        assertFalse(SessionFreshness.isLocalStale(server, idsOf("first reply", "second reply")))
    }

    @Test
    fun `only the entries the local copy lacks are reported, oldest first`() {
        val server = listOf("first reply", "second reply", "third reply")

        val missing = SessionFreshness.missingEntries(server, idsOf("first reply"))

        assertEquals(listOf("second reply", "third reply"), missing)
        assertTrue(SessionFreshness.isLocalStale(server, idsOf("first reply")))
    }

    @Test
    fun `an empty local copy is stale against any non-empty server transcript`() {
        val missing = SessionFreshness.missingEntries(listOf("only reply"), emptySet())

        assertEquals(listOf("only reply"), missing)
    }

    @Test
    fun `an empty server transcript never makes the local copy stale`() {
        assertFalse(SessionFreshness.isLocalStale(emptyList(), idsOf("something local")))
    }

    @Test
    fun `blank and whitespace-only server entries are dropped`() {
        // The server sends `content: null` for tool-only assistant turns and
        // the wire mapper turns those into empty strings; they are not
        // transcript and must not count as "the local copy is behind".
        val missing = SessionFreshness.missingEntries(listOf("", "   ", "\n"), emptySet())

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `surrounding whitespace does not make an already-local entry look new`() {
        // stableAssistantId trims before hashing, so the local row written
        // from "hello" must match a server replay of "  hello\n".
        val missing = SessionFreshness.missingEntries(listOf("  hello\n"), idsOf("hello"))

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `the same content repeated in one transcript is taken once`() {
        // Identity is content-derived, so two identical replies collapse to
        // one chat item downstream — reporting both would persist a row and
        // then immediately REPLACE it with itself.
        val missing = SessionFreshness.missingEntries(listOf("same", "other", "same"), emptySet())

        assertEquals(listOf("same", "other"), missing)
    }

    @Test
    fun `a server transcript that is a subset of local history is not newer`() {
        // The server restarted its own session and replayed less than we
        // hold; "newer" must mean "has content we lack", never "differs".
        val missing = SessionFreshness.missingEntries(
            listOf("first reply"),
            idsOf("first reply", "second reply")
        )

        assertTrue(missing.isEmpty())
    }
}
