package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun UserBubble(
    item: ChatItem.User,
    timestamp: String?,
    renderMarkdown: Boolean = true,
    showSender: Boolean = true,
    onResend: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        MessageBubble(
            content = item.content,
            isUser = true,
            images = item.images,
            files = item.files,
            // The footer slot rather than a new badge: a message that has not
            // been delivered has no send time yet, so the line is free.
            timestamp = when (item.state) {
                UserMessageState.QUEUED -> "Waiting to send…"
                UserMessageState.FAILED -> null
                UserMessageState.SENT -> timestamp
            },
            renderMarkdown = renderMarkdown,
            showSender = showSender
        )

        if (item.state == UserMessageState.FAILED) {
            // Beside the message it belongs to, not a banner: a banner can only
            // ever offer to resend "the last one", which is the wrong message
            // as soon as anything else has been sent since.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                modifier = Modifier
                    .padding(top = MaterialTheme.spacing.xs)
                    .then(if (onResend != null) Modifier.clickable(onClick = onResend) else Modifier)
                    .semantics { contentDescription = "Not sent. Tap to try again." }
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (onResend != null) "Not sent · Tap to try again" else "Not sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun AgentBubble(item: ChatItem.Agent, timestamp: String?, renderMarkdown: Boolean = true, showSender: Boolean = true) {
    MessageBubble(
        content = item.content,
        isUser = false,
        images = item.images,
        timestamp = timestamp,
        renderMarkdown = renderMarkdown,
        showSender = showSender
    )
}

/**
 * Render a [ChatItem.Turn] (one full LLM turn) — the shape every assistant
 * reply takes, live off the wire and reloaded from Room alike.
 *
 * No avatar or sender label: this is the live flow's existing presentation,
 * and the reload path was made to match it rather than the other way round.
 * A turn with no [ChatItem.Turn.thinking] — a server replay, or a row written
 * before turn metadata was stored — still gets a footer holding the tick
 * alone, so it does not read as a different kind of message.
 */
@Composable
internal fun TurnBubble(
    item: ChatItem.Turn,
    onRetry: (() -> Unit)? = null,
    renderMarkdown: Boolean = true
) {
    val agent = item.agent
    val thinking = item.thinking
    // Retry starts a whole turn and is not serialised anywhere below it, so a
    // second tap before the first turn reaches the wire sends two INPUT frames
    // and the agent answers twice. Latched here rather than in the ViewModel:
    // sendMessage is fire-and-forget, so there is no completion to clear a
    // flag on. A genuine second attempt passes through RUNNING on its way back
    // to ERROR, which is what re-arms the button.
    var retryTapped by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(thinking?.status) {
        if (thinking?.status != ThinkingStatus.ERROR) retryTapped = false
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (agent != null) {
            // Images (e.g. an "agent_image" event merged onto this Turn by
            // ChatEventReducer) render above the text, matching MessageBubble's
            // own content+images ordering.
            if (!agent.images.isNullOrEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ImageGridBubble(images = agent.images, isUser = false)
                }
                if (agent.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                }
            }
            if (agent.content.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Same composable AgentBubble uses. Hand-rolling the bubble
                    // here meant a live reply arrived as raw "### heading" /
                    // "**bold**" and only started rendering once the session
                    // was replayed from history as a ChatItem.Agent.
                    TextBubbleContent(
                        content = agent.content,
                        isUser = false,
                        renderMarkdown = renderMarkdown
                    )
                }
            }
        } else if (thinking?.status == ThinkingStatus.ERROR && !thinking.content.isNullOrBlank()) {
            // No successful reply arrived; the connection error takes over this Turn's bubble instead, styled like a real reply (errorContainer tint) so the failure reason stays visible. Monospace since server error reports use box-drawing/aligned fields.
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(end = MaterialTheme.spacing.xxxl)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
                ) {
                    SelectionContainer {
                        Text(
                            text = thinking.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    // Resends the paired user message as a fresh send over the
                    // still-alive connection — mirrors the reference web
                    // client's "Retry" (distinct from "Reconnect", which is
                    // ConnectionBanner's job).
                    if (onRetry != null) {
                        TextButton(
                            onClick = {
                                retryTapped = true
                                onRetry()
                            },
                            enabled = !retryTapped
                        ) {
                            Text(
                                text = "Retry",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
        // A reply restored from the server carries no metadata — the wire
        // transcript is role and content only, so duration, tokens and cost
        // never existed for it. Still footer it with the bare tick, or a
        // restored turn looks unlike every other finished one.
        when {
            thinking != null -> TurnThinkingFooter(thinking)
            agent != null -> TurnThinkingFooter(ChatItem.Thinking(id = item.id, status = ThinkingStatus.DONE))
        }
    }
}

/**
 * The footer's one-line summary — "2s · 46 tokens" once a turn is done.
 *
 * A finished turn names neither its state nor its model: the tick already says
 * "done", and the model sits permanently in the top bar. Running and failed
 * still lead with a word, and failed keeps the model. A cost that would print
 * as $0.0000 is dropped.
 */
internal fun turnFooterLabel(thinking: ChatItem.Thinking): String {
    val parts = mutableListOf<String>()
    when (thinking.status) {
        ThinkingStatus.RUNNING -> parts += "Generating…"
        ThinkingStatus.ERROR -> parts += "Failed"
        ThinkingStatus.DONE -> Unit
    }
    if (thinking.status != ThinkingStatus.DONE) thinking.model?.let { parts += it }
    thinking.durationMs?.let { parts += "${(it / 1000).toInt()}s" }
    thinking.tokensTotal?.let { parts += "${formatTokenCount(it)} tokens" }
    thinking.costUsd?.takeIf { it >= MIN_DISPLAY_COST_USD }?.let { parts += formatCostUsd(it) }
    return parts.joinToString(" · ")
}

@Composable
internal fun TurnThinkingFooter(thinking: ChatItem.Thinking) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        modifier = Modifier.padding(start = MaterialTheme.spacing.md, top = MaterialTheme.spacing.xxs)
    ) {
        StatusIcon(
            status = when (thinking.status) {
                ThinkingStatus.RUNNING -> IndicatorStatus.RUNNING
                ThinkingStatus.ERROR -> IndicatorStatus.ERROR
                ThinkingStatus.DONE -> IndicatorStatus.DONE
            },
            size = 10.dp,
            runningSize = 10.dp,
            strokeWidth = 1.5.dp,
            // A bare stroked tick in the outline colour, per the design: this
            // line is ambient, and a filled CheckCircle in `primary` announced
            // every finished turn as a green blob.
            doneIcon = Icons.Default.Check,
            doneTint = MaterialTheme.colorScheme.outline,
            // On a turn restored from the server the tick is the whole
            // footer, so without this a screen reader reaches an empty row.
            doneContentDescription = "Done",
            // Cancel (X), not a tick — a checkmark reads as success regardless of tint.
            errorIcon = Icons.Default.Cancel,
            errorTint = MaterialTheme.colorScheme.error
        )
        val label = turnFooterLabel(thinking)
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}
