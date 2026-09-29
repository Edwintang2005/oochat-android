package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.InputBarToken
import ai.openonion.oochat.ui.theme.chatBody
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Default action, so enter stays a newline: Send is in the pill in every state,
 * and nothing here has to carry a send. Constant on purpose — CoreTextField
 * restarts the input connection whenever `imeOptions` change on a focused
 * field, and a restart re-shows a keyboard the user just dismissed.
 */
private val ComposerKeyboardOptions = KeyboardOptions.Default

@Composable
fun TextInputField(
    // A TextFieldValue, not a String: the slash palette rewrites the whole
    // field on selection, and the String overload keeps the old caret offset
    // instead of following the text out to the arguments.
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isConnected: Boolean,
    // Live socket, but the agent is gated on an invite code. Distinct from
    // !isConnected: telling the user to "connect first" when the header says
    // Connected is the kind of contradiction that makes people re-check their
    // network instead of the card sitting right above the field.
    awaitingOnboardCode: Boolean = false,
    // Status only — it no longer locks the field. An INPUT arriving on a
    // running session is pushed to the agent as runtime input, so typing now is
    // an interjection, not a mistake. The placeholder still says the agent is
    // busy, since an empty field is where that fact is worth stating.
    isAgentWorking: Boolean = false,
    attachmentCount: Int,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted so InputBar can lock the approval chip while the field has focus
    // — the chip now sits below the field, in the thumb's path to the keyboard.
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    trailingContent: (@Composable () -> Unit)? = null
) {
    val hasAttachments = attachmentCount > 0
    val editable = isConnected
    val placeholderText = when {
        // Onboarding runs on a socket that hasn't reached Connected yet, so it
        // is checked inside the disconnected branch, as before.
        !isConnected && awaitingOnboardCode -> "Redeem the invite code above to start"
        !isConnected -> "Connect to an agent first"
        isAgentWorking -> "Agent is working…"
        // Send is disabled until there's text — see InputBar's canSend. Say so
        // here rather than leaving a greyed button with no explanation.
        hasAttachments -> "Add a message to send with your attachment"
        else -> "Message…"
    }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val glowAlpha by animateFloatAsState(if (isFocused) 1f else 0f, label = "pillGlow")

    Box(modifier = modifier.fillMaxWidth()) {
        // Glow ring — pill radius plus the xs inset the pill sits inside.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(InputBarToken.BorderRadius + 3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * glowAlpha))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.xs)
                .clip(RoundedCornerShape(InputBarToken.BorderRadius))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                // Quieter at rest, full weight only once focused: an empty text
                // field held at 1.5dp competed with the messages above it.
                .border(
                    if (isFocused) {
                        BorderStroke(InputBarToken.BorderWidth, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                    RoundedCornerShape(InputBarToken.BorderRadius)
                )
                .padding(start = MaterialTheme.spacing.xs2, top = MaterialTheme.spacing.xs2, end = MaterialTheme.spacing.xs2, bottom = MaterialTheme.spacing.xs2)
        ) {
            AttachmentMenuButton(
                enabled = editable,
                onCameraClick = onCameraClick,
                onGalleryClick = onGalleryClick,
                onFileClick = onFileClick
            )

            val isEmpty = value.text.isEmpty()

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = editable,
                maxLines = 5,
                keyboardOptions = ComposerKeyboardOptions,
                textStyle = MaterialTheme.typography.chatBody.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = InputBarToken.MinHeight)
                    .padding(vertical = 5.dp)
            ) { innerTextField ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.semantics {
                        if (isEmpty) {
                            this.contentDescription = placeholderText
                        }
                    }
                ) {
                    if (isEmpty) {
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.chatBody.copy(letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }

            // Pending-attachment count — inline in the pill bar rather than
            // overlaid on the attach button, since the button no longer
            // represents "images" specifically once it opens the camera/
            // gallery/files menu.
            if (hasAttachments) {
                Box(
                    modifier = Modifier
                        // Opts out of the Row's Bottom alignment: the badge is
                        // shorter than the buttons and would otherwise sit on
                        // the pill floor instead of beside the send icon.
                        .align(Alignment.CenterVertically)
                        .padding(horizontal = MaterialTheme.spacing.xs)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = attachmentCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            trailingContent?.invoke()
        }
    }
}
