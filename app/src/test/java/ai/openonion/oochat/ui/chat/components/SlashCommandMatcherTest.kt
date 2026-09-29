package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * No agent we can connect to publishes a skill, so the palette's end-to-end
 * path can't be exercised on a device — these cases are the confidence.
 * Ported case-for-case from oo-chat-web's `skillMatchRank` and `slashQuery`.
 */
class SlashCommandMatcherTest {

    private fun skill(name: String, description: String? = null) = AgentSkill(name, description)

    private val skills = listOf(
        skill("linkedin-engagement", "Engage LinkedIn feed posts using verified browser workflows"),
        skill("web-scraping", "Read a web page and extract structured text from it"),
        skill("pdf"),
        skill("linkedin-post", "Write ready-to-paste LinkedIn post text")
    )

    private fun names(candidates: List<AgentSkill>) = candidates.map { it.name }

    // ── When the palette is open at all ──────────────────────────────

    @Test
    fun `a bare slash opens the palette on everything`() {
        assertEquals("", SlashCommandMatcher.query("/"))
        assertEquals(skills, SlashCommandMatcher.candidates(skills, "/"))
    }

    @Test
    fun `text that does not start with a slash never opens it`() {
        assertNull(SlashCommandMatcher.query("hello /pdf"))
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(skills, "hello /pdf"))
    }

    @Test
    fun `an empty field does not open it`() {
        assertNull(SlashCommandMatcher.query(""))
    }

    @Test
    fun `a space commits the command and everything after is arguments`() {
        assertNull(SlashCommandMatcher.query("/pdf "))
        assertNull(SlashCommandMatcher.query("/pdf report.pdf"))
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(skills, "/pdf report.pdf"))
    }

    @Test
    fun `the token is matched case-insensitively`() {
        assertEquals(listOf("pdf"), names(SlashCommandMatcher.candidates(skills, "/PDF")))
        // Ranked case-insensitively, but the agent's own spelling is what
        // comes back — that is the name the completion has to send.
        assertEquals(
            listOf("PDF"),
            names(SlashCommandMatcher.candidates(listOf(skill("PDF")), "/pdf"))
        )
    }

    // ── The description rides along with the match ───────────────────

    @Test
    fun `a match carries the skill's description, which is what the palette shows`() {
        val match = SlashCommandMatcher.candidates(skills, "/web").single()
        assertEquals("web-scraping", match.name)
        assertEquals("Read a web page and extract structured text from it", match.description)
    }

    @Test
    fun `a skill with no description still matches - the palette shows the name alone`() {
        val match = SlashCommandMatcher.candidates(skills, "/pdf").single()
        assertEquals("pdf", match.name)
        assertNull(match.description)
    }

    @Test
    fun `the description is not searched - only the name is`() {
        // "browser" appears in linkedin-engagement's description and in no
        // name, so it must not match: the command you type is the name.
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(skills, "/browser"))
    }

    // ── Ranking: prefix, then substring, then subsequence ────────────

    @Test
    fun `prefix beats substring`() {
        val candidates = SlashCommandMatcher.candidates(
            listOf(skill("web-scraping"), skill("scraper")),
            "/scrap"
        )
        assertEquals(listOf("scraper", "web-scraping"), names(candidates))
    }

    @Test
    fun `substring beats a subsequence`() {
        val candidates = SlashCommandMatcher.candidates(
            listOf(skill("linkedin-engagement"), skill("web-scraping")),
            "/eng"
        )
        assertEquals(listOf("linkedin-engagement", "web-scraping"), names(candidates))
    }

    @Test
    fun `a subsequence still matches - the forgiving tier`() {
        assertEquals(
            listOf("linkedin-engagement"),
            names(SlashCommandMatcher.candidates(skills, "/linkedeng"))
        )
    }

    @Test
    fun `equal ranks keep the agent's own ordering`() {
        assertEquals(
            listOf("linkedin-engagement", "linkedin-post"),
            names(SlashCommandMatcher.candidates(skills, "/linkedin"))
        )
    }

    @Test
    fun `letters out of order are not a match`() {
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(skills, "/fdp"))
    }

    @Test
    fun `a query longer than the name is not a match`() {
        assertEquals(-1, SlashCommandMatcher.rank("pdf", "pdfx"))
    }

    @Test
    fun `nothing matching produces no candidates - never an empty palette`() {
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(skills, "/zzz"))
    }

    @Test
    fun `an agent with no published skills produces nothing at all`() {
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(emptyList(), "/"))
        assertEquals(emptyList<AgentSkill>(), SlashCommandMatcher.candidates(emptyList(), "/pdf"))
    }

    @Test
    fun `rank tiers are exactly prefix 0, substring 1, subsequence 2`() {
        assertEquals(0, SlashCommandMatcher.rank("linkedin-engagement", "link"))
        assertEquals(1, SlashCommandMatcher.rank("linkedin-engagement", "engage"))
        assertEquals(2, SlashCommandMatcher.rank("linkedin-engagement", "linkedeng"))
    }

    // ── Completion ───────────────────────────────────────────────────

    @Test
    fun `selecting a skill leaves a trailing space, which is what closes the palette`() {
        val completed = SlashCommandMatcher.completion(skill("pdf", "Read and edit PDFs"))
        // The description never reaches the field — only the command does.
        assertEquals("/pdf ", completed)
        assertNull(SlashCommandMatcher.query(completed))
    }
}
