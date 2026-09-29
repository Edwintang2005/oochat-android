package ai.openonion.oochat.data.local

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [FileAttachmentStoreImpl] against real (Robolectric) file
 * I/O — mirrors [ImageAttachmentStoreTest]'s approach, needing a real
 * [android.content.Context] for `filesDir`/[android.content.ContentResolver].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileAttachmentStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = FileAttachmentStoreImpl(context)

    private fun writeTestFileUri(name: String, contents: String): Uri {
        val file = File(context.cacheDir, name)
        file.writeText(contents)
        return Uri.fromFile(file)
    }

    @Test
    fun `store copies the file into a durable app-private path and returns a base64 data URL`() = runTest {
        val sourceUri = writeTestFileUri("picker-report.pdf", "not a real pdf, just test bytes")

        val result = store.store(sourceUri.toString())

        assertTrue(result is FileAttachResult.Success)
        val success = result as FileAttachResult.Success
        assertEquals("picker-report.pdf", success.attachment.name)
        assertTrue(success.attachment.data.startsWith("data:"))
        assertTrue(success.attachment.data.contains(";base64,"))
        assertNotEquals(
            "local path must be a durable app-private copy, not the ephemeral source URI",
            sourceUri.toString(),
            success.localPath
        )
        assertTrue(success.localPath.startsWith("file:"))
        assertTrue(File(Uri.parse(success.localPath).path!!).exists())
    }

    @Test
    fun `store returns Failed for a uri that cannot be opened`() = runTest {
        val result = store.store("file:///no/such/file.pdf")

        assertTrue(result is FileAttachResult.Failed)
    }

    @Test
    fun `store returns TooLarge when the file exceeds MAX_FILE_BYTES`() = runTest {
        val oversized = "x".repeat((FileAttachmentStoreImpl.MAX_FILE_BYTES + 1).toInt())
        val sourceUri = writeTestFileUri("oversized.bin", oversized)

        val result = store.store(sourceUri.toString())

        assertTrue(result is FileAttachResult.TooLarge)
    }

    /** Half of MAX_TOTAL_BYTES, and comfortably inside the per-file cap, so only the shared budget can refuse it. */
    private fun writeHalfBudgetFileUri(name: String): String =
        writeTestFileUri(name, "x".repeat((FileAttachmentStoreImpl.MAX_TOTAL_BYTES / 2).toInt())).toString()

    @Test
    fun `storeAll caps the selection total, not only each file`() = runTest {
        val half = writeHalfBudgetFileUri("half.bin")

        val results = store.storeAll(listOf(half, half, half))

        assertTrue(results[0] is FileAttachResult.Success)
        assertTrue(results[1] is FileAttachResult.Success)
        assertTrue("the third file must not fit the remaining budget", results[2] is FileAttachResult.TooLarge)
    }

    @Test
    fun `a file that stores fine alone is refused once the selection budget is spent`() = runTest {
        val half = writeHalfBudgetFileUri("half.bin")
        val small = writeTestFileUri("notes.txt", "small by any measure").toString()
        assertTrue(store.store(small) is FileAttachResult.Success)

        val results = store.storeAll(listOf(half, half, small))

        assertTrue(results[2] is FileAttachResult.TooLarge)
        assertEquals("notes.txt", (results[2] as FileAttachResult.TooLarge).name)
    }

    @Test
    fun `storeAll still applies the per-file cap`() = runTest {
        val oversized = writeTestFileUri("huge.bin", "x".repeat((FileAttachmentStoreImpl.MAX_FILE_BYTES + 1).toInt()))

        val results = store.storeAll(listOf(oversized.toString()))

        assertTrue(results.single() is FileAttachResult.TooLarge)
    }

    @Test
    fun `a refused file leaves no app-private copy behind`() = runTest {
        val attachmentsDir = File(context.filesDir, "attachments")
        val before = attachmentsDir.listFiles()?.size ?: 0
        // file:// reports no OpenableColumns.SIZE, so this is refused during
        // the copy — after the destination directory already exists.
        val oversized = writeTestFileUri("leaky.bin", "x".repeat((FileAttachmentStoreImpl.MAX_FILE_BYTES + 1).toInt()))

        assertTrue(store.store(oversized.toString()) is FileAttachResult.TooLarge)

        assertEquals(before, attachmentsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `re-storing a previously stored local path recovers the original display name`() = runTest {
        // Simulates a failed-message retry: ChatViewModel passes the local
        // file:// path (from the earlier ChatFileAttachment) back into
        // store(), which has no SAF DISPLAY_NAME to query this time — the
        // name must come back off the path itself.
        val sourceUri = writeTestFileUri("my-notes.txt", "hello")
        val first = store.store(sourceUri.toString()) as FileAttachResult.Success

        val retried = store.store(first.localPath) as FileAttachResult.Success

        assertEquals("my-notes.txt", retried.attachment.name)
    }
}
