package ai.openonion.oochat.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the previously-mock Settings screen toggles
 * (Markdown rendering, font size, haptics, sound effects, streaming
 * responses, memory, custom instructions).
 *
 * Interface — like every other repo in this codebase — so [ChatViewModel]
 * can be tested with a fake instead of needing a real DataStore/Context;
 * [ChatViewModel] reads [customInstructions] and [streamingResponses]
 * directly (the others are only consumed by Compose UI).
 */
interface AppSettings {
    val renderMarkdown: Flow<Boolean>
    suspend fun setRenderMarkdown(value: Boolean)

    /** Index into Settings' own `FONT_SIZE_LABELS` (Small/Medium/Large/Extra Large). */
    val fontSizeIndex: Flow<Int>
    suspend fun setFontSizeIndex(value: Int)

    val hapticFeedback: Flow<Boolean>
    suspend fun setHapticFeedback(value: Boolean)

    val soundEffects: Flow<Boolean>
    suspend fun setSoundEffects(value: Boolean)

    val streamingResponses: Flow<Boolean>
    suspend fun setStreamingResponses(value: Boolean)

    /**
     * Whether the client resumes/sends persisted session state to the
     * agent on connect. Gates protocol-level session resumption only —
     * see [ai.openonion.oochat.data.repository.MemoryGatedSessionStore]
     * — not the local per-conversation history the drawer browses.
     */
    val memoryEnabled: Flow<Boolean>
    suspend fun setMemoryEnabled(value: Boolean)

    /** Free text silently prepended to every outgoing prompt — see ChatViewModel.sendMessage. */
    val customInstructions: Flow<String>
    suspend fun setCustomInstructions(value: String)

    /**
     * User's intent to receive push notifications. On API 33+ this is
     * independent of the actual OS permission grant — SettingsViewModel
     * reconciles the two (see its `notificationPermissionBlocked` /
     * `pushNotificationsEffective` flows) rather than this flow tracking
     * the permission itself.
     */
    val pushNotificationsEnabled: Flow<Boolean>
    suspend fun setPushNotificationsEnabled(value: Boolean)

    /**
     * Unused — sound is a per-channel OS property from API 26 onward, and
     * Settings now hands off to the system's own channel settings instead of
     * offering a picker backed by no real sound assets. Kept (not removed)
     * because deleting the DataStore key/interface member is a bigger ripple
     * than a persisted value nothing reads.
     */
    val notificationSound: Flow<String>
    suspend fun setNotificationSound(value: String)

    val analyticsEnabled: Flow<Boolean>
    suspend fun setAnalyticsEnabled(value: Boolean)

    val safeModeEnabled: Flow<Boolean>
    suspend fun setSafeModeEnabled(value: Boolean)
}
