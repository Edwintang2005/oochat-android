package ai.openonion.oochat.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep a live dictation from eating what the user wrote. Pure,
 * because the recognizer is not the interesting part — the ordering of a
 * partial against a hand edit is.
 */
class DictationMergeTest {

    @Test
    fun `an empty field takes the transcript as-is`() {
        val merged = DictationMerge.merge(DictationAnchor(base = ""), "book a table")

        assertEquals("book a table", merged!!.text)
    }

    @Test
    fun `text typed before recording survives, with the transcript appended`() {
        val merged = DictationMerge.merge(DictationAnchor(base = "Reminder:"), "book a table")

        assertEquals("Reminder: book a table", merged!!.text)
    }

    @Test
    fun `a field left with a trailing space does not get a double one`() {
        val merged = DictationMerge.merge(DictationAnchor(base = "Reminder: "), "book a table")

        assertEquals("Reminder: book a table", merged!!.text)
    }

    @Test
    fun `repeated partials replace each other instead of stacking up`() {
        val anchor = DictationAnchor(base = "Reminder:")

        val first = DictationMerge.merge(anchor, "book")
        val second = DictationMerge.merge(anchor, "book a")
        val third = DictationMerge.merge(anchor, "book a table")

        assertEquals("Reminder: book", first!!.text)
        assertEquals("Reminder: book a", second!!.text)
        assertEquals("Reminder: book a table", third!!.text)
    }

    @Test
    fun `the caret lands after the inserted text, never at the start`() {
        val merged = DictationMerge.merge(DictationAnchor(base = "Reminder:"), "book a table")!!

        assertEquals(merged.text.length, merged.selection.start)
        assertTrue("the caret must be collapsed, not a selection", merged.selection.collapsed)
    }

    @Test
    fun `a hand edit detaches the anchor`() {
        val anchor = DictationAnchor(base = "Reminder:")

        val after = DictationMerge.onFieldChanged(anchor, current = "Reminder: book", edited = "Reminder: books")

        assertTrue(after.detached)
    }

    @Test
    fun `moving the caret is not an edit and does not end the dictation`() {
        val anchor = DictationAnchor(base = "Reminder:")

        val after = DictationMerge.onFieldChanged(anchor, current = "Reminder: book", edited = "Reminder: book")

        assertTrue("a selection-only change must not detach", !after.detached)
    }

    @Test
    fun `a partial arriving after a hand edit is dropped, not applied over it`() {
        val detached = DictationAnchor(base = "Reminder:", detached = true)

        assertNull(DictationMerge.merge(detached, "book a table"))
    }

    @Test
    fun `a final result after a hand edit is dropped too, for the same reason`() {
        val detached = DictationAnchor(base = "Reminder:", detached = true)

        // Same call, because "final" is only a phase in the caller — the anchor
        // does not distinguish them, which is what makes the two consistent.
        assertNull(DictationMerge.merge(detached, "book a table for two at eight"))
    }
}
