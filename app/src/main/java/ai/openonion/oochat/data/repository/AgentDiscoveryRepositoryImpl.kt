package ai.openonion.oochat.data.repository

import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Implementation of [AgentDiscoveryRepository].
 *
 * `discoverFromRelay` performs a real GET against
 * `${relayUrl}/api/relay/agents` and maps the response into [AgentProfile]s.
 */
class AgentDiscoveryRepositoryImpl : AgentDiscoveryRepository {

    // Derived from the app's one client, not built fresh — see [sharedHttpClient].
    private val httpClient = sharedHttpClient.newBuilder()
        .connectTimeout(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Throws instead of returning emptyList() so callers can tell 'relay has zero agents' apart from 'request failed'.
    override suspend fun discoverFromRelay(relayUrl: String): List<AgentProfile> {
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val url = "${relayUrl.trimEnd('/')}/api/relay/agents"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw java.io.IOException("Relay responded ${response.code} for $url")
                    }
                    val body = response.body?.string()
                        ?: throw java.io.IOException("Relay returned an empty response for $url")
                    val parsed = json.decodeFromString<RelayAgentsResponse>(body)
                    val now = System.currentTimeMillis()
                    // The address is the row's id, and the results list is keyed
                    // on it. The relay does not promise one row per address, and
                    // a repeat would crash the list rather than show a duplicate.
                    parsed.agents.distinctBy { it.address }.mapNotNull { dto ->
                        val address = dto.address ?: return@mapNotNull null
                        AgentProfile(
                            id = address,
                            address = address,
                            name = dto.profile?.alias ?: "Agent ${address.take(10)}…",
                            description = null,
                            serverUrl = relayUrl,
                            apiKey = null,
                            avatarUrl = null,
                            createdAt = now,
                            lastConnectedAt = null,
                            isActive = true,
                            connectionMode = "relay"
                        )
                    }
                }
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_DISCOVERY, "discoverFromRelay failed: ${e.message}")
            }.getOrThrow()
        }
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Wire format of `GET /api/relay/agents`.
 * Only the fields we consume are declared; extras (endpoints, skills, etc.)
 * are ignored.
 */
@Serializable
private data class RelayAgentsResponse(
    val agents: List<RelayAgentDto> = emptyList()
)

@Serializable
private data class RelayAgentDto(
    val address: String? = null,
    val relay: String? = null,
    val profile: RelayProfileDto? = null
)

@Serializable
private data class RelayProfileDto(
    val alias: String? = null,
    val model: String? = null,
    @SerialName("model_version") val modelVersion: String? = null
)
