package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.SafePreferencesWrapper
import ai.openonion.oochat.domain.model.AgentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ViewModel-facing contract for default-agent bookkeeping. Extracted so
 * [ai.openonion.oochat.ui.agent.AgentViewModel] can be unit-tested
 * with an in-memory fake — [DefaultAgentRepository]'s real implementation
 * needs a [SafePreferencesWrapper], which needs an Android [android.content.Context]
 * unavailable in plain JVM unit tests. Matches the codebase pattern of
 * `AgentRepository` / `ConnectionRepository` / `IgnoredIdsStorage`.
 */
interface DefaultAgentRepositoryContract {
    suspend fun getDefaultAgent(): AgentProfile?
    suspend fun setDefaultAgent(agentId: String)
    suspend fun clearDefaultAgent()
    suspend fun hasDefaultAgent(): Boolean
}

/**
 * Repository for managing default agent configuration.
 *
 * Persists the default agent ID using SafePreferencesWrapper.
 * Provides fallback to first active agent if no default is set.
 *
 * Not synced with the legacy `ConnectionConfig` mechanism `LoadingScreen`'s
 * auto-reconnect still reads — this one just decides which agent
 * `AgentListScreen` shows as "default". See `ConnectionConfig`'s doc for why.
 */
class DefaultAgentRepository(
    private val safePrefs: SafePreferencesWrapper,
    private val agentRepository: AgentRepository
) : DefaultAgentRepositoryContract {
    private val prefs = safePrefs.getPrefs()

    override suspend fun getDefaultAgent(): AgentProfile? {
        return withContext(Dispatchers.IO) {
            val defaultId = prefs.getString(KEY_DEFAULT_AGENT_ID, null)
            if (defaultId != null) {
                agentRepository.getAgentById(defaultId)
            } else {
                agentRepository.getDefaultAgent()
            }
        }
    }

    override suspend fun setDefaultAgent(agentId: String) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_DEFAULT_AGENT_ID, agentId)
                .apply()
        }
    }

    override suspend fun clearDefaultAgent() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_DEFAULT_AGENT_ID)
                .apply()
        }
    }

    override suspend fun hasDefaultAgent(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.contains(KEY_DEFAULT_AGENT_ID)
        }
    }

    companion object {
        private const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
    }
}
