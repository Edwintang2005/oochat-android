package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.domain.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMapperTest {

    // ── toDomain ───────────────────────────────────────────────────

    @Test
    fun `toDomain maps all non-null fields correctly`() {
        val entity = createTestEntity()
        val domain = SessionMapper.toDomain(entity)

        assertEquals(entity.id, domain.id)
        assertEquals(entity.agentId, domain.agentId)
        assertEquals(entity.title, domain.title)
        assertEquals(entity.createdAt, domain.createdAt)
        assertEquals(entity.updatedAt, domain.updatedAt)
        assertEquals(entity.messageCount, domain.messageCount)
        assertEquals(entity.lastMessagePreview, domain.lastMessagePreview)
    }

    @Test
    fun `toDomain maps lastMessagePreview when null`() {
        val entity = createTestEntity().copy(lastMessagePreview = null)

        assertNull(SessionMapper.toDomain(entity).lastMessagePreview)
    }

    @Test
    fun `toDomain preserves a zero messageCount rather than defaulting it away`() {
        val entity = createTestEntity().copy(messageCount = 0)

        assertEquals(0, SessionMapper.toDomain(entity).messageCount)
    }

    // ── toEntity ───────────────────────────────────────────────────

    @Test
    fun `toEntity maps all non-null fields correctly`() {
        val domain = createTestSession()
        val entity = SessionMapper.toEntity(domain)

        assertEquals(domain.id, entity.id)
        assertEquals(domain.agentId, entity.agentId)
        assertEquals(domain.title, entity.title)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.updatedAt, entity.updatedAt)
        assertEquals(domain.messageCount, entity.messageCount)
        assertEquals(domain.lastMessagePreview, entity.lastMessagePreview)
    }

    @Test
    fun `toEntity maps lastMessagePreview when null`() {
        val domain = createTestSession().copy(lastMessagePreview = null)

        assertNull(SessionMapper.toEntity(domain).lastMessagePreview)
    }

    // ── Round-trip ─────────────────────────────────────────────────

    @Test
    fun `toDomain then toEntity round-trips to an equal entity`() {
        val original = createTestEntity()

        assertEquals(original, SessionMapper.toEntity(SessionMapper.toDomain(original)))
    }

    @Test
    fun `toEntity then toDomain round-trips to an equal domain model`() {
        val original = createTestSession()

        assertEquals(original, SessionMapper.toDomain(SessionMapper.toEntity(original)))
    }

    // ── List variant ───────────────────────────────────────────────

    @Test
    fun `toDomainList maps every entity in order`() {
        val entities = listOf(
            createTestEntity().copy(id = "s1"),
            createTestEntity().copy(id = "s2"),
            createTestEntity().copy(id = "s3")
        )

        val domains = SessionMapper.toDomainList(entities)

        assertEquals(listOf("s1", "s2", "s3"), domains.map { it.id })
    }

    @Test
    fun `toDomainList on an empty list returns an empty list`() {
        assertTrue(SessionMapper.toDomainList(emptyList()).isEmpty())
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun createTestEntity() = ChatSessionEntity(
        id = "session-1",
        agentId = "agent-1",
        title = "Test conversation",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        messageCount = 5,
        lastMessagePreview = "See you later"
    )

    private fun createTestSession() = ChatSession(
        id = "session-1",
        agentId = "agent-1",
        title = "Another conversation",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        messageCount = 3,
        lastMessagePreview = "Talk soon"
    )
}
