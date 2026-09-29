package ai.openonion.oochat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * DataStore-backed [IgnoredIdsStorage] for production.
 *
 * Singleton pattern: the `preferencesDataStore` delegate caches the DataStore<Preferences> on the Context.
 *
 * [saveForAgent] is a single read-modify-write cycle (fine for once per clearChat click).
 * If ever hot, switch to per-agent keys and keep the whole map in memory.
 */
private val Context.ignoredIdsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat_ignored_ids"
)

// Bumped from v1 (single Set<String>) to v2 (per-agent Map<String, Set<String>>).
// v1 data is ignored — the storage was only ever populated by the local
// dev build, no production users carry forward state to migrate.
private val IGNORED_IDS_MAP_KEY = stringPreferencesKey("ignored_ids_map_v2")

private val IGNORED_IDS_FORMAT = MapSerializer(String.serializer(), SetSerializer(String.serializer()))

private val IGNORED_IDS_JSON = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = false
}

class DataStoreIgnoredIdsStorage(
    context: Context
) : IgnoredIdsStorage {

    // Use application context so we don't pin an Activity.
    private val dataStore: DataStore<Preferences> =
        context.applicationContext.ignoredIdsDataStore

    override suspend fun loadAll(): Map<String, Set<String>> {
        val raw = dataStore.data.first()[IGNORED_IDS_MAP_KEY] ?: return emptyMap()
        return runCatching { IGNORED_IDS_JSON.decodeFromString(IGNORED_IDS_FORMAT, raw) }
            .getOrElse { emptyMap() }
    }

    override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {
        dataStore.edit { prefs ->
            val currentRaw = prefs[IGNORED_IDS_MAP_KEY]
            val currentMap: Map<String, Set<String>> = currentRaw
                ?.let { runCatching { IGNORED_IDS_JSON.decodeFromString(IGNORED_IDS_FORMAT, it) }.getOrNull() }
                ?: emptyMap()
            // Replace just the targeted agent's entry. Empty Set is a
            // legitimate value — it records that the user cleared this
            // agent, not that we should drop the key.
            val updated = currentMap + (agentAddress to ids)
            prefs[IGNORED_IDS_MAP_KEY] = IGNORED_IDS_JSON.encodeToString(IGNORED_IDS_FORMAT, updated)
        }
    }
}
