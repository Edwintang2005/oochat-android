package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMapperTest {

    // ── files (MIGRATION_8_9) ─────────────────────────────────────

    @Test
    fun `toEntity encodes files as a JSON array preserving name and path`() {
        val domain = createTestMessage().copy(
            files = listOf(
                ChatFileAttachment(name = "report.pdf", path = "file:///a/report.pdf"),
                ChatFileAttachment(name = "notes.txt", path = "file:///a/notes.txt")
            )
        )

        val entity = MessageMapper.toEntity(domain)

        assertNotNull(entity.files)
        assertTrue(entity.files!!.contains("report.pdf"))
        assertTrue(entity.files!!.contains("notes.txt"))
    }

    @Test
    fun `toDomain then toEntity round-trips files unchanged`() {
        val files = listOf(
            ChatFileAttachment(name = "report.pdf", path = "file:///a/report.pdf"),
            ChatFileAttachment(name = "notes.txt", path = "file:///a/notes.txt")
        )
        val original = createTestMessage().copy(files = files)

        val roundTripped = MessageMapper.toDomain(MessageMapper.toEntity(original))

        assertEquals(files, roundTripped.files)
    }

    @Test
    fun `toDomain leaves files null when the entity column is null`() {
        val entity = createTestEntity().copy(files = null)

        assertNull(MessageMapper.toDomain(entity).files)
    }

    @Test
    fun `toEntity leaves files null when the domain field is null`() {
        val domain = createTestMessage().copy(files = null)

        assertNull(MessageMapper.toEntity(domain).files)
    }

    @Test
    fun `encodeFiles then decodeFiles round-trips a single attachment`() {
        val files = listOf(ChatFileAttachment(name = "a b.txt", path = "file:///x/a b.txt"))

        val decoded = MessageMapper.decodeFiles(MessageMapper.encodeFiles(files))

        assertEquals(files, decoded)
    }

    @Test
    fun `encodeFiles returns null for a null list`() {
        assertNull(MessageMapper.encodeFiles(null))
    }

    @Test
    fun `decodeFiles returns null for a null string`() {
        assertNull(MessageMapper.decodeFiles(null))
    }

    // ── toDomain ───────────────────────────────────────────────────

    @Test
    fun `toDomain maps all non-null fields correctly`() {
        val entity = createTestEntity()
        val domain = MessageMapper.toDomain(entity)

        assertEquals(entity.id, domain.id)
        assertEquals(entity.sessionId, domain.sessionId)
        assertEquals(Role.valueOf(entity.role), domain.role)
        assertEquals(entity.content, domain.content)
        assertEquals(entity.timestamp, domain.timestamp)
        assertEquals(entity.model, domain.model)
        assertEquals(entity.durationMs, domain.durationMs)
    }

    @Test
    fun `toDomain maps nullable fields when null`() {
        val entity = createTestEntity().copy(model = null, durationMs = null)
        val domain = MessageMapper.toDomain(entity)

        assertNull(domain.model)
        assertNull(domain.durationMs)
    }

    @Test
    fun `toDomain converts the role string to the matching Role enum for every value`() {
        for (role in Role.entries) {
            val entity = createTestEntity().copy(role = role.name)
            assertEquals(role, MessageMapper.toDomain(entity).role)
        }
    }

    // ── toEntity ───────────────────────────────────────────────────

    @Test
    fun `toEntity maps all non-null fields correctly`() {
        val domain = createTestMessage()
        val entity = MessageMapper.toEntity(domain)

        assertEquals(domain.id, entity.id)
        assertEquals(domain.sessionId, entity.sessionId)
        assertEquals(domain.role.name, entity.role)
        assertEquals(domain.content, entity.content)
        assertEquals(domain.timestamp, entity.timestamp)
        assertEquals(domain.model, entity.model)
        assertEquals(domain.durationMs, entity.durationMs)
    }

    @Test
    fun `toEntity converts the Role enum to its name string`() {
        for (role in Role.entries) {
            val domain = createTestMessage().copy(role = role)
            assertEquals(role.name, MessageMapper.toEntity(domain).role)
        }
    }

    // ── Round-trip ─────────────────────────────────────────────────

    @Test
    fun `toDomain then toEntity round-trips to an equal entity`() {
        val original = createTestEntity()

        val roundTripped = MessageMapper.toEntity(MessageMapper.toDomain(original))

        assertEquals(original, roundTripped)
    }

    @Test
    fun `toEntity then toDomain round-trips to an equal domain model`() {
        val original = createTestMessage()

        val roundTripped = MessageMapper.toDomain(MessageMapper.toEntity(original))

        assertEquals(original, roundTripped)
    }

    // ── List variants ──────────────────────────────────────────────

    @Test
    fun `toDomainList maps every entity in order`() {
        val entities = listOf(
            createTestEntity().copy(id = "m1"),
            createTestEntity().copy(id = "m2"),
            createTestEntity().copy(id = "m3")
        )

        val domains = MessageMapper.toDomainList(entities)

        assertEquals(listOf("m1", "m2", "m3"), domains.map { it.id })
    }

    @Test
    fun `toDomainList on an empty list returns an empty list`() {
        assertTrue(MessageMapper.toDomainList(emptyList()).isEmpty())
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun createTestEntity() = ChatMessageEntity(
        id = "msg-1",
        sessionId = "session-1",
        role = Role.USER.name,
        content = "Hello",
        timestamp = 1_700_000_000_000L,
        model = "gemini-3-flash-preview",
        durationMs = 1234L
    )

    private fun createTestMessage() = ChatMessage(
        id = "msg-1",
        sessionId = "session-1",
        role = Role.ASSISTANT,
        content = "Hi there",
        timestamp = 1_700_000_000_000L,
        model = "gemini-3-flash-preview",
        durationMs = 1234L
    )
}
