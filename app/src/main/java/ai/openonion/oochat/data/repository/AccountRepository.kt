package ai.openonion.oochat.data.repository

import ai.openonion.oochat.domain.model.UserProfile

/**
 * Bearer token + the profile it unlocked, returned together since Settings
 * displays the raw token itself as "API Key" (matching the web client's
 * `openonionApiKey` field) alongside the balance breakdown.
 */
data class AccountSnapshot(
    val apiKey: String,
    val profile: UserProfile
)

/**
 * Repository for the OpenOnion account service (balance/credits). UI must
 * not talk to `oo.openonion.ai` directly - only through this interface.
 */
interface AccountRepository {
    /**
     * Authenticates (if not already cached, or if [forceReauth]) and fetches
     * the account profile in one call.
     */
    suspend fun loadAccount(forceReauth: Boolean = false): Result<AccountSnapshot>

    /**
     * Forgets all identity-scoped cached state (the cached JWT). MUST be
     * called whenever the wallet identity changes — reset or import via
     * [ai.openonion.oochat.crypto.KeyManager] — so a subsequent
     * [loadAccount] re-authenticates as the *current* identity instead of
     * serving the previous identity's balance/API key/profile from a token
     * that is still cryptographically valid server-side.
     */
    fun invalidate()
}
