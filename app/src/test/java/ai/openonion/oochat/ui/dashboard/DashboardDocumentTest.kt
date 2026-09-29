package ai.openonion.oochat.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the dashboard's sandbox boundary: the CSP and the bridge must survive
 * *any* agent-authored HTML, because that HTML is untrusted input.
 *
 * The hostile cases are the regression suite for the injection approach this
 * replaced — locating `<head>` by matching the raw string, which a stray
 * `<head>` inside a comment defeated, moving the CSP meta into that comment and
 * dropping the policy entirely. Ported from oo-chat-web's
 * `build-srcdoc.test.ts`; anything asserting "the CSP is present and
 * authoritative regardless of what the agent wrote" belongs here.
 */
class DashboardDocumentTest {

    private val nonce = "deadbeefdeadbeefdeadbeefdeadbeef"

    /** Everything before the agent's content — the part the agent must not be able to reach. */
    private fun ourHead(doc: String): String = doc.substring(0, doc.indexOf("<body>"))

    private val hostile = listOf(
        "a <head> inside a comment" to
            "<!-- <head> --><html><head><title>T</title></head><body>hi</body></html>",
        "a <head> in text content" to
            "<html><body><p>put &lt;head&gt; here</p><head></head></body></html>",
        "a </body> inside a comment" to "<html><body><!-- </body> --><p>hi</p></body></html>",
        "no head at all" to "<html><body>hi</body></html>",
        "no html or body" to "<div>just a fragment</div>",
        "its own CSP meta first" to
            """<html><head><meta http-equiv="Content-Security-Policy" content="default-src *"></head><body>hi</body></html>""",
        "an unterminated attribute" to """<html><body><div title="oops""",
        "an unterminated comment" to "<html><body><p>hi</p><!-- never closed",
        "an unterminated script tag" to """<html><body><script nonce="""",
        "an empty document" to "",
    )

    // ── The CSP is authoritative ─────────────────────────────────────

    @Test
    fun `the CSP stays ahead of the agent content for every hostile document`() {
        for ((label, html) in hostile) {
            val head = ourHead(DashboardDocument.build(html, nonce))
            // Present, in *our* head, before a single agent byte is parsed.
            assertTrue(label, head.contains(DashboardDocument.cspMeta(nonce)))
            if (html.isNotEmpty()) assertFalse(label, head.contains(html.take(20)))
            // Not commented out: no comment opens before the meta.
            assertFalse(label, head.substringBefore(DashboardDocument.cspMeta(nonce)).contains("<!--"))
        }
    }

    @Test
    fun `the bridge stays ahead of the agent content for every hostile document`() {
        for ((label, html) in hostile) {
            val head = ourHead(DashboardDocument.build(html, nonce))
            assertTrue(label, head.contains(DashboardDocument.bridgeScript(nonce)))
        }
    }

    @Test
    fun `the agent HTML is never rewritten`() {
        val html = "<html><head><style>body{color:red}</style></head><body><h1>Hi</h1></body></html>"
        assertTrue(DashboardDocument.build(html, nonce).contains(html))
    }

    @Test
    fun `the CSP locks down every fetch class an agent could use to phone home`() {
        val csp = DashboardDocument.cspMeta(nonce)
        assertTrue(csp.contains("default-src 'none'"))   // covers connect/frame/script fallbacks
        assertTrue(csp.contains("style-src 'unsafe-inline'"))
        assertTrue(csp.contains("img-src data:"))        // no remote pixels
        assertTrue(csp.contains("font-src data:"))
        assertTrue(csp.contains("script-src 'nonce-$nonce'"))
        // Agent inline scripts must not be admitted alongside the nonce.
        assertFalse(Regex("""script-src[^;"]*'unsafe-inline'""").containsMatchIn(csp))
    }

    @Test
    fun `only the nonced bridge script is admitted`() {
        val doc = DashboardDocument.build("<body><script>alert(1)</script></body>", nonce)
        val tags = Regex("<script[^>]*>").findAll(doc).map { it.value }.toList()
        assertEquals(2, tags.size)  // ours + the agent's, which the CSP refuses to run
        assertEquals(1, tags.count { it.contains("""nonce="$nonce"""") })
    }

    @Test
    fun `the agent's own script tag carries no nonce`() {
        val doc = DashboardDocument.build("""<script src="x.js"></script>""", nonce)
        val agentTag = doc.substringAfter("<body>").substringBefore("</body>")
        assertTrue(agentTag.contains("<script"))
        assertFalse(agentTag.contains("nonce"))
    }

    // ── The wrapper document ─────────────────────────────────────────

    @Test
    fun `charset and viewport are ours, since the agent's copies land in body`() {
        val head = ourHead(
            DashboardDocument.build(
                """<html><head><meta charset="utf-16"></head><body>hi</body></html>""",
                nonce
            )
        )
        assertTrue(head.contains("""<meta charset="utf-8">"""))
        assertTrue(head.contains("""name="viewport""""))
    }

    // ── The body background ──────────────────────────────────────────

    @Test
    fun `the body takes the app's surface, so a page that declares none is not white`() {
        val head = ourHead(DashboardDocument.build("<h1>Home</h1>", nonce, "#101f14", "#e3e9e3"))
        assertTrue(head.contains("body { background: #101f14; color: #e3e9e3; }"))
    }

    @Test
    fun `it is a fallback, not an override - the agent's own body rule comes later`() {
        // Same specificity, so the cascade is decided by document order: ours
        // is in our head, the agent's lands in the body after it.
        val agent = "<style>body{background:#ff00ff}</style><h1>Home</h1>"
        val doc = DashboardDocument.build(agent, nonce, "#101f14", "#e3e9e3")
        val ours = doc.indexOf("body { background: #101f14")
        val theirs = doc.indexOf("body{background:#ff00ff}")
        assertTrue("our body rule is missing entirely", ours >= 0)
        assertTrue("the agent's body rule is missing entirely", theirs >= 0)
        assertTrue("ours must come first so theirs wins the cascade", ours < theirs)
    }

    @Test
    fun `the background rule is inside our style block, not loose in the head`() {
        // Loose text in <head> would be reparented into <body> by the browser
        // and shown to the reader as page content.
        val styles = DashboardDocument.componentStyles("#101f14", "#e3e9e3")
        assertTrue(styles.startsWith("<style>"))
        assertTrue(styles.endsWith("</style>"))
        assertTrue(styles.contains("background: #101f14"))
    }

    @Test
    fun `the agent content is carried in the body`() {
        assertTrue(DashboardDocument.build("<h1>Home</h1>", nonce).contains("<body>\n<h1>Home</h1>\n</body>"))
    }

    // ── The bridge ───────────────────────────────────────────────────

    @Test
    fun `the bridge posts the skill and args from the clicked element, and nothing else`() {
        val src = DashboardDocument.bridgeScript(nonce)
        assertTrue(src.contains("${DashboardDocument.BRIDGE_NAME}.runSkill"))
        assertTrue(src.contains("data-ochat-skill"))
        assertTrue(src.contains("data-ochat-args"))
        // One-way: it reads the frame's own DOM and calls out. It must never
        // accept messages back in or reach for anything outside the document.
        assertFalse(Regex("""addEventListener\(\s*'message'""").containsMatchIn(src))
        assertFalse(src.contains("parent."))
    }

    @Test
    fun `the bridge cancels clicks on external links but leaves same-page fragments alone`() {
        // Neither the CSP nor the opaque origin stops a page navigating itself,
        // so a link is the one way agent HTML could replace Home with a
        // document running under its own CSP.
        val src = DashboardDocument.bridgeScript(nonce)
        assertTrue(src.contains("closest('a[href]')"))
        assertTrue(src.contains("charAt(0) !== '#'"))
        assertTrue(src.contains("preventDefault"))
    }

    @Test
    fun `the skill branch cancels the default action, so an anchor button cannot navigate`() {
        // Without this, <a href="..." data-ochat-skill="x"> would run the skill
        // *and* navigate away.
        val src = DashboardDocument.bridgeScript(nonce)
        val skillBranch = src.substring(src.indexOf("data-ochat-skill"), src.indexOf("runSkill"))
        assertTrue(skillBranch.contains("preventDefault"))
    }

    // ── The nonce ────────────────────────────────────────────────────

    @Test
    fun `generateNonce is 128 bits of hex`() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(DashboardDocument.generateNonce()))
    }

    @Test
    fun `generateNonce is fresh per call - a reused nonce would let one dashboard predict the next`() {
        val nonces = (1..100).map { DashboardDocument.generateNonce() }.toSet()
        assertEquals(100, nonces.size)
    }
}
