package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.spacing
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Sends the ConnectOnion `INTERRUPT` frame (see ChatViewModel.interrupt). It
 * sits on the shelf row below the field, not in the trailing slot: sending
 * during a run is a real option now, so Send kept its place and Stop needed one
 * of its own.
 *
 * Neutral, and labelled in the shelf's own language: green is this theme's
 * "go", and spending it on the one irreversible control here read wrong. Same
 * chip geometry as [ApprovalModeChip], which shares the row. It is always live
 * — it used to grey out mid-dictation, back when the mic slot showed a second
 * square glyph.
 */
@Composable
internal fun StopButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "stopButtonScale")

    Row(
        modifier = Modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            interactionSource = interactionSource
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Stop",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val background = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline

    // Scale down slightly on press (matches the Figma design's button press
    // feedback). Reuses the button's own interaction source rather than
    // adding a second clickable, so this is purely a visual layer.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "sendButtonScale")

    // IconButton is outer (no explicit size = default 48dp touch target);
    // the 40dp filled circle that used to be the button is now its content,
    // so the visual is unchanged.
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interactionSource) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Send",
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun AttachmentThumbnail(uri: Uri, onRemove: () -> Unit, index: Int = 0, total: Int = 1) {
    Box(modifier = Modifier.size(72.dp)) {
        AsyncImage(
            model = uri,
            contentDescription = "Selected image ${index + 1} of $total",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
        )
        // Overhangs the thumbnail's corner (Figma: top:-6,right:-6) rather
        // than sitting flush inside it. The 8dp gap between thumbnails
        // (see the LazyRow spacing above) comfortably clears this 6dp
        // overhang without touching the neighboring thumbnail.
        //
        // 48dp touch target: align(TopEnd) + offset() fixes this box's
        // top-right corner in place independent of its size, so growing
        // 40dp -> 48dp only extends the invisible hit area down and left
        // (into this thumbnail's own image), never right/up into the
        // neighbor's space or above the row.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.surface), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove image",
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        }
    }
}

/** Dashed-border "+" tile at the end of the thumbnail row, up to the 4-image cap. */
@Composable
internal fun AddMoreTile(enabled: Boolean, onClick: () -> Unit) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind {
                val stroke = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
                )
                drawRoundRect(
                    color = outlineColor,
                    style = stroke,
                    cornerRadius = CornerRadius(10.dp.toPx())
                )
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add more images",
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * Preview chip for a picked (non-image) file, shown in the same strip as
 * [AttachmentThumbnail] above the input field. Unlike an image, a file has
 * no visual to thumbnail, so this shows a document icon plus the file's own
 * name instead.
 *
 * Also reused read-only (no remove button) inside a sent `ChatItem.User`
 * bubble — see [MessageBubble] — by passing [onRemove] = null.
 */
@Composable
internal fun AttachmentFileChip(name: String, onRemove: (() -> Unit)? = null) {
    Box(modifier = Modifier.size(width = 88.dp, height = 72.dp)) {
        Column(
            modifier = Modifier
                .size(width = 88.dp, height = 72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.surface), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove $name",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
