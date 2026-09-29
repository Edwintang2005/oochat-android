package ai.openonion.oochat.util

/**
 * Named [FileLogger]/log-tag constants, replacing freeform string literals
 * at each call site. A typo'd literal (`"AgnetConn"`) silently breaks
 * `adb logcat`/[FileLogger.readLogs] filtering for that call site with no
 * compiler warning; a typo'd constant reference doesn't compile.
 */
object LogTags {
    const val ACCOUNT = "Account"
    const val AGENT_CONN = "AgentConn"
    const val AGENT_DISCOVERY = "AgentDiscovery"
    const val CHAT_VM = "ChatVM"
    const val CONNECT_USE_CASE = "ConnectUseCase"
    const val CONN_REPO = "ConnRepo"
    const val CONV_HISTORY = "ConvHistory"
    const val DASHBOARD = "Dashboard"
    const val FILE_STORE = "FileStore"
    const val IGNORED_IDS_MGR = "IgnoredIdsMgr"
    const val IMAGE_STORE = "ImageStore"
    const val NOTIFICATIONS = "Notifications"
    const val PENDING_OUTBOX = "PendingOutbox"
    const val PERMISSION = "Permission"
    const val PROTOCOL = "Protocol"
    const val SESSION_STORE = "SessionStore"
    const val VOICE_STORE = "VoiceStore"
    const val VOICE_TRANSCRIBE = "VoiceTranscribe"
}
