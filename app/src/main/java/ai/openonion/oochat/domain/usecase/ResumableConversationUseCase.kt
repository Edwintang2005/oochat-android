package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.SessionRepository
import kotlinx.coroutines.flow.first

/**
 * Which conversation an agent's next connect should resume.
 *
 * A seam, not just a class, so a caller can be tested without Room.
 */
fun interface ResumableConversationLookup {
    /**
     * The conversation id [agentAddress] would come back to, or null when the
     * agent is unknown locally or has only empty placeholder rows — the caller
     * then connects on a session the server mints.
     */
    suspend fun forAgent(agentAddress: String): String?
}

/**
 * Reads the resumable conversation from Room without resolving or mutating
 * anything.
 *
 * Exists for callers that must know the conversation *before* they open a
 * socket but do not own the conversation themselves — the LoadingScreen
 * probe. Resolving it (and loading its rows) still belongs to
 * [ConversationHistoryUseCase.ensureActiveSession], which is why this is a
 * separate read rather than a call into it.
 */
class ResumableConversationUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository
) : ResumableConversationLookup {

    override suspend fun forAgent(agentAddress: String): String? {
        val agent = agentRepository.getAgentByAddress(agentAddress) ?: return null
        // Same ordering (updated_at DESC) and same skip rule as
        // ensureActiveSession's resume branch, so both land on one conversation.
        return sessionRepository.getSessionsByAgent(agent.id).first()
            .firstOrNull { !it.isEmptyPlaceholder() }
            ?.id
    }
}
