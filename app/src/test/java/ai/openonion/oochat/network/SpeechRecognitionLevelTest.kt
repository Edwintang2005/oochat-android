package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of [AndroidSpeechRecognitionService] that is arithmetic rather
 * than framework: turning `onRmsChanged`'s dB into the 0..1 the composer's
 * waveform draws. The recognizer itself is behind [SpeechRecognitionService]
 * and faked in ChatViewModelTest.
 */
class SpeechRecognitionLevelTest {

    @Test
    fun `silence and below floor at zero`() {
        assertEquals(0f, AndroidSpeechRecognitionService.normalizeRms(-2f), 0.001f)
        assertEquals(0f, AndroidSpeechRecognitionService.normalizeRms(-40f), 0.001f)
    }

    @Test
    fun `a loud sample and anything past it caps at one`() {
        assertEquals(1f, AndroidSpeechRecognitionService.normalizeRms(10f), 0.001f)
        assertEquals(1f, AndroidSpeechRecognitionService.normalizeRms(60f), 0.001f)
    }

    @Test
    fun `ordinary speech lands in the middle of the bar, not pinned to an end`() {
        val level = AndroidSpeechRecognitionService.normalizeRms(4f)

        assertTrue("a normal voice drew a flat or full bar: $level", level in 0.3f..0.7f)
    }
}
