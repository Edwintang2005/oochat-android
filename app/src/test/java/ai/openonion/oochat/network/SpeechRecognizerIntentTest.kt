package ai.openonion.oochat.network

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * What [AndroidSpeechRecognitionService] actually asks the platform recognizer
 * for. The transcript itself needs a microphone and a real recognition service,
 * so the request is the thing a unit test can hold to: leaving the language
 * unset is what let a Chinese utterance come back romanised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechRecognizerIntentTest {

    private fun intent(languages: List<String>, sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE) =
        AndroidSpeechRecognitionService.buildRecognizerIntent("com.example.app", languages, sdkInt)

    @Test
    fun `the request names a language instead of leaving it to the system default`() {
        val extras = intent(listOf("zh-CN"))

        assertEquals("zh-CN", extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    @Test
    fun `the most preferred language leads, not whichever one happens to be in the list`() {
        val extras = intent(listOf("en-AU", "zh-CN"))

        assertEquals("en-AU", extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    @Test
    fun `a mixed-language speaker gets the auto switch, so neither half is transliterated`() {
        val extras = intent(listOf("zh-CN", "en-US"))

        assertEquals(
            RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            extras.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH)
        )
        assertEquals(
            listOf("zh-CN", "en-US"),
            extras.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES)
        )
    }

    @Test
    fun `one declared language leaves the switch open rather than nailing it shut`() {
        val extras = intent(listOf("en-US"))

        // Constraining the switch to the single locale would be the same as no
        // switch at all; what the user might say is not what they configured.
        assertEquals(
            RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            extras.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH)
        )
        assertNull(extras.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
    }

    @Test
    fun `an English speaker is never handed another language to switch into`() {
        val extras = intent(listOf("en-US", "en-GB"))

        assertEquals("en-US", extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(
            extras.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES)
                .orEmpty().all { it.startsWith("en") }
        )
    }

    @Test
    fun `below API 34 the language is still pinned, without extras the platform cannot honour`() {
        val extras = intent(listOf("zh-CN", "en-US"), sdkInt = Build.VERSION_CODES.S)

        assertEquals("zh-CN", extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(extras.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
        assertFalse(extras.hasExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
    }

    /**
     * The reporting device (Huawei TAS-AL00) has `system_locales` set to
     * `zh-Hans-CN,en-US` — two declared languages, which is the case the whole
     * switch branch exists for. Reflection because the wiring from the app's
     * configuration to the intent is what is under test, not the builder above.
     */
    @Test
    fun `a device declaring Chinese then English hands the recognizer both`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("zh-Hans-CN"), Locale.forLanguageTag("en-US")))
        }
        val service = AndroidSpeechRecognitionService(app.createConfigurationContext(config))

        val method = AndroidSpeechRecognitionService::class.java.getDeclaredMethod("recognizerIntent")
        method.isAccessible = true
        val extras = method.invoke(service) as android.content.Intent

        assertEquals("zh-Hans-CN", extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(
            listOf("zh-Hans-CN", "en-US"),
            extras.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES)
        )
    }

    @Test
    fun `an empty language list degrades to the old behaviour rather than sending a blank tag`() {
        val extras = intent(emptyList())

        assertFalse(extras.hasExtra(RecognizerIntent.EXTRA_LANGUAGE))
        // Still a free-form streaming dictation — the language fix must not
        // have quietly cost the partial results the composer draws.
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            extras.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertTrue(extras.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }
}
