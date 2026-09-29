package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import androidx.compose.runtime.Composable

@Composable
internal fun ChatItemBubble(
    item: ChatItem,
    timestamp: String?,
    onRespond: (String) -> Unit,
    onApprove: (approved: Boolean, scope: String, mode: String?, feedback: String?) -> Unit,
    onOnboard: (method: String, inviteCode: String?, payment: Double?) -> Unit,
    onPlanReviewResponse: (String) -> Unit,
    onUlwResponse: (action: String, turns: Int?, mode: String?) -> Unit,
    renderMarkdown: Boolean = true,
    showSender: Boolean = true,
    onRetry: (() -> Unit)? = null,
    hasLiveConnection: Boolean = true,
    onResend: (() -> Unit)? = null
) {
    when (item) {
        is ChatItem.User -> UserBubble(item, timestamp, renderMarkdown, showSender, onResend)
        is ChatItem.Agent -> AgentBubble(item, timestamp, renderMarkdown, showSender)
        is ChatItem.Thinking -> {
            // While running with no metadata yet, show the Figma-style
            // bouncing-dots typing indicator; once done/errored (or a model
            // is already known), fall back to the richer status bubble.
            if (item.status == ThinkingStatus.RUNNING && item.model == null && item.content == null) {
                TypingIndicator()
            } else {
                ThinkingBubble(item, timestamp, onRetry)
            }
        }
        is ChatItem.Turn -> TurnBubble(item, onRetry, renderMarkdown)
        is ChatItem.ToolCall -> ToolCallDispatch(item)
        is ChatItem.AskUser -> AskUserCard(item, onRespond)
        is ChatItem.ApprovalNeeded -> ApprovalCard(item, onApprove)
        // Deliberately one call site for both variants. Compose gives each
        // branch its own identity, so two calls would rebuild the card — and
        // drop the keyboard — every time the gate swapped between them.
        is ChatItem.OnboardRequired, is ChatItem.OnboardingFailed -> {
            val rejection = (item as? ChatItem.OnboardingFailed)?.reason
            OnboardGateCard(
                title = if (rejection != null) "Onboarding Rejected" else "Onboarding Required",
                subtitle = if (rejection != null) {
                    "Try again with a different invite code."
                } else {
                    "This agent requires an invite code to activate your session."
                },
                onOnboard = onOnboard,
                errorReason = rejection,
                hasLiveConnection = hasLiveConnection
            )
        }
        is ChatItem.OnboardSuccess -> OnboardSuccessCard(item)
        is ChatItem.IntentItem -> IntentCard(item)
        is ChatItem.EvalItem -> EvalCard(item)
        is ChatItem.CompactItem -> CompactDivider(item)
        is ChatItem.ToolBlockedItem -> ToolBlockedCard(item)
        is ChatItem.FilesReceivedItem -> FilesReceivedCard(item)
        is ChatItem.DiffPreviewItem -> DiffPreviewCard(item)
        is ChatItem.PlanReviewItem -> PlanReviewCard(item, onPlanReviewResponse)
        is ChatItem.UlwTurnsReachedItem -> UlwTurnsReachedCard(item, onUlwResponse)
        is ChatItem.ModeChangedItem -> ModeChangedDivider(item)
    }
}
