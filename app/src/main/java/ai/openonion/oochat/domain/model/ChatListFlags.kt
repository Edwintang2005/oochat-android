package ai.openonion.oochat.domain.model

/**
 * The list-wide answers the chat screen's derived flows need, all read off one
 * walk of the chat list.
 *
 * They used to be four independent scans plus a filter, each re-running on
 * every server event: "is the agent working", "is an onboarding code owed",
 * "has onboarding since succeeded", "is a gate waiting on the user". A turn
 * emits many frames, so a long conversation was walked several times per
 * frame purely to re-derive booleans that almost never change.
 */
data class ChatListFlags(
    /** Any item still in flight — drives the input bar's Send/Stop swap. */
    val hasInProgress: Boolean,
    /** An onboarding prompt is on screen, answered or not. */
    val hasOnboardPrompt: Boolean,
    /** Onboarding has since succeeded, which retires any prompt above it. */
    val hasOnboardSuccess: Boolean,
    /** Something only the user can answer is blocking the run. */
    val hasPendingGate: Boolean
) {
    companion object {
        val NONE = ChatListFlags(
            hasInProgress = false,
            hasOnboardPrompt = false,
            hasOnboardSuccess = false,
            hasPendingGate = false
        )

        /**
         * Deliberately one loop with four accumulators rather than four
         * `any {}` calls: each of those walks from the start again, and the
         * common answer is "no" — the case that reads every item.
         */
        fun of(items: List<ChatItem>): ChatListFlags {
            var inProgress = false
            var onboardPrompt = false
            var onboardSuccess = false
            var pendingGate = false
            for (item in items) {
                if (!inProgress && item.isInProgress()) inProgress = true
                when (item) {
                    is ChatItem.OnboardRequired, is ChatItem.OnboardingFailed -> onboardPrompt = true
                    is ChatItem.OnboardSuccess -> onboardSuccess = true
                    // An answered approval is a record of what was decided,
                    // not a question still standing in the way.
                    is ChatItem.ApprovalNeeded -> if (item.decision == null) pendingGate = true
                    is ChatItem.AskUser,
                    is ChatItem.PlanReviewItem,
                    is ChatItem.UlwTurnsReachedItem -> pendingGate = true
                    else -> {}
                }
            }
            return ChatListFlags(inProgress, onboardPrompt, onboardSuccess, pendingGate)
        }
    }
}
