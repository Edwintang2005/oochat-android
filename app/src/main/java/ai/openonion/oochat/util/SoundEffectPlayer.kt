package ai.openonion.oochat.util

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Built-in tone playback for Settings' "Sound effects" toggle. Reuses one
 * process-lifetime [ToneGenerator] rather than a bundled sound asset.
 */
object SoundEffectPlayer {

    private val toneGenerator: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun playSent() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
    }

    fun playReceived() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
    }
}
