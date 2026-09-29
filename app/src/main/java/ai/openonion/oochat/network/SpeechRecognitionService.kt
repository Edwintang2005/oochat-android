package ai.openonion.oochat.network

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** One update from a dictation in progress. */
sealed interface SpeechRecognitionEvent {
    /**
     * `onReadyForSpeech`: the recognizer has the microphone and is listening.
     * Carries nothing for the composer — it exists so a caller can tell a
     * working recognizer from one that binds and then says nothing at all.
     */
    object Ready : SpeechRecognitionEvent

    /** Best guess so far. Superseded by the next [Partial] or by [Final] — never appended to. */
    data class Partial(val text: String) : SpeechRecognitionEvent

    /** The recognizer's settled answer for this dictation. The flow completes after it. */
    data class Final(val text: String) : SpeechRecognitionEvent

    /** Microphone level, 0f..1f, for the composer's waveform. */
    data class Level(val normalized: Float) : SpeechRecognitionEvent

    /** The dictation ended without a transcript. The flow completes after it. */
    data class Failed(val reason: String) : SpeechRecognitionEvent
}

/**
 * What we know about this device's live dictation, cached for the process.
 *
 * "A recognition service is registered" is not the same claim as "it works":
 * some devices ship a stub that binds, takes the microphone and never calls
 * a single [android.speech.RecognitionListener] method back — not even
 * `onError` — so nothing but trying tells them apart.
 */
enum class RecognizerReadiness {
    /** Nothing registered, or a capability query or an earlier dictation proved it silent. */
    UNAVAILABLE,

    /** Known good: a capability query or an earlier dictation heard back from it. */
    READY,

    /** Registered but unproven. The next dictation doubles as the probe. */
    UNPROVEN
}

/**
 * Live dictation from the platform speech recognizer.
 *
 * Interface (like [VoiceTranscriptionService]) so [ai.openonion.oochat.ui.chat.ChatViewModel]
 * can be driven by a fake: `android.speech.SpeechRecognizer` needs a real
 * Looper and an installed recognition service, neither of which a JVM unit
 * test has.
 */
interface SpeechRecognitionService {

    /**
     * Whether live dictation is worth attempting. [RecognizerReadiness.UNAVAILABLE]
     * is the case that needs [VoiceTranscriptionService]'s server round trip;
     * [RecognizerReadiness.UNPROVEN] asks the caller to time the first dictation
     * and report back through [recordProbeResult].
     */
    suspend fun readiness(): RecognizerReadiness

    /**
     * The verdict from an [RecognizerReadiness.UNPROVEN] dictation. Held in
     * memory only — a wrong verdict costs one session, never a reinstall.
     */
    fun recordProbeResult(usable: Boolean)

    /**
     * One dictation. Emits [SpeechRecognitionEvent.Ready] when the microphone
     * is live, [SpeechRecognitionEvent.Partial] while the user is still
     * speaking, then exactly one [SpeechRecognitionEvent.Final] or
     * [SpeechRecognitionEvent.Failed], then completes. Cancelling the
     * collector abandons the dictation and releases the microphone.
     */
    fun listen(): Flow<SpeechRecognitionEvent>

    /** Ends the current dictation early and asks for the final result. */
    suspend fun stop()
}

/**
 * Real implementation, backed by [SpeechRecognizer] with `EXTRA_PARTIAL_RESULTS`.
 *
 * Deliberately not [VoiceTranscriptionService]: that path POSTs the finished
 * recording to a general chat-completion endpoint, so the wait scaled with how
 * long you spoke and nothing appeared until it was over. The trade-off accepted
 * here is that the audio goes to whatever recognition service the device ships
 * (Google's, usually, and off-device unless [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 * is available on API 33+) rather than through the user's own relay.
 */
class AndroidSpeechRecognitionService(private val context: Context) : SpeechRecognitionService {

    // SpeechRecognizer is main-thread-only for every call, construction included.
    private var recognizer: SpeechRecognizer? = null

    // Null until something settles it. Never persisted: a verdict reached by
    // timing out one dictation is a guess, and a restart deserves a fresh one.
    @Volatile
    private var verdict: Boolean? = null

    override suspend fun readiness(): RecognizerReadiness {
        verdict?.let { return if (it) RecognizerReadiness.READY else RecognizerReadiness.UNAVAILABLE }

        val registered = withContext(Dispatchers.Main.immediate) {
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        }
        if (!registered) {
            recordProbeResult(usable = false)
            FileLogger.i(LogTags.VOICE_TRANSCRIBE, "no recognition service registered")
            return RecognizerReadiness.UNAVAILABLE
        }

        // checkRecognitionSupport is a capability query, not a session: it
        // answers without opening the microphone, so it is safe to run here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return RecognizerReadiness.UNPROVEN
        val supported = querySupport()
        FileLogger.i(LogTags.VOICE_TRANSCRIBE, "checkRecognitionSupport -> $supported")
        return when (supported) {
            true -> RecognizerReadiness.READY.also { recordProbeResult(usable = true) }
            false -> RecognizerReadiness.UNAVAILABLE.also { recordProbeResult(usable = false) }
            null -> RecognizerReadiness.UNPROVEN
        }
    }

    override fun recordProbeResult(usable: Boolean) {
        verdict = usable
    }

    override fun listen(): Flow<SpeechRecognitionEvent> = callbackFlow {
        val speech = createRecognizer()
        if (speech == null) {
            trySend(SpeechRecognitionEvent.Failed("no recognition service"))
            close()
            return@callbackFlow
        }
        recognizer = speech

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                firstTranscript(partialResults)?.let { trySend(SpeechRecognitionEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstTranscript(results)
                if (text.isNullOrBlank()) {
                    trySend(SpeechRecognitionEvent.Failed("empty transcript"))
                } else {
                    trySend(SpeechRecognitionEvent.Final(text))
                }
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechRecognitionEvent.Failed(errorText(error)))
                close()
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechRecognitionEvent.Level(normalizeRms(rmsdB)))
            }

            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechRecognitionEvent.Ready)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        speech.startListening(recognizerIntent())

        // flowOn(Main) puts this block on the main thread too, which is where
        // every SpeechRecognizer call has to happen. Cancel, not stop: an
        // abandoned collector wants the microphone back now, not one more
        // result it will never read.
        awaitClose {
            recognizer = null
            runCatching {
                speech.cancel()
                speech.destroy()
            }
        }
    }.flowOn(Dispatchers.Main.immediate)

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        runCatching { recognizer?.stopListening() }
        Unit
    }

    private suspend fun createRecognizer(): SpeechRecognizer? = withContext(Dispatchers.Main.immediate) {
        runCatching {
            // On-device keeps the audio on the phone, so it is preferred where
            // it exists; createSpeechRecognizer is the pre-33 and no-model path.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.onFailure { FileLogger.e(LogTags.VOICE_TRANSCRIBE, "recognizer init failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * True/false from `checkRecognitionSupport`, or null when it could not
     * tell — an inconclusive answer must not condemn the recognizer, so it
     * falls through to the first-dictation probe instead.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(): Boolean? = withContext(Dispatchers.Main.immediate) {
        val speech = createRecognizer() ?: return@withContext false
        try {
            withTimeoutOrNull(SUPPORT_QUERY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean?> { cont ->
                    runCatching {
                        speech.checkRecognitionSupport(
                            recognizerIntent(),
                            Executor { it.run() },
                            object : RecognitionSupportCallback {
                                override fun onSupportResult(support: RecognitionSupport) {
                                    if (!cont.isActive) return
                                    cont.resume(
                                        support.installedOnDeviceLanguages.isNotEmpty() ||
                                            support.supportedOnDeviceLanguages.isNotEmpty() ||
                                            support.onlineLanguages.isNotEmpty()
                                    )
                                }

                                override fun onError(error: Int) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        } finally {
            runCatching { speech.destroy() }
        }
    }

    private fun recognizerIntent(): Intent =
        buildRecognizerIntent(context.packageName, preferredLanguageTags(), Build.VERSION.SDK_INT)

    /**
     * The languages this user has actually declared, most preferred first.
     * Read off the app's own configuration rather than `Locale.getDefault()`
     * so a per-app language override (Android 13+) wins over the system one.
     */
    private fun preferredLanguageTags(): List<String> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
            // "und" is what an undetermined locale tags as; it names no language.
            .filter { it.isNotBlank() && it != UNDETERMINED_LANGUAGE_TAG }
            .distinct()
            .ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
    }

    private fun firstTranscript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        /** A capability query that has not answered in this long is not going to. */
        private const val SUPPORT_QUERY_TIMEOUT_MS = 1_500L

        private const val UNDETERMINED_LANGUAGE_TAG = "und"

        /**
         * The dictation request, built from [preferredLanguages] (BCP-47, most
         * preferred first) so the recognizer is never left to guess from
         * `Locale.getDefault()`.
         *
         * `EXTRA_LANGUAGE` alone would pin a mixed Chinese/English speaker to
         * one script, so API 34+ also turns on the auto switch, which follows
         * what is actually being said. `EXTRA_LANGUAGE_PREFERENCE` and
         * `EXTRA_SUPPORTED_LANGUAGES` are no help here — both are outputs of
         * the `ACTION_GET_LANGUAGE_DETAILS` broadcast, not recognition inputs.
         */
        fun buildRecognizerIntent(
            packageName: String,
            preferredLanguages: List<String>,
            sdkInt: Int
        ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            preferredLanguages.firstOrNull()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }

            if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@apply
            // BALANCED, not QUICK_RESPONSE: switching on a guess the recognizer
            // is not confident in thrashes mid-sentence, which reads worse than
            // a beat of lag on the first switched word.
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            // Only a list the user actually declared is evidence of intent. One
            // locale says nothing about what they might say, so leave the switch
            // unconstrained there rather than nailing it to that single language.
            if (preferredLanguages.size > 1) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(preferredLanguages)
                )
            }
        }

        /**
         * [RecognitionListener.onRmsChanged] reports roughly -2dB (silence) to
         * 10dB (loud) — not a documented range, but the one every device in
         * practice produces — so it is clamped rather than scaled from dB.
         */
        fun normalizeRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        /** Human-readable [SpeechRecognizer] error, for the composer's error line. */
        fun errorText(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone blocked. Allow microphone access in Settings, then try again."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs a network connection."
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "Didn't catch that. Try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again."
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER -> "Speech recognition failed."
            else -> "Speech recognition failed."
        }
    }
}
