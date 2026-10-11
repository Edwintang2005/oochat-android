package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The messages [FailedMessagesBar] offers to resend, oldest first, as the transcript orders them. */
internal fun List<ChatItem>.failedUserMessages(): List<ChatItem.User> =
    filterIsInstance<ChatItem.User>().filter { it.state == UserMessageState.FAILED }

/**
 * One line naming a failed message, so a Retry can say which message it sends.
 *
 * The text when there is any; otherwise what was attached, since an image or
 * file message held too long fails with no words to quote.
 */
internal fun failedMessagePreview(item: ChatItem.User): String {
    item.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { return it }
    val files = item.files.orEmpty()
    val images = item.images.orEmpty()
    return when {
        files.size == 1 && images.isEmpty() -> files.single().name
        files.isNotEmpty() && images.isNotEmpty() -> "${images.size + files.size} attachments"
        files.isNotEmpty() -> "${files.size} files"
        images.size == 1 -> "Image"
        images.isNotEmpty() -> "${images.size} images"
        else -> "Message"
    }
}

/**
 * Failed messages, offered for resending where the user is about to type.
 *
 * The bubble's own "Tap to try again" stays — this is a second way in for a
 * message that has scrolled out of sight. It is not a "resend the last one"
 * banner (see [UserBubble]): every row quotes its message and resends that one,
 * so with several failures each still has its own Retry.
 *
 * Renders nothing when nothing has failed. A row goes away on its own once its
 * message leaves FAILED, which a resend does at once by queueing it.
 *
 * @param failed The FAILED user messages, oldest first.
 * @param onRetry Resends the message with this id.
 */
@Composable
internal fun FailedMessagesBar(
    failed: List<ChatItem.User>,
    onRetry: (messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (failed.isEmpty()) return
    val error = MaterialTheme.colorScheme.error

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (failed.size > 1) {
            Text(
                text = "${failed.size} messages not sent",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = error,
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.spacing.lg)
                    .padding(top = MaterialTheme.spacing.xs2)
                    .semantics { heading() }
            )
        }
        // Capped so a run of failures cannot push the composer off a small
        // screen; past three rows the rest scroll.
        Column(modifier = Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
            failed.forEach { item ->
                FailedMessageRow(item = item, onRetry = { onRetry(item.id) })
            }
        }
    }
}

@Composable
private fun FailedMessageRow(item: ChatItem.User, onRetry: () -> Unit) {
    val preview = failedMessagePreview(item)
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = MaterialTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = error,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Not sent: $preview",
            style = MaterialTheme.typography.labelSmall,
            color = error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onRetry)
                // The visible word alone would leave TalkBack reading a column
                // of identical "Retry"s; the label names the message.
                .semantics { contentDescription = "Retry sending: $preview" }
                .padding(horizontal = MaterialTheme.spacing.lg)
        ) {
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
