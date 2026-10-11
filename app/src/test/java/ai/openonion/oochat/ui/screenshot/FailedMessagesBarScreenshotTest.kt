package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.chat.components.FailedMessagesBar
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The retry control above the composer: a single failure, and several — a
 * text message, a held-too-long image and a file — each with its own Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FailedMessagesBarScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun failed(id: String, content: String, images: List<String>? = null, files: List<ChatFileAttachment>? = null) =
        ChatItem.User(id = id, content = content, images = images, files = files, state = UserMessageState.FAILED)

    private val one = listOf(failed("m1", "Can you summarise yesterday's deploy logs?"))

    private val several = listOf(
        failed("m1", "Can you summarise yesterday's deploy logs?"),
        failed("m2", "", images = listOf("file:///x/a.jpg")),
        failed("m3", "", files = listOf(ChatFileAttachment(name = "report.pdf", path = "file:///x/report.pdf")))
    )

    private fun capture(name: String, items: List<ChatItem.User>, palette: Palette) =
        composeRule.captureThemed(name, palette) { FailedMessagesBar(failed = items, onRetry = {}) }

    @Test
    fun `one failed - light`() = capture("failed_messages_bar_one", one, Palette.Light)

    @Test
    fun `one failed - dark`() = capture("failed_messages_bar_one", one, Palette.Dark)

    @Test
    fun `several failed - light`() = capture("failed_messages_bar_several", several, Palette.Light)

    @Test
    fun `several failed - dark`() = capture("failed_messages_bar_several", several, Palette.Dark)
}
