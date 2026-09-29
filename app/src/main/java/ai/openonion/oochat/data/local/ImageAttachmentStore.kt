package ai.openonion.oochat.data.local

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** A single picked image, converted for both local display/persistence and the wire. */
data class StoredImage(
    /** file:// URI string of the app-private copy — safe to persist and reload later. */
    val localPath: String,
    /** data:image/jpeg;base64,... — ready to drop into InputMsg.images. */
    val dataUrl: String
)

/**
 * Converts a picked photo-picker URI into a durable local copy plus a
 * base64 data URL for the wire.
 *
 * Interface (like every other repo in this codebase) so [ChatViewModel]
 * can be tested with a fake instead of needing a real Context/BitmapFactory
 * — [ChatViewModelTest] is a plain JVM test, not Robolectric. Takes the URI
 * as a plain [String] (not [Uri]) for the same reason: callers that only
 * need to pass it through — [ChatViewModel] — never touch the unmockable
 * `Uri.parse()` static; only [ImageAttachmentStoreImpl] does.
 */
interface ImageAttachmentStore {
    suspend fun store(uriString: String): StoredImage?
}

/**
 * Real implementation, backed by [android.graphics.BitmapFactory].
 *
 * Photo Picker URIs (`content://media/picker/...`) only grant this app a
 * transient read grant tied to the current picking session — they are not
 * guaranteed to still resolve after the app process is killed and
 * restarted, so images must be copied into app-private storage immediately
 * at send time rather than persisting the raw picker URI.
 *
 * Also downscales to a max 1568px long side and re-encodes as JPEG: modern
 * phone photos routinely run several MB, well past what's sane to inline as
 * base64 JSON over a chat WebSocket frame, and 1568px matches common
 * vision-model input guidance (larger doesn't improve recognition).
 */
class ImageAttachmentStoreImpl(private val context: Context) : ImageAttachmentStore {

    /**
     * [Dispatchers.Default], not IO: decode, downscale, JPEG-encode and the
     * base64 pass are all CPU-bound, and IO's 64-thread pool oversubscribes
     * the cores (and starves the UI thread) when several images are attached
     * at once. Same choice [ImageGridBubble][ai.openonion.oochat.ui.chat.components]
     * already makes for its own decode. Only the file write is genuinely
     * blocking, so only it goes back to IO.
     */
    override suspend fun store(uriString: String): StoredImage? = withContext(Dispatchers.Default) {
        val uri = Uri.parse(uriString)
        val bytes = decodeAndCompress(uri) ?: return@withContext null
        val file = withContext(Dispatchers.IO) { writeToAppStorage(bytes) }
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        StoredImage(localPath = Uri.fromFile(file).toString(), dataUrl = dataUrl)
    }

    /**
     * Two-pass decode: first read just the bounds (no pixel allocation) to
     * pick a power-of-two [BitmapFactory.Options.inSampleSize], then decode
     * once already close to [MAX_DIMENSION] — a modern phone photo (12-48MP)
     * decoded at full resolution first (the previous approach) could
     * allocate hundreds of MB just to immediately downscale it, risking
     * [OutOfMemoryError] on constrained devices.
     *
     * Runs on [Dispatchers.Default] (see [store]). The two stream reads stay
     * with it rather than being hoisted onto IO: `decodeStream` consumes the
     * stream as it decodes, so the read is not separable from the CPU work.
     */
    private fun decodeAndCompress(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // decodeStream returns null by design in inJustDecodeBounds mode —
            // it only fills outWidth/outHeight. The null check therefore has to
            // guard openInputStream itself; an elvis on the use{} result would
            // reject every image.
            val boundsStream = context.contentResolver.openInputStream(uri) ?: run {
                FileLogger.w(LogTags.IMAGE_STORE, "No input stream for bounds pass: $uri")
                return null
            }
            boundsStream.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                FileLogger.w(
                    LogTags.IMAGE_STORE,
                    "Undecodable bounds ${bounds.outWidth}x${bounds.outHeight}: $uri"
                )
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: run {
                FileLogger.w(
                    LogTags.IMAGE_STORE,
                    "Decode returned null (inSampleSize=${options.inSampleSize}): $uri"
                )
                return null
            }

            // `scaled` must be allocated inside this try/finally so an OOM during downscale doesn't leak `decoded`'s native memory; both are recycled below unless `scaled === decoded`.
            var scaled: Bitmap? = null
            try {
                scaled = downscale(decoded, MAX_DIMENSION)
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } finally {
                decoded.recycle()
                if (scaled != null && scaled !== decoded) scaled.recycle()
            }
        } catch (e: OutOfMemoryError) {
            FileLogger.w(LogTags.IMAGE_STORE, "Out of memory decoding/compressing $uri: ${e.message}")
            null
        } catch (e: Exception) {
            FileLogger.w(LogTags.IMAGE_STORE, "Failed to decode/compress $uri: ${e.message}")
            null
        }
    }

    /** Largest power-of-two subsampling that still leaves the long side >= [targetDimension]. */
    private fun calculateInSampleSize(width: Int, height: Int, targetDimension: Int): Int {
        var inSampleSize = 1
        val longSide = maxOf(width, height)
        while (longSide / (inSampleSize * 2) >= targetDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longSide
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun writeToAppStorage(bytes: ByteArray): File {
        val dir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        return file
    }

    companion object {
        private const val MAX_DIMENSION = 1568
        private const val JPEG_QUALITY = 85
    }
}
