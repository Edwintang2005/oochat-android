package ai.openonion.oochat.data.local

import ai.openonion.oochat.domain.model.ThemeMode
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed persistence for the user's selected [ThemeMode].
 *
 * Mirrors the pattern in [DataStoreIgnoredIdsStorage]: a single app-scoped
 * DataStore instance (the delegate caches it on the Context), a small string
 * value, and a reactive [Flow] for reads.
 *
 * Defaults to [ThemeMode.SYSTEM] when nothing is stored yet or the stored value
 * is unrecognized (e.g. a future/renamed enum constant).
 */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_prefs"
)

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

class ThemePreferences(context: Context) {

    // Application context so we don't pin an Activity.
    private val dataStore: DataStore<Preferences> = context.applicationContext.themeDataStore

    /** Reactive stream of the persisted theme mode. Emits [ThemeMode.SYSTEM] by default. */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    /** Persist the selected [mode]. Survives process death and app restarts. */
    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode.name
        }
    }
}
