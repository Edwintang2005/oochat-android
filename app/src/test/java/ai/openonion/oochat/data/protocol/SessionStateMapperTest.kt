package ai.openonion.oochat.data.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateMapperTest {

    @Test
    fun `sessionId and turn pass through unchanged`() {
        val wire = SessionState(sessionId = "sess-abc", turn = 12, messages = null)

        val domain = wire.toDomain()

        assertEquals("sess-abc", domain.sessionId)
        assertEquals(12, domain.turn)
    }

    @Test
    fun `null sessionId and turn stay null after mapping`() {
        val wire = SessionState(sessionId = null, turn = null, messages = null)

        val domain = wire.toDomain()

        assertNull(domain.sessionId)
        assertNull(domain.turn)
    }

    @Test
    fun `messageCount reflects the size of the messages list`() {
        val wire = SessionState(
            sessionId = "sess-2",
            turn = 4,
            messages = listOf(
                SessionMessage(role = "user", content = "first"),
                SessionMessage(role = "assistant", content = "second"),
                SessionMessage(role = "user", content = "third"),
                SessionMessage(role = "assistant", content = "fourth")
            )
        )

        val domain = wire.toDomain()

        assertEquals(4, domain.messageCount)
    }

    @Test
    fun `a missing messages list maps to a zero count, not null`() {
        val wire = SessionState(sessionId = "sess-3", turn = 0, messages = null)

        val domain = wire.toDomain()

        assertEquals(0, domain.messageCount)
    }

    @Test
    fun `a wire trace array is dropped at parse, not carried into the app`() {
        // SessionState has no `trace` field and every decoder is
        // ignoreUnknownKeys, so the array never becomes a JsonElement tree.
        // A frame carrying one must still parse — the field is ignored, not
        // rejected.
        val json = Json { ignoreUnknownKeys = true }

        val wire = json.decodeFromString<SessionState>(
            """{"session_id":"sess-4","turn":2,"messages":[],"trace":[{"step":"a"},{"step":"b"}]}"""
        )

        assertEquals("sess-4", wire.sessionId)
        assertEquals(0, wire.toDomain().messageCount)
    }
}
