package ai.openonion.oochat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [AppSettings] for production.
 *
 * Same pattern as [ThemePreferences]/[DataStoreIgnoredIdsStorage]: a single
 * app-scoped DataStore instance (the `preferencesDataStore` delegate caches
 * it on the Context), one key per setting, a reactive [Flow] per read.
 */
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings"
)

private val RENDER_MARKDOWN_KEY = booleanPreferencesKey("render_markdown")
private val FONT_SIZE_INDEX_KEY = intPreferencesKey("font_size_index")
private val HAPTIC_FEEDBACK_KEY = booleanPreferencesKey("haptic_feedback")
private val SOUND_EFFECTS_KEY = booleanPreferencesKey("sound_effects")
private val STREAMING_RESPONSES_KEY = booleanPreferencesKey("streaming_responses")
private val MEMORY_ENABLED_KEY = booleanPreferencesKey("memory_enabled")
private val CUSTOM_INSTRUCTIONS_KEY = stringPreferencesKey("custom_instructions")
private val PUSH_NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("push_notifications_enabled")
private val NOTIFICATION_SOUND_KEY = stringPreferencesKey("notification_sound")
private val ANALYTICS_ENABLED_KEY = booleanPreferencesKey("analytics_enabled")
private val SAFE_MODE_ENABLED_KEY = booleanPreferencesKey("safe_mode_enabled")

class DataStoreAppSettings(context: Context) : AppSettings {

    // Application context so we don't pin an Activity.
    private val dataStore: DataStore<Preferences> = context.applicationContext.appSettingsDataStore

    override val renderMarkdown: Flow<Boolean> =
        dataStore.data.map { it[RENDER_MARKDOWN_KEY] ?: true }
    override suspend fun setRenderMarkdown(value: Boolean) {
        dataStore.edit { it[RENDER_MARKDOWN_KEY] = value }
    }

    override val fontSizeIndex: Flow<Int> =
        dataStore.data.map { it[FONT_SIZE_INDEX_KEY] ?: 1 }
    override suspend fun setFontSizeIndex(value: Int) {
        dataStore.edit { it[FONT_SIZE_INDEX_KEY] = value }
    }

    override val hapticFeedback: Flow<Boolean> =
        dataStore.data.map { it[HAPTIC_FEEDBACK_KEY] ?: true }
    override suspend fun setHapticFeedback(value: Boolean) {
        dataStore.edit { it[HAPTIC_FEEDBACK_KEY] = value }
    }

    override val soundEffects: Flow<Boolean> =
        dataStore.data.map { it[SOUND_EFFECTS_KEY] ?: false }
    override suspend fun setSoundEffects(value: Boolean) {
        dataStore.edit { it[SOUND_EFFECTS_KEY] = value }
    }

    override val streamingResponses: Flow<Boolean> =
        dataStore.data.map { it[STREAMING_RESPONSES_KEY] ?: true }
    override suspend fun setStreamingResponses(value: Boolean) {
        dataStore.edit { it[STREAMING_RESPONSES_KEY] = value }
    }

    override val memoryEnabled: Flow<Boolean> =
        dataStore.data.map { it[MEMORY_ENABLED_KEY] ?: true }
    override suspend fun setMemoryEnabled(value: Boolean) {
        dataStore.edit { it[MEMORY_ENABLED_KEY] = value }
    }

    override val customInstructions: Flow<String> =
        dataStore.data.map { it[CUSTOM_INSTRUCTIONS_KEY] ?: "" }
    override suspend fun setCustomInstructions(value: String) {
        dataStore.edit { it[CUSTOM_INSTRUCTIONS_KEY] = value }
    }

    override val pushNotificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[PUSH_NOTIFICATIONS_ENABLED_KEY] ?: false }
    override suspend fun setPushNotificationsEnabled(value: Boolean) {
        dataStore.edit { it[PUSH_NOTIFICATIONS_ENABLED_KEY] = value }
    }

    override val notificationSound: Flow<String> =
        dataStore.data.map { it[NOTIFICATION_SOUND_KEY] ?: "Chime" }
    override suspend fun setNotificationSound(value: String) {
        dataStore.edit { it[NOTIFICATION_SOUND_KEY] = value }
    }

    override val analyticsEnabled: Flow<Boolean> =
        dataStore.data.map { it[ANALYTICS_ENABLED_KEY] ?: false }
    override suspend fun setAnalyticsEnabled(value: Boolean) {
        dataStore.edit { it[ANALYTICS_ENABLED_KEY] = value }
    }

    override val safeModeEnabled: Flow<Boolean> =
        dataStore.data.map { it[SAFE_MODE_ENABLED_KEY] ?: true }
    override suspend fun setSafeModeEnabled(value: Boolean) {
        dataStore.edit { it[SAFE_MODE_ENABLED_KEY] = value }
    }
}
