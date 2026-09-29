package ai.openonion.oochat.data.local

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Metadata returned after a usable recording has been finalized. */
data class RecordedVoice(
    val localPath: String,
    val durationSeconds: Float,
    val file: File
)

/**
 * App-private voice capture boundary, for the server-transcription fallback.
 *
 * Only reached where [android.speech.SpeechRecognizer.isRecognitionAvailable]
 * is false — the recognizer owns the microphone on the primary path and streams
 * partials straight into the composer, so nothing is recorded there.
 *
 * Keeping Android audio APIs behind this interface lets the chat layer use a
 * deterministic fake in local tests.
 */
interface VoiceRecorderStore {
    suspend fun startRecording(): Boolean
    suspend fun stopRecording(): RecordedVoice?
    suspend fun cancelRecording()

    /**
     * Latest normalized microphone input level (0f = silence, 1f = loudest
     * observed sample), refreshed continuously while recording. Lets the UI
     * draw a waveform that reflects real input instead of a fake animation.
     *
     * The one non-suspend member: a volatile read, polled per waveform frame.
     */
    fun currentAmplitude(): Float = 0f
}

/**
 * Records microphone input as 16 kHz mono PCM and wraps it in a WAV file.
 *
 * The transcription endpoint accepts WAV/MP3, so producing WAV at capture
 * time avoids a lossy encode/decode conversion. Completed files live under
 * filesDir, but nothing reads them once the transcript comes back — neither the
 * audio nor its path reaches the transcript or the wire.
 *
 * Every lifecycle call runs on [Dispatchers.IO], like its [ImageAttachmentStore]
 * and [FileAttachmentStore] siblings: they open and delete files and join the
 * capture thread with a one-second bound, which is not work a UI callback can
 * wait on.
 */
class VoiceRecorderStoreImpl(context: Context) : VoiceRecorderStore {

    private val voiceDirectory = File(context.filesDir, DIRECTORY_NAME)
    private val lifecycleLock = Any()

    @Volatile
    private var captureRequested = false
    private var activeCapture: Capture? = null

    @Volatile
    private var latestAmplitude = 0f

    override fun currentAmplitude(): Float = if (captureRequested) latestAmplitude else 0f

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            discardActiveCapture(deleteFile = true)

            val target = createTargetFile() ?: return@withContext false
            val bufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (bufferBytes <= 0) {
                target.delete()
                FileLogger.e(LogTags.VOICE_STORE, "Unsupported recorder buffer size: $bufferBytes")
                return@withContext false
            }

            val recorder = createRecorder(bufferBytes * BUFFER_MULTIPLIER)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                target.delete()
                FileLogger.e(LogTags.VOICE_STORE, "AudioRecord initialization failed")
                return@withContext false
            }

            try {
                initializeWavFile(target)
                recorder.startRecording()

                val capture = Capture(
                    recorder = recorder,
                    file = target,
                    startedAtNanos = System.nanoTime(),
                    bufferSize = bufferBytes * BUFFER_MULTIPLIER
                )
                captureRequested = true
                capture.worker = Thread(
                    { copyMicrophonePcm(capture) },
                    "voice-capture-${target.nameWithoutExtension}"
                ).also { it.start() }
                activeCapture = capture
                true
            } catch (error: Exception) {
                captureRequested = false
                recorder.release()
                target.delete()
                FileLogger.e(LogTags.VOICE_STORE, "Unable to start recording: ${error.message}")
                false
            }
        }
    }

    override suspend fun stopRecording(): RecordedVoice? = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            val capture = activeCapture ?: return@withContext null
            val elapsedSeconds = ((System.nanoTime() - capture.startedAtNanos) / NANOS_PER_SECOND).toFloat()

            stopCapture(capture)
            activeCapture = null

            val hasAudio = capture.file.exists() && capture.file.length() > WAV_HEADER_BYTES
            if (elapsedSeconds < MINIMUM_DURATION_SECONDS || !hasAudio) {
                capture.file.delete()
                return@withContext null
            }

            try {
                writeFinalHeader(capture.file)
                RecordedVoice(
                    localPath = Uri.fromFile(capture.file).toString(),
                    durationSeconds = elapsedSeconds,
                    file = capture.file
                )
            } catch (error: Exception) {
                FileLogger.e(LogTags.VOICE_STORE, "Unable to finalize WAV: ${error.message}")
                capture.file.delete()
                null
            }
        }
    }

    override suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            discardActiveCapture(deleteFile = true)
        }
    }

    private fun createTargetFile(): File? {
        if (!voiceDirectory.exists() && !voiceDirectory.mkdirs()) {
            FileLogger.e(LogTags.VOICE_STORE, "Unable to create ${voiceDirectory.absolutePath}")
            return null
        }
        return File(voiceDirectory, "${UUID.randomUUID()}.wav")
    }

    @Suppress("MissingPermission")
    private fun createRecorder(bufferSize: Int): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )

    private fun initializeWavFile(file: File) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.write(ByteArray(WAV_HEADER_BYTES))
        }
    }

    private fun copyMicrophonePcm(capture: Capture) {
        val chunk = ByteArray(capture.bufferSize)
        try {
            RandomAccessFile(capture.file, "rw").use { output ->
                output.seek(WAV_HEADER_BYTES.toLong())
                while (captureRequested) {
                    val count = capture.recorder.read(chunk, 0, chunk.size)
                    if (count > 0) {
                        output.write(chunk, 0, count)
                        latestAmplitude = peakAmplitude(chunk, count)
                    }
                }
            }
        } catch (error: Exception) {
            if (captureRequested) {
                FileLogger.e(LogTags.VOICE_STORE, "Microphone read failed: ${error.message}")
            }
        } finally {
            latestAmplitude = 0f
        }
    }

    /** Peak absolute value of the 16-bit little-endian PCM samples in [bytes], normalized to 0f..1f. */
    private fun peakAmplitude(bytes: ByteArray, byteCount: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return (peak.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
    }

    private fun stopCapture(capture: Capture) {
        captureRequested = false
        runCatching { capture.recorder.stop() }
            .onFailure { FileLogger.w(LogTags.VOICE_STORE, "AudioRecord.stop failed: ${it.message}") }
        capture.worker?.join(WORKER_JOIN_MILLIS)
        capture.recorder.release()
    }

    private fun discardActiveCapture(deleteFile: Boolean) {
        val capture = activeCapture ?: return
        stopCapture(capture)
        if (deleteFile) capture.file.delete()
        activeCapture = null
    }

    private fun writeFinalHeader(file: File) {
        val pcmBytes = file.length() - WAV_HEADER_BYTES
        RandomAccessFile(file, "rw").use { output ->
            output.seek(0L)
            output.write(wavHeader(pcmBytes))
        }
    }

    private fun wavHeader(pcmBytes: Long): ByteArray {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * bytesPerSample
        val frameSize = CHANNEL_COUNT * bytesPerSample

        return ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putAscii("RIFF")
                putInt((pcmBytes + RIFF_METADATA_BYTES).toInt())
                putAscii("WAVE")
                putAscii("fmt ")
                putInt(PCM_FORMAT_CHUNK_BYTES)
                putShort(PCM_AUDIO_FORMAT)
                putShort(CHANNEL_COUNT.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort(frameSize.toShort())
                putShort(BITS_PER_SAMPLE.toShort())
                putAscii("data")
                putInt(pcmBytes.toInt())
            }
            .array()
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }

    private class Capture(
        val recorder: AudioRecord,
        val file: File,
        val startedAtNanos: Long,
        val bufferSize: Int
    ) {
        var worker: Thread? = null
    }

    private companion object {
        const val DIRECTORY_NAME = "voice_messages"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BUFFER_MULTIPLIER = 2
        const val WAV_HEADER_BYTES = 44
        const val RIFF_METADATA_BYTES = 36L
        const val PCM_FORMAT_CHUNK_BYTES = 16
        const val PCM_AUDIO_FORMAT: Short = 1
        const val MINIMUM_DURATION_SECONDS = 1f
        const val WORKER_JOIN_MILLIS = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
