package ai.openonion.oochat.ui.chat.components

/**
 * The composer's view of a dictation in progress.
 *
 * [transcript] is cumulative for the whole dictation, never a delta — the
 * composer recomputes the field from its anchor on every update, so a repeated
 * or revised partial replaces the last one instead of stacking onto it.
 */
data class VoiceInputState(
    val phase: VoiceInputPhase = VoiceInputPhase.IDLE,
    val transcript: String = "",
    /** Set once when a dictation ends with nothing usable; cleared on the next start. */
    val error: String? = null,
    /**
     * Something the user should know about a dictation that is working fine —
     * separate from [error] because it is shown mid-recording, and the error
     * line is styled as a failure.
     */
    val notice: String? = null
) {
    /** A microphone is open right now. Drives the elapsed timer, so it must not include [VoiceInputPhase.PREPARING]. */
    val isRecording: Boolean get() = phase == VoiceInputPhase.LISTENING

    /** A dictation owns the composer — from the tap, not from the mic opening. */
    val isBusy: Boolean get() = phase != VoiceInputPhase.IDLE
}

enum class VoiceInputPhase {
    IDLE,

    /**
     * Tapped, but no audio source is open yet — the recognizer is still being
     * asked about, bound, or probed. Nothing said in this window is captured,
     * so the row must not claim to be listening and the timer must not run.
     */
    PREPARING,

    /** Microphone open. On the recognizer path the transcript grows while this lasts. */
    LISTENING,

    /**
     * Microphone closed, transcript not back yet. Only reachable on the
     * server-transcription fallback, which has nothing to show until the POST
     * returns; the recognizer path goes straight from LISTENING to IDLE.
     */
    TRANSCRIBING
}
