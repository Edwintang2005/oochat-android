package ai.openonion.oochat.network

import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Result of agent address resolution.
 *
 * @param address Resolved agent address (0x + 64 hex chars)
 * @param directUrl Direct server URL if the input was a URL, null for relay mode
 */
data class ResolvedAgent(
    val address: String,
    val directUrl: String? = null
)

/**
 * Service for discovering and resolving agent addresses.
 *
 * Supports three input types:
 * 1. Full hex address (0x + 64 hex chars) - returned as-is (relay mode)
 * 2. URL (https://...) - fetches /info endpoint to extract address (direct mode)
 * 3. Other input - returned as-is (fallback)
 */
class AgentDiscoveryService {

    // Derived from the app's one client, not built fresh — see [sharedHttpClient].
    // ChatViewModel and ConnectToAgentUseCase each build their own instance of
    // this service from a constructor default, so a per-instance client here
    // meant a thread pool per ViewModel.
    private val httpClient = sharedHttpClient.newBuilder()
        .connectTimeout(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun resolveAgentAddress(input: String): ResolvedAgent {
        val trimmed = input.trim()

        // Case 1: Full hex address (0x + 64 hex chars)
        if (isValidAddress(trimmed)) {
            FileLogger.i(LogTags.AGENT_DISCOVERY, "Input is valid address: ${trimmed.take(16)}...")
            return ResolvedAgent(address = trimmed)
        }

        // Case 2: URL - try fetching /info
        if (isUrl(trimmed)) {
            FileLogger.i(LogTags.AGENT_DISCOVERY, "Input is URL: $trimmed, fetching /info")
            val address = fetchAddressFromInfo(trimmed)
            if (address != null) {
                FileLogger.i(LogTags.AGENT_DISCOVERY, "Resolved address from /info: ${address.take(16)}...")
                return ResolvedAgent(address = address, directUrl = trimmed)
            }
            FileLogger.w(LogTags.AGENT_DISCOVERY, "Failed to resolve from /info, using URL as-is")
            return ResolvedAgent(address = trimmed, directUrl = trimmed)
        }

        // Case 3: Fallback - return as-is
        FileLogger.w(LogTags.AGENT_DISCOVERY, "Unrecognized input, returning as-is: ${trimmed.take(50)}")
        return ResolvedAgent(address = trimmed)
    }

    private fun isValidAddress(input: String): Boolean {
        return ADDRESS_REGEX.matches(input)
    }

    private fun isUrl(input: String): Boolean {
        return input.startsWith("http://") || input.startsWith("https://")
    }

    private suspend fun fetchAddressFromInfo(serverUrl: String): String? {
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val infoUrl = "${serverUrl.trimEnd('/')}/info"
                FileLogger.d(LogTags.AGENT_DISCOVERY, "GET $infoUrl")

                val request = Request.Builder()
                    .url(infoUrl)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        FileLogger.w(LogTags.AGENT_DISCOVERY, "/info returned ${response.code}")
                        return@withContext null
                    }

                    val body = response.body?.string() ?: return@withContext null
                    val info = json.decodeFromString<AgentInfo>(body)
                    info.address
                }
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_DISCOVERY, "Failed to fetch /info: ${e.message}")
            }.getOrNull()
        }
    }

    /**
     * The agent's published profile, from the relay's directory.
     *
     * The app used to wait for an `AGENT_PROFILE` frame. Nothing sends one:
     * the string appears nowhere in the ConnectOnion SDK, and three connects
     * against a live agent produced CONNECTED, ONBOARD_* and PING and no
     * profile. What the SDK actually does is put the profile in its ANNOUNCE
     * ("the relay persists it, so heartbeats don't carry it") and leave
     * subscribers to fetch it — which is this call. Without it the slash
     * palette is empty against every agent.
     *
     * Null on any failure. The palette is an affordance, not a correctness
     * requirement, so a directory that is down costs the shortcut and nothing
     * else.
     */
    suspend fun fetchPublishedProfile(address: String, relayUrl: String): PublishedProfile? {
        val httpBase = relayUrl.replace(Regex("^ws"), "http").trimEnd('/')
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val url = "$httpBase/api/agents/$address"
                FileLogger.d(LogTags.AGENT_DISCOVERY, "GET ${url.take(60)}…")

                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        FileLogger.w(LogTags.AGENT_DISCOVERY, "directory returned ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    json.decodeFromString<DirectoryEntry>(body).profile
                }
            }.onFailure { e ->
                FileLogger.w(LogTags.AGENT_DISCOVERY, "Failed to fetch published profile: ${e.message}")
            }.getOrNull()
        }
    }

    @kotlinx.serialization.Serializable
    private data class AgentInfo(
        val address: String? = null
    )

    @kotlinx.serialization.Serializable
    private data class DirectoryEntry(
        val profile: PublishedProfile? = null
    )

    /** The relay's stored copy of what an agent published about itself. */
    @kotlinx.serialization.Serializable
    data class PublishedProfile(
        val alias: String? = null,
        val model: String? = null,
        val tools: List<String> = emptyList(),
        val skills: List<PublishedSkill> = emptyList()
    )

    @kotlinx.serialization.Serializable
    data class PublishedSkill(
        val name: String = "",
        val description: String? = null
    )

    companion object {
        /**
         * A ConnectOnion address is an Ed25519 public key: `0x` + 64 hex chars,
         * 66 in total. This was `{40}` — an Ethereum-style address — so every
         * real address failed the check and fell through to the "unrecognized
         * input" branch, which happens to return the same value and so hid the
         * bug behind a warning on every single connect.
         */
        internal val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{64}$")
        private const val DISCOVERY_TIMEOUT_SECONDS = 10L
    }
}
