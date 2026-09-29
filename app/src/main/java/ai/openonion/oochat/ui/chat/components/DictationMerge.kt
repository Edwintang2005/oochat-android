package ai.openonion.oochat.ui.chat.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * What the composer held when the microphone opened, plus whether the user has
 * since edited it. [base] is the anchor every update is recomputed from, so a
 * stream of partials replaces its own last guess instead of concatenating all
 * of them.
 */
data class DictationAnchor(val base: String, val detached: Boolean = false)

/**
 * Folds a live dictation into the composer, ported from oo-chat-web's
 * `chat-input.tsx`: `setValue(prev => prev ? `${prev} ${text}` : text)`.
 *
 * Deliberately free of Compose state and of the recognizer, because the
 * interesting part is not either of those — it is that a partial arriving
 * after a correction must not undo the correction.
 */
object DictationMerge {

    /**
     * The field after [transcript] lands, or null when the anchor is detached
     * (the user has edited by hand) and the caller should leave the field alone.
     *
     * The caret goes to the end of the inserted text: dictation always appends,
     * so that is also the end of the field. Returning a whole [TextFieldValue]
     * rather than a String is the point — a String replacement keeps the old
     * selection offset and strands the caret mid-word.
     */
    fun merge(anchor: DictationAnchor, transcript: String): TextFieldValue? {
        if (anchor.detached) return null
        val text = when {
            transcript.isBlank() -> anchor.base
            anchor.base.isBlank() -> transcript
            // trimEnd, unlike the reference's plain `${prev} ${text}`, so a
            // field left with a trailing space does not get a double one.
            else -> "${anchor.base.trimEnd()} $transcript"
        }
        return TextFieldValue(text, TextRange(text.length))
    }

    /**
     * The anchor after the user typed [edited] over [current].
     *
     * A text change detaches for good: every later partial and the final result
     * are then dropped. The alternative — tracking an insertion region the
     * recognizer keeps rewriting — cannot survive an edit that spans it, and a
     * correction silently undone by the next partial is worse than a few words
     * the user has to say again. A selection-only change is not an edit, so
     * moving the caret or tapping into the field does not end the dictation.
     */
    fun onFieldChanged(anchor: DictationAnchor, current: String, edited: String): DictationAnchor =
        if (edited == current) anchor else anchor.copy(detached = true)
}
