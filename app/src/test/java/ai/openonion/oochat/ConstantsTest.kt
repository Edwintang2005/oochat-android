package ai.openonion.oochat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Constants.isDefaultServerUrl], especially the trailing-slash
 * normalisation added to defend against user-typed URLs like
 * "https://oo.openonion.ai/" which previously fell through to
 * "is direct" and produced WS URLs with `//ws` segments.
 */
class ConstantsTest {

    @Test
    fun `matches canonical https URL without slash`() {
        assertTrue(Constants.isDefaultServerUrl("https://oo.openonion.ai"))
    }

    @Test
    fun `matches canonical https URL with trailing slash`() {
        // The user-tap case: copy-paste from a browser bookmark strips
        // the trailing slash, but a typed address may keep it.
        assertTrue(Constants.isDefaultServerUrl("https://oo.openonion.ai/"))
    }

    @Test
    fun `matches wss variant without slash`() {
        assertTrue(Constants.isDefaultServerUrl("wss://oo.openonion.ai"))
    }

    @Test
    fun `matches wss variant with trailing slash`() {
        assertTrue(Constants.isDefaultServerUrl("wss://oo.openonion.ai/"))
    }

    @Test
    fun `matches http plain variant with trailing slash`() {
        assertTrue(Constants.isDefaultServerUrl("http://oo.openonion.ai/"))
    }

    @Test
    fun `does not match an unknown host with trailing slash`() {
        assertFalse(Constants.isDefaultServerUrl("https://attacker.example.com/"))
    }

    @Test
    fun `does not match an unknown host even if it ends with the default path`() {
        // Some valid suffix doesn't imply trust — must be an exact match.
        assertFalse(Constants.isDefaultServerUrl("https://oo.openonion.ai.attacker.com/"))
    }

    @Test
    fun `returns false for null and blank`() {
        assertFalse(Constants.isDefaultServerUrl(null))
        assertFalse(Constants.isDefaultServerUrl(""))
    }
}
