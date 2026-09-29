package ai.openonion.oochat.network

import ai.openonion.oochat.Constants
import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Converts an app-private audio file into recognized text.
 *
 * The fallback path only: [SpeechRecognitionService] handles dictation wherever
 * the device has a recognition service, because this one POSTs the whole
 * recording and so cannot show a word until the speaking is over.
 */
interface VoiceTranscriptionService {
    suspend fun transcribe(audioFile: File): Result<String>
}

/**
 * Sends WAV audio to the relay's chat-completions transcription endpoint.
 *
 * Authentication is obtained from [AccountRepository]. A rejected cached
 * token is refreshed once; other failures are returned to the caller without
 * retrying so the UI can move its voice bubble to the failed state.
 */
class VoiceTranscriptionServiceImpl(
    private val accountRepository: AccountRepository,
    baseUrl: String = Constants.DEFAULT_SERVER_URL,
    private val client: OkHttpClient = defaultHttpClient()
) : VoiceTranscriptionService {

    private val endpoint = "${baseUrl.trimEnd('/')}/v1/chat/completions"

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val codec = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun transcribe(audioFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(audioFile.isFile) {
                    "Recording does not exist: ${audioFile.absolutePath}"
                }

                val payload = encodeRequest(audioFile)
                val initialToken = loadToken(forceRefresh = false)
                val firstResponse = post(payload, initialToken)

                val finalResponse = if (firstResponse.code == HTTP_UNAUTHORIZED) {
                    firstResponse.close()
                    post(payload, loadToken(forceRefresh = true))
                } else {
                    firstResponse
                }

                finalResponse.use { response ->
                    readTranscript(response)
                }
            }.onFailure { error ->
                FileLogger.e(
                    LogTags.VOICE_TRANSCRIBE,
                    "Voice transcription failed: ${error.message}"
                )
            }
        }

    private fun encodeRequest(audioFile: File): String {
        val audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val request = CompletionRequest(
            model = TRANSCRIPTION_MODEL,
            messages = listOf(
                CompletionMessage(
                    role = "user",
                    content = listOf(
                        CompletionPart.Text(TRANSCRIPTION_PROMPT),
                        CompletionPart.Audio(
                            AudioInput(data = audio, format = AUDIO_FORMAT)
                        )
                    )
                )
            )
        )
        return codec.encodeToString(CompletionRequest.serializer(), request)
    }

    private suspend fun loadToken(forceRefresh: Boolean): String =
        accountRepository.loadAccount(forceReauth = forceRefresh)
            .getOrThrow()
            .apiKey

    private fun post(payload: String, token: String): Response {
        val request = Request.Builder()
            .url(endpoint)
            .header(AUTHORIZATION_HEADER, "Bearer $token")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute()
    }

    private fun readTranscript(response: Response): String {
        val responseText = response.body?.string()
        if (!response.isSuccessful || responseText == null) {
            throw VoiceTranscriptionException(
                "HTTP ${response.code}: ${responseText?.take(MAX_ERROR_BODY_CHARS)}"
            )
        }

        val decoded = codec.decodeFromString(
            CompletionResponse.serializer(),
            responseText
        )
        return decoded.choices
            .orEmpty()
            .firstNotNullOfOrNull { it.message?.content?.takeIf(String::isNotBlank) }
            ?: throw VoiceTranscriptionException("The server returned an empty transcript")
    }

    internal companion object {
        const val TRANSCRIPTION_MODEL = "gemini-2.5-flash"

        /**
         * Deliberately a rule about the audio, not a language: naming a locale
         * would push the other half of a code-switched sentence into the wrong
         * script, and the device locale is no evidence of what was just said.
         * "Do not translate" is what stops an English instruction turning
         * Chinese speech into English; the transliteration ban is what stops it
         * coming back as pinyin, which is what this endpoint used to return.
         */
        const val TRANSCRIPTION_PROMPT: String =
            "Transcribe this audio verbatim. Write every word in the native script of the " +
                "language actually spoken, and keep a switch between languages mid-sentence " +
                "exactly where it happens. Never translate, and never romanise or otherwise " +
                "transliterate speech into a writing system other than its own. Reply with " +
                "the transcript and nothing else."
        const val AUDIO_FORMAT = "wav"
        const val HTTP_UNAUTHORIZED = 401
        const val MAX_ERROR_BODY_CHARS = 500
        const val REQUEST_TIMEOUT_SECONDS = 30L
        const val AUTHORIZATION_HEADER = "Authorization"

        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Derived from the app's one client, not built fresh — see [sharedHttpClient].
        fun defaultHttpClient(): OkHttpClient = sharedHttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

private class VoiceTranscriptionException(message: String) : Exception(message)

@Serializable
private data class CompletionRequest(
    val model: String,
    val messages: List<CompletionMessage>
)

@Serializable
private data class CompletionMessage(
    val role: String,
    val content: List<CompletionPart>
)

@Serializable
private sealed class CompletionPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : CompletionPart()

    @Serializable
    @SerialName("input_audio")
    data class Audio(
        @SerialName("input_audio") val input: AudioInput
    ) : CompletionPart()
}

@Serializable
private data class AudioInput(
    val data: String,
    val format: String
)

@Serializable
private data class CompletionResponse(
    val choices: List<CompletionChoice>? = null
)

@Serializable
private data class CompletionChoice(
    val message: CompletionResultMessage? = null
)

@Serializable
private data class CompletionResultMessage(
    val content: String? = null
)
