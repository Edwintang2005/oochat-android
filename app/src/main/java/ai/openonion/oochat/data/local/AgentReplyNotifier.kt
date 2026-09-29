package ai.openonion.oochat.data.local

/**
 * Android-free boundary for the "agent replied while you were away" system
 * notification — [ui.chat.ChatViewModel][ai.openonion.oochat.ui.chat.ChatViewModel]
 * depends on this instead of `android.app.NotificationManager` directly, the
 * same reason [AppSettings] sits here rather than a concrete DataStore type.
 * The real implementation is [ai.openonion.oochat.notification.AndroidAgentReplyNotifier].
 */
interface AgentReplyNotifier {
    /** Post (or replace) the reply notification. [agentName] null/blank falls back to a generic title. */
    fun notifyAgentReply(agentName: String?, preview: String)

    /** Dismiss it — called once the chat is back in the foreground. */
    fun clearReplyNotifications()
}
