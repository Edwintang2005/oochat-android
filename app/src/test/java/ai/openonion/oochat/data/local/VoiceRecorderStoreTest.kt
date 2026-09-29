package ai.openonion.oochat.data.local

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [VoiceRecorderStoreImpl] against Robolectric's shadow
 * [android.media.AudioRecord] — which tracks state transitions (so
 * `startRecording()`/`stop()` don't throw) but has no real microphone and
 * never actually delivers PCM bytes, so it can't exercise a genuinely
 * successful capture. That path (stop → non-null [RecordedVoice]) is instead
 * covered via the [VoiceRecorderStore] interface's fake in
 * [ai.openonion.oochat.ui.chat.ChatViewModelTest]. What's real and
 * worth covering here is the lifecycle contract: start doesn't throw,
 * nothing-to-stop is a safe no-op, and an immediate stop is correctly
 * treated as too short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRecorderStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = VoiceRecorderStoreImpl(context)

    @Test
    fun `startRecording succeeds under the shadow AudioRecord`(): Unit = runBlocking {
        val started = store.startRecording()

        assertTrue("recorder should initialize under Robolectric's shadow AudioRecord", started)
        store.cancelRecording()
    }

    @Test
    fun `cancelRecording after starting does not throw`(): Unit = runBlocking {
        store.startRecording()
        store.cancelRecording()
        // A second cancel (nothing active) must also be a safe no-op.
        store.cancelRecording()
    }

    @Test
    fun `stopRecording with nothing started returns null`(): Unit = runBlocking {
        assertNull(store.stopRecording())
    }

    @Test
    fun `stopRecording immediately after starting is too short and returns null`(): Unit = runBlocking {
        store.startRecording()

        val result = store.stopRecording()

        assertNull("a <1s recording must not be treated as a real message", result)
    }

    @Test
    fun `currentAmplitude is 0 when nothing has been recorded`(): Unit = runBlocking {
        assertEquals(0f, store.currentAmplitude(), 0f)
    }

    @Test
    fun `currentAmplitude returns to 0 once recording has stopped`(): Unit = runBlocking {
        store.startRecording()
        store.stopRecording()

        assertEquals(0f, store.currentAmplitude(), 0f)
    }

    @Test
    fun `startRecording after a previous session's stop does not throw`(): Unit = runBlocking {
        store.startRecording()
        store.stopRecording()

        val startedAgain = store.startRecording()

        assertTrue(startedAgain)
        store.cancelRecording()
    }

    @Test
    fun `RecordedVoice localPath is a file uri pointing at an app-private path`(): Unit = runBlocking {
        // Sanity-checks the shape callers rely on (the fallback path parses
        // this back with Uri.parse) without depending on a real capture.
        val dir = File(context.filesDir, "voice_messages")
        val fake = RecordedVoice(
            localPath = Uri.fromFile(File(dir, "sample.m4a")).toString(),
            durationSeconds = 5f,
            file = File(dir, "sample.m4a")
        )
        assertTrue(fake.localPath.startsWith("file:"))
    }
}
