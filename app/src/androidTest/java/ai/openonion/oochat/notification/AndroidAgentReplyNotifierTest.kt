package ai.openonion.oochat.notification

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real notifier against the real OS.
 *
 * Everything else that touches notifications goes through a fake, so nothing
 * checked that a notification the system actually accepts comes out the other
 * end — a wrong channel id, or an icon the platform rejects, would have posted
 * nothing at all and passed every existing test.
 *
 * This is also what produces the README's notification screenshot: run
 * [posts_a_reply_the_system_shows] on a device and the shade holds a genuine
 * notification, with only the reply text supplied here rather than by an agent.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAgentReplyNotifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidAgentReplyNotifier(context)

    private fun current(): Notification? =
        manager.activeNotifications.firstOrNull {
            it.notification.channelId == AGENT_REPLY_CHANNEL_ID
        }?.notification

    /**
     * `notify()` and `cancel()` both cross into system_server, so the shade
     * catches up a moment after the call returns. Reading [current] straight
     * away sees the state from before the call about half the time.
     */
    private fun awaitNotification(present: Boolean): Notification? {
        repeat(50) {
            val n = current()
            if ((n != null) == present) return n
            Thread.sleep(100)
        }
        return current()
    }

    private fun posted(): Notification? = awaitNotification(present = true)

    /**
     * From API 33 a notification the user has not granted POST_NOTIFICATIONS
     * for is dropped silently, so without this the assertions would be about
     * the permission rather than the notifier. Granted through UiAutomation
     * rather than GrantPermissionRule to avoid pulling in androidx.test:rules
     * for one line.
     */
    @Before
    fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @Before
    fun clearAny() {
        notifier.clearReplyNotifications()
        awaitNotification(present = false)
    }

    @Test
    fun posts_a_reply_the_system_shows() {
        notifier.notifyAgentReply(
            agentName = "demo-agent",
            preview = "Sydney is 19 degrees and clear right now, easing to 14 overnight."
        )

        val n = requireNotNull(posted()) { "nothing was posted to $AGENT_REPLY_CHANNEL_ID" }
        assertEquals("demo-agent", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(
            n.extras.getString(Notification.EXTRA_TEXT).orEmpty().startsWith("Sydney is 19 degrees")
        )
    }

    /** A reply with no agent name still has to say something in the title. */
    @Test
    fun falls_back_to_a_generic_title() {
        notifier.notifyAgentReply(agentName = "   ", preview = "Done.")

        assertEquals("New reply", requireNotNull(posted()).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun clearing_removes_it() {
        notifier.notifyAgentReply(agentName = "demo-agent", preview = "Done.")
        require(posted() != null)

        notifier.clearReplyNotifications()

        assertNull(awaitNotification(present = false))
    }
}
