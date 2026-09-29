package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.ui.components.AssistantAvatar
import ai.openonion.oochat.ui.theme.MessageBubbleToken
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Caps a bubble at [fraction] of the width its row offers, never above [max].
 *
 * Only the max constraint is tightened: `fillMaxWidth(fraction)` would also pin
 * short bubbles to that exact width. The modifier reports the child's own
 * measured width, so the parent Column's Start/End alignment still anchors user
 * bubbles right and agent bubbles left.
 */
internal fun Modifier.bubbleMaxWidth(fraction: Float, max: Dp): Modifier = layout { measurable, constraints ->
    val ceiling = max.roundToPx()
    val cap = if (constraints.hasBoundedWidth) {
        minOf((constraints.maxWidth * fraction).roundToInt(), ceiling)
    } else {
        ceiling
    }
    val placeable = measurable.measure(
        constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = cap)
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Modern message bubble component matching Figma design specifications.
 *
 * Features:
 * - Asymmetric corner radius (pointed tail toward sender)
 * - Block-level Markdown rendering (headings, lists, code blocks, blockquotes,
 *   horizontal rules) plus inline styling (bold/italic/code/links)
 * - Image grid attachments (1 image = 4:3, 2+ images = 2-column 1:1 grid)
 * - Assistant avatar with icon
 * - Timestamp display
 * - Smooth entrance animation
 *
 * @param content Message text content (supports Markdown)
 * @param isUser True if message is from user, false for assistant
 * @param images Optional image URLs/URIs to render as an attachment grid above the text
 * @param timestamp Optional timestamp to display below bubble
 * @param modifier Optional modifier for the container
 */
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    images: List<String>? = null,
    files: List<ChatFileAttachment>? = null,
    timestamp: String? = null,
    renderMarkdown: Boolean = true,
    // False for a message that continues a run from the same sender — see
    // MessageListScreen's grouping. The avatar and name only mark where a run
    // starts; repeating them on every bubble is the clutter chat UIs group away.
    showSender: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasText = content.isNotBlank()
    val hasImages = !images.isNullOrEmpty()
    val hasFiles = !files.isNullOrEmpty()

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it / 8 }) + fadeIn(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isUser) 48.dp else MaterialTheme.spacing.lg,
                    end = if (isUser) MaterialTheme.spacing.lg else 48.dp,
                    top = MaterialTheme.spacing.xxs,
                    bottom = MaterialTheme.spacing.xxs
                ),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Assistant avatar and name
            if (!isUser && showSender) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
                ) {
                    AssistantAvatar()
                    Text(
                        text = "Agent",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            }

            if (hasImages) {
                ImageGridBubble(images = images.orEmpty(), isUser = isUser)
                if (hasFiles || hasText) Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            }

            if (hasFiles) {
                Row(
                    modifier = Modifier
                        .bubbleMaxWidth(MessageBubbleToken.MaxWidthFraction, MessageBubbleToken.MaxWidth)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    // The Row fills the bubble cap, so the parent Column's End
                    // alignment only moves its right edge — the chips still need
                    // to be pushed to that edge to line up with the text bubble.
                    horizontalArrangement = Arrangement.spacedBy(
                        MaterialTheme.spacing.xs,
                        if (isUser) Alignment.End else Alignment.Start
                    )
                ) {
                    files.orEmpty().forEach { file ->
                        AttachmentFileChip(name = file.name)
                    }
                }
                if (hasText) Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            }

            if (hasText) {
                TextBubbleContent(content = content, isUser = isUser, renderMarkdown = renderMarkdown)
            }

            // Timestamp
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                BubbleTimestamp(timestamp)
            }
        }
    }
}

