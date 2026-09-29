package ai.openonion.oochat.data.local

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Unit tests for [ImageAttachmentStoreImpl] against real (Robolectric)
 * file I/O and Bitmap encoding — needs a real [android.content.Context]
 * for [android.content.ContentResolver]/`filesDir`, unlike the plain-JVM
 * [ai.openonion.oochat.ui.chat.ChatViewModelTest], which uses the
 * [ImageAttachmentStore] interface's fake instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageAttachmentStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = ImageAttachmentStoreImpl(context)

    private fun writeTestImageUri(width: Int = 20, height: Int = 20): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("picker", ".png", context.cacheDir)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return Uri.fromFile(file)
    }

    @Test
    fun `store copies the image into a durable app-private file and returns a base64 data URL`() = runTest {
        val sourceUri = writeTestImageUri()

        val result = store.store(sourceUri.toString())

        assertNotNull(result)
        assertTrue(
            "local path must be a durable app-private copy, not the ephemeral source URI",
            result!!.localPath != sourceUri.toString()
        )
        assertTrue(result.localPath.startsWith("file:"))
        assertTrue(File(Uri.parse(result.localPath).path!!).exists())
        assertTrue(result.dataUrl.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `store returns null for a uri that cannot be opened`() = runTest {
        val result = store.store("file:///no/such/file.png")

        assertNull(result)
    }
}
