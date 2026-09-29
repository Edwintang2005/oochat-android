package ai.openonion.oochat.network

import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.AccountSnapshot
import ai.openonion.oochat.domain.model.UserProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The instruction [VoiceTranscriptionServiceImpl] actually POSTs beside the
 * audio. What the model writes back cannot be asserted from a unit test, but
 * the prompt can: an English-only "Transcribe this audio accurately." is what
 * had Chinese speech coming back as pinyin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceTranscriptionInstructionTest {

    private lateinit var server: MockWebServer
    private lateinit var service: VoiceTranscriptionServiceImpl
    private lateinit var audio: File

    private val account = object : AccountRepository {
        override suspend fun loadAccount(forceReauth: Boolean) = Result.success(
            AccountSnapshot("test-key", UserProfile("pk", 0.0, 0.0, 0.0))
        )

        override fun invalidate() = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"你好 world"}}]}""")
        )
        service = VoiceTranscriptionServiceImpl(account, server.url("/").toString().trimEnd('/'))
        audio = File.createTempFile("dictation", ".wav").apply { writeBytes(ByteArray(8)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        audio.delete()
    }

    /** The request body as sent, audio and all. */
    private suspend fun postedBody(): String {
        service.transcribe(audio)
        return server.takeRequest().body.readUtf8()
    }

    @Test
    fun `the instruction asks for the language spoken, in that language's own script`() = runTest {
        val body = postedBody()

        assertTrue(body, body.contains("native script"))
        assertTrue(body, body.contains("language actually spoken"))
    }

    @Test
    fun `it forbids the two ways a transcript stops being a transcript`() = runTest {
        val body = postedBody().lowercase(Locale.ROOT)

        // Translating and romanising are both "helpful" rewrites the model will
        // otherwise reach for when the instruction beside the audio is English.
        assertTrue(body, body.contains("never translate"))
        assertTrue(body, body.contains("transliterate"))
    }

    @Test
    fun `it names no language, so a code-switched sentence is not forced into one`() {
        val instruction = VoiceTranscriptionServiceImpl.TRANSCRIPTION_PROMPT.lowercase(Locale.ROOT)

        // Naming the device locale would be worse than naming nothing: this user
        // dictates English on a Chinese phone and Chinese on an English one.
        listOf("chinese", "english", "mandarin", "pinyin", "zh-cn", "en-us").forEach {
            assertFalse("instruction hard-codes '$it'", instruction.contains(it))
        }
    }

    @Test
    fun `the instruction is what goes on the wire, not a constant nothing reads`() = runTest {
        val body = postedBody()

        assertTrue(body, body.contains(VoiceTranscriptionServiceImpl.TRANSCRIPTION_PROMPT))
    }
}
