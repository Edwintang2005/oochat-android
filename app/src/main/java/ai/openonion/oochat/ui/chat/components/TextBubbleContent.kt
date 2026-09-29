package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.chat.markdown.MarkdownRenderer
import ai.openonion.oochat.ui.theme.MessageBubbleToken
import ai.openonion.oochat.ui.theme.bubbleShape
import ai.openonion.oochat.ui.theme.chatBody
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun TextBubbleContent(
    content: String,
    isUser: Boolean,
    renderMarkdown: Boolean = true,
    modifier: Modifier = Modifier
) {
    // The agent's reply is the focal tier, so it gets the fill; the user's own
    // message is outlined. It was a `primary` fill — the brightest block on
    // screen carrying the least new information.
    val shape = bubbleShape(isUser)
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            // Same proportional cap the image/voice bubbles use — see
            // MessageBubbleToken.
            .bubbleMaxWidth(MessageBubbleToken.MaxWidthFraction, MessageBubbleToken.MaxWidth)
            .clip(shape)
            .then(
                if (isUser) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                }
            )
            .padding(
                horizontal = MessageBubbleToken.PaddingHorizontal,
                vertical = MessageBubbleToken.PaddingVertical
            )
    ) {
        SelectionContainer {
            if (renderMarkdown) {
                MarkdownRenderer.RenderBlocks(
                    text = content,
                    textColor = textColor
                )
            } else {
                Text(
                    text = content,
                    color = textColor,
                    style = MaterialTheme.typography.chatBody
                )
            }
        }
    }
}
