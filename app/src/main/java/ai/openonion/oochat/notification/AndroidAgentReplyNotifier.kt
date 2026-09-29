package ai.openonion.oochat.notification

import ai.openonion.oochat.MainActivity
import ai.openonion.oochat.R
import ai.openonion.oochat.data.local.AgentReplyNotifier
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Shared with SettingsScreen's "Notification sound" row, which opens the OS's own settings for this channel. */
const val AGENT_REPLY_CHANNEL_ID = "agent_replies"

private const val NOTIFICATION_ID = 2001

/** Longer than this reads as noise in a notification's collapsed line, and BigTextStyle already expands it. */
private const val PREVIEW_MAX_LENGTH = 200

/**
 * [AgentReplyNotifier] backed by the real OS APIs.
 *
 * The channel is created on first use, not from [android.app.Application.onCreate]
 * — this app ships baseline profiles, and most cold starts never post a
 * notification at all, so that work has no business being on the startup path.
 */
class AndroidAgentReplyNotifier(private val context: Context) : AgentReplyNotifier {
    private val manager = NotificationManagerCompat.from(context)
    private var channelCreated = false

    override fun notifyAgentReply(agentName: String?, preview: String) {
        ensureChannel()
        val tapIntent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = preview.take(PREVIEW_MAX_LENGTH)
        val notification = NotificationCompat.Builder(context, AGENT_REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(agentName?.takeIf { it.isNotBlank() } ?: "New reply")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // Caller (ChatViewModel) has already checked POST_NOTIFICATIONS before
        // reaching here — see its gating doc — so this can't actually throw
        // the SecurityException the missing-permission lint warning implies.
        @Suppress("MissingPermission")
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clearReplyNotifications() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(AGENT_REPLY_CHANNEL_ID, "Agent replies", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }
}
