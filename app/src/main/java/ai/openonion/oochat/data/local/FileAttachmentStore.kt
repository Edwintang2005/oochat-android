package ai.openonion.oochat.data.local

import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Outcome of [FileAttachmentStore.store] for one SAF-picked document. */
sealed class FileAttachResult {
    /**
     * [localPath] is a file:// URI to the app-private copy — the
     * [ai.openonion.oochat.domain.model.ChatFileAttachment.path] that
     * gets persisted, mirroring [StoredImage.localPath].
     */
    data class Success(val attachment: OutgoingFileAttachment, val localPath: String) : FileAttachResult()
    /**
     * Over [FileAttachmentStoreImpl.MAX_FILE_BYTES] on its own, or over what
     * was left of [FileAttachmentStoreImpl.MAX_TOTAL_BYTES] for the selection.
     * [sizeBytes] is null when the provider didn't report OpenableColumns.SIZE.
     */
    data class TooLarge(val name: String, val sizeBytes: Long?) : FileAttachResult()
    data class Failed(val name: String?) : FileAttachResult()
}

/**
 * Converts a SAF document URI (from `ActivityResultContracts.OpenMultipleDocuments`)
 * into both a durable app-private copy and a base64 data URL for
 * [ai.openonion.oochat.data.protocol.InputMsg.files].
 *
 * Like [ImageAttachmentStore], the file is copied into app-private storage
 * so it survives a process restart and can be reattached on retry — see
 * [ChatItem.User.files][ai.openonion.oochat.domain.model.ChatItem.User].
 * Unlike images, files have no downscale to shrink them, so size is controlled
 * purely by refusing the raw picked file: [FileAttachmentStoreImpl.MAX_FILE_BYTES]
 * each, and [FileAttachmentStoreImpl.MAX_TOTAL_BYTES] across a whole selection
 * via [storeAll].
 *
 * Interface for the same reason as [ImageAttachmentStore] — so
 * [ai.openonion.oochat.ui.chat.ChatViewModel] can be unit-tested with
 * a fake instead of a real Context/ContentResolver.
 */
interface FileAttachmentStore {
    suspend fun store(uriString: String): FileAttachResult

    /**
     * Stores one whole picker selection. Callers hold every [FileAttachResult.Success]
     * live at once, so the per-file cap bounds nothing on its own — this is the
     * entry point that also budgets the selection's total. Default is the
     * unbudgeted per-file loop, for fakes that never allocate anything.
     */
    suspend fun storeAll(uriStrings: List<String>): List<FileAttachResult> =
        uriStrings.map { store(it) }
}

class FileAttachmentStoreImpl(private val context: Context) : FileAttachmentStore {

    override suspend fun store(uriString: String): FileAttachResult = withContext(Dispatchers.IO) {
        storeWithin(uriString, MAX_FILE_BYTES).result
    }

    override suspend fun storeAll(uriStrings: List<String>): List<FileAttachResult> = withContext(Dispatchers.IO) {
        // Spend one shared budget down the selection instead of applying
        // MAX_FILE_BYTES afresh to each URI: the caller keeps every success
        // (and its base64 copy) alive until the message is sent, so only the
        // total bounds the peak heap.
        var remaining = MAX_TOTAL_BYTES
        uriStrings.map { uriString ->
            val outcome = storeWithin(uriString, minOf(MAX_FILE_BYTES, remaining))
            remaining -= outcome.consumedBytes
            outcome.result
        }
    }

    /** [consumedBytes] is what this URI took out of [storeAll]'s budget — 0 unless it stored. */
    private class StoreOutcome(val result: FileAttachResult, val consumedBytes: Long)

    private fun storeWithin(uriString: String, limitBytes: Long): StoreOutcome {
        val uri = Uri.parse(uriString)
        val (queriedName, queriedSize) = queryNameAndSize(uri)
        val displayName = queriedName ?: uri.lastPathSegment ?: "file"

        if (queriedSize != null && queriedSize > limitBytes) {
            return StoreOutcome(FileAttachResult.TooLarge(displayName, queriedSize), 0L)
        }

        val localFile = newAppStorageFile(displayName)
        var kept = false
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return StoreOutcome(FileAttachResult.Failed(displayName), 0L)

            // Some SAF providers omit OpenableColumns.SIZE, so the cap has to
            // be re-applied to the bytes actually read rather than trusting an
            // absent (or wrong) header. Copying straight to disk means an
            // over-cap file is refused without ever being fully allocated.
            val copiedBytes = input.use { copyUpTo(it, localFile, limitBytes) }
                ?: return StoreOutcome(FileAttachResult.TooLarge(displayName, queriedSize), 0L)

            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val dataUrl = "data:$mimeType;base64," + encodeBase64(localFile)
            kept = true
            StoreOutcome(
                FileAttachResult.Success(
                    attachment = OutgoingFileAttachment(name = displayName, data = dataUrl),
                    localPath = Uri.fromFile(localFile).toString()
                ),
                copiedBytes
            )
        } catch (e: Exception) {
            FileLogger.w(LogTags.FILE_STORE, "Failed to read file $uri: ${e.message}")
            StoreOutcome(FileAttachResult.Failed(displayName), 0L)
        } finally {
            // A refused or half-written copy must not survive as a stray
            // attachments/ directory; only a returned Success keeps its file.
            if (!kept) discardAppStorageFile(localFile)
        }
    }

    /**
     * Copies at most [limitBytes], returning null (and leaving the partial
     * write for the caller to discard) the moment the source runs past it, so
     * the whole file never has to be held to find out it is too big.
     */
    private fun copyUpTo(input: InputStream, destination: File, limitBytes: Long): Long? {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        destination.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                copied += read
                if (copied > limitBytes) return null
                output.write(buffer, 0, read)
            }
        }
        return copied
    }

    /**
     * Encodes off disk rather than off a ByteArray, so the raw bytes are never
     * materialized alongside their ~1.33x base64 copy. Binary WebSocket frames
     * would remove the base64 copy too, but that is a protocol change — see
     * [ai.openonion.oochat.data.protocol.InputMsg.files].
     */
    private fun encodeBase64(file: File): String {
        val encoded = ByteArrayOutputStream(((file.length() + 2) / 3 * 4).toInt())
        Base64OutputStream(encoded, Base64.NO_WRAP).use { base64 ->
            file.inputStream().use { it.copyTo(base64) }
        }
        return encoded.toString(Charsets.US_ASCII.name())
    }

    /**
     * Each copy gets its own UUID-named subdirectory (unlike
     * [ImageAttachmentStoreImpl], which discards the original name behind a
     * flat UUID filename) so [displayName] survives on disk — a retry that
     * re-[store]s this same local path recovers the right name straight off
     * [Uri.getLastPathSegment], with no extra plumbing needed.
     */
    private fun newAppStorageFile(displayName: String): File {
        val dir = File(File(context.filesDir, "attachments"), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, sanitizeFileName(displayName))
    }

    private fun discardAppStorageFile(file: File) {
        file.delete()
        file.parentFile?.delete()
    }

    /** Strips path separators a malicious/unusual DISPLAY_NAME could smuggle in, since [displayName] becomes a literal path segment. */
    private fun sanitizeFileName(displayName: String): String {
        val cleaned = displayName.replace('/', '_').replace('\\', '_').trim()
        return cleaned.ifBlank { "file" }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?> {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null to null
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                name to size
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }

    companion object {
        // 10MB raw: base64 inflates by ~1.33x, so this caps the wire payload
        // at ~13.3MB per file — comfortably under common WebSocket
        // frame/message and reverse-proxy body-size defaults, while still
        // covering the common "attach a PDF/doc" case. Images already have a
        // much tighter effective cap via ImageAttachmentStore's 1568px
        // downscale; files have no such transform available, so the limit is
        // enforced on the raw picked file instead.
        const val MAX_FILE_BYTES = 10 * 1024 * 1024L

        // 20MB across one selection. The picker is multi-select and nothing
        // is released until the message is sent, so the payload is live ~3x
        // over: each base64 data URL, the serialized JSON, and the frame the
        // socket encodes from it — ~80MB peak here, which a 192MB heap
        // survives. Three files at MAX_FILE_BYTES apiece would not.
        const val MAX_TOTAL_BYTES = 20 * 1024 * 1024L
    }
}
