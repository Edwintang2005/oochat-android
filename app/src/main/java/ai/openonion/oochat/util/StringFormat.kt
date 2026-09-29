package ai.openonion.oochat.util

/** Shortens a long identifier while preserving recognizable text at both ends. */
fun String.truncateMiddle(prefix: Int, suffix: Int, ellipsis: String = "…"): String {
    val displayedCharacters = prefix + suffix + ellipsis.length
    if (length <= displayedCharacters) return this

    // takeLast, not substring(length - suffix): inside buildString the bare
    // `length` resolves to the StringBuilder's, not this string's, so the tail
    // started near the front of the source and was barely truncated at all.
    return "${take(prefix)}$ellipsis${takeLast(suffix)}"
}

/**
 * `mm:ss` stopwatch for
 * [ai.openonion.oochat.ui.chat.components.VoiceRecordingRow]'s
 * dictation timer. The unpadded playback form went with the voice bubble.
 */
fun formatVoiceDuration(seconds: Float): String {
    val wholeSeconds = seconds.toInt()
    val minutesText = (wholeSeconds / SECONDS_PER_MINUTE).toString().padStart(2, '0')
    val secondsText = (wholeSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')

    return "$minutesText:$secondsText"
}

private const val SECONDS_PER_MINUTE = 60
