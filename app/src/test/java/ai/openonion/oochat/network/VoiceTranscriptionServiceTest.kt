package ai.openonion.oochat.network

import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.AccountSnapshot
import ai.openonion.oochat.domain.model.UserProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The server-transcription fallback's HTTP contract: what it does with an
 * expired token, and which answers count as failures. [VoiceTranscriptionInstructionTest]
 * covers what it asks for; this covers what it does with the reply.
 *
 * Every failure here reaches ChatViewModel as "Couldn't transcribe that. Try
 * again." with nothing sent - a failure mistaken for success would put an
 * empty or garbage transcript in the composer instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceTranscriptionServiceTest {

    /** Hands out "stale-token" until asked to re-authenticate, then "fresh-token". */
    private class RecordingAccount : AccountRepository {
        val forceReauthCalls = mutableListOf<Boolean>()
        override suspend fun loadAccount(forceReauth: Boolean): Result<AccountSnapshot> {
            forceReauthCalls += forceReauth
            val key = if (forceReauth) "fresh-token" else "stale-token"
            return Result.success(AccountSnapshot(key, UserProfile("pk", 0.0, 0.0, 0.0)))
        }
        override fun invalidate() = Unit
    }

    private val account = RecordingAccount()
    private val transcript = """{"choices":[{"message":{"content":"book a table for two"}}]}"""

    private lateinit var server: MockWebServer
    private lateinit var service: VoiceTranscriptionServiceImpl
    private lateinit var audio: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = VoiceTranscriptionServiceImpl(account, server.url("/").toString().trimEnd('/'))
        audio = File.createTempFile("dictation", ".wav").apply { writeBytes(ByteArray(8)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        audio.delete()
    }

    @Test
    fun `a transcript comes back from the completions endpoint with the account's token`() = runTest {
        server.enqueue(MockResponse().setBody(transcript))

        val result = service.transcribe(audio)

        assertEquals("book a table for two", result.getOrNull())
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer stale-token", request.getHeader("Authorization"))
    }

    @Test
    fun `an expired token is refreshed once and the same recording re-sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(transcript))

        val result = service.transcribe(audio)

        assertEquals("book a table for two", result.getOrNull())
        assertEquals(listOf(false, true), account.forceReauthCalls)
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer fresh-token", retry.getHeader("Authorization"))
        assertEquals("the retry must carry the same audio", first.body.readUtf8(), retry.body.readUtf8())
    }

    @Test
    fun `a second 401 is a failure, not an endless retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.transcribe(audio)

        assertTrue(result.isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a server error is a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream down"))

        assertTrue(service.transcribe(audio).isFailure)
    }

    @Test
    fun `an empty transcript is a failure, not an empty success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"  "}}]}"""))

        assertTrue(service.transcribe(audio).isFailure)
    }

    @Test
    fun `a recording that no longer exists fails without a network call`() = runTest {
        audio.delete()

        assertTrue(service.transcribe(audio).isFailure)
        assertEquals(0, server.requestCount)
    }
}
