package ai.openonion.oochat.domain.model

/**
 * OpenOnion account profile — the balance/credits breakdown returned by
 * `GET /api/v1/auth/me`. Mirrors the web client's `UserProfile` shape
 * (`store/chat-store.ts`) so both clients agree on units (USD).
 */
data class UserProfile(
    val publicKey: String,
    val creditsUsd: Double,
    val totalCostUsd: Double,
    val balanceUsd: Double
)
