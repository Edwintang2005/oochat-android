package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.domain.model.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMapperTest {

    private fun sampleEntity(
        id: String = "agent-1",
        description: String? = "A sample agent",
        avatarUrl: String? = null,
        lastConnectedAt: Long? = null,
        connectionMode: String = "relay",
        apiKey: String? = null,
        name: String = "Sample Agent"
    ) = AgentEntity(
        id = id,
        address = "0x1111222233334444555566667777888899990000",
        name = name,
        description = description,
        serverUrl = "https://relay.example.org",
        apiKey = apiKey,
        avatarUrl = avatarUrl,
        createdAt = 5000L,
        lastConnectedAt = lastConnectedAt,
        isActive = true,
        connectionMode = connectionMode
    )

    private fun sampleDomain(apiKey: String? = "must-be-stripped") = AgentProfile(
        id = "agent-1",
        address = "0x1111222233334444555566667777888899990000",
        name = "Sample Agent",
        description = "A sample agent",
        serverUrl = "https://relay.example.org",
        apiKey = apiKey,
        avatarUrl = "https://cdn.example.org/avatar.png",
        createdAt = 5000L,
        lastConnectedAt = 6000L,
        isActive = true,
        connectionMode = "relay"
    )

    // ── toDomain ───────────────────────────────────────────────────

    @Test
    fun `toDomain copies every non-null column onto the domain model`() {
        val entity = sampleEntity()

        val domain = AgentMapper.toDomain(entity)

        assertEquals(entity.id, domain.id)
        assertEquals(entity.address, domain.address)
        assertEquals(entity.name, domain.name)
        assertEquals(entity.description, domain.description)
        assertEquals(entity.serverUrl, domain.serverUrl)
        assertEquals(entity.avatarUrl, domain.avatarUrl)
        assertEquals(entity.createdAt, domain.createdAt)
        assertEquals(entity.lastConnectedAt, domain.lastConnectedAt)
        assertEquals(entity.isActive, domain.isActive)
        assertEquals(entity.connectionMode, domain.connectionMode)
    }

    @Test
    fun `toDomain leaves optional columns null when the row has no value`() {
        val entity = sampleEntity(description = null, avatarUrl = null, lastConnectedAt = null)

        val domain = AgentMapper.toDomain(entity)

        assertNull(domain.description)
        assertNull(domain.avatarUrl)
        assertNull(domain.lastConnectedAt)
    }

    @Test
    fun `toDomain carries optional columns through when present`() {
        val entity = sampleEntity(
            description = "Has a description",
            avatarUrl = "https://cdn.example.org/pic.jpg",
            lastConnectedAt = 42_000L
        )

        val domain = AgentMapper.toDomain(entity)

        assertEquals("Has a description", domain.description)
        assertEquals("https://cdn.example.org/pic.jpg", domain.avatarUrl)
        assertEquals(42_000L, domain.lastConnectedAt)
    }

    @Test
    fun `toDomain never surfaces the stored apiKey`() {
        val entity = sampleEntity(apiKey = "sk-super-secret")

        val domain = AgentMapper.toDomain(entity)

        assertNull("apiKey lives only in EncryptedSharedPreferences, never in domain models", domain.apiKey)
    }

    @Test
    fun `toDomain preserves whichever connectionMode the row has`() {
        assertEquals("relay", AgentMapper.toDomain(sampleEntity(connectionMode = "relay")).connectionMode)
        assertEquals("direct", AgentMapper.toDomain(sampleEntity(connectionMode = "direct")).connectionMode)
    }

    // ── toEntity ───────────────────────────────────────────────────

    @Test
    fun `toEntity copies every domain field onto the row`() {
        val domain = sampleDomain()

        val entity = AgentMapper.toEntity(domain)

        assertEquals(domain.id, entity.id)
        assertEquals(domain.address, entity.address)
        assertEquals(domain.name, entity.name)
        assertEquals(domain.description, entity.description)
        assertEquals(domain.serverUrl, entity.serverUrl)
        assertEquals(domain.avatarUrl, entity.avatarUrl)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.lastConnectedAt, entity.lastConnectedAt)
        assertEquals(domain.isActive, entity.isActive)
        assertEquals(domain.connectionMode, entity.connectionMode)
    }

    @Test
    fun `toEntity never persists the apiKey either`() {
        val domain = sampleDomain(apiKey = "sk-another-secret")

        val entity = AgentMapper.toEntity(domain)

        assertNull("the row must never carry apiKey in plaintext", entity.apiKey)
    }

    // ── toDomainList ───────────────────────────────────────────────

    @Test
    fun `toDomainList of an empty list is an empty list`() {
        assertTrue(AgentMapper.toDomainList(emptyList()).isEmpty())
    }

    @Test
    fun `toDomainList maps every row in the input`() {
        val rows = listOf(
            sampleEntity(id = "a"),
            sampleEntity(id = "b"),
            sampleEntity(id = "c")
        )

        val mapped = AgentMapper.toDomainList(rows)

        assertEquals(3, mapped.size)
        assertEquals("a", mapped[0].id)
        assertEquals("b", mapped[1].id)
        assertEquals("c", mapped[2].id)
    }

    @Test
    fun `toDomainList keeps the original row order`() {
        val rows = listOf(
            sampleEntity(name = "Charlie"),
            sampleEntity(name = "Alice"),
            sampleEntity(name = "Bravo")
        )

        val mapped = AgentMapper.toDomainList(rows)

        assertEquals("Charlie", mapped[0].name)
        assertEquals("Alice", mapped[1].name)
        assertEquals("Bravo", mapped[2].name)
    }

    // ── Round-trip ─────────────────────────────────────────────────

    @Test
    fun `mapping to an entity and back loses nothing but the apiKey`() {
        val original = sampleDomain()

        val roundTripped = AgentMapper.toDomain(AgentMapper.toEntity(original))

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.address, roundTripped.address)
        assertEquals(original.name, roundTripped.name)
        assertEquals(original.description, roundTripped.description)
        assertEquals(original.serverUrl, roundTripped.serverUrl)
        assertEquals(original.avatarUrl, roundTripped.avatarUrl)
        assertEquals(original.createdAt, roundTripped.createdAt)
        assertEquals(original.lastConnectedAt, roundTripped.lastConnectedAt)
        assertEquals(original.isActive, roundTripped.isActive)
        assertEquals(original.connectionMode, roundTripped.connectionMode)
        assertNull(roundTripped.apiKey)
    }
}
