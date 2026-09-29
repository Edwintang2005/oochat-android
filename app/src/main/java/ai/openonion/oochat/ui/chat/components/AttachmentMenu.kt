package ai.openonion.oochat.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * The input bar's attachment entry point: a "+" button that rotates into an
 * "×" and opens an upward-anchored dropdown panel with three entries —
 * camera / photo library / files. Replaces the old single-purpose image
 * icon.
 *
 * A dropdown, not a bottom sheet: a sheet would cover the text field the
 * user is mid-edit on, and three items is too little content to justify a
 * sheet's weight. It opens *upward*, into the message list's scroll area
 * above the input bar — that space isn't affected by the IME the way the
 * area below the input bar is.
 *
 * Self-contained like [TextInputField]'s focus state: owns its own
 * `expanded` state and measures its own position, so [InputBar] only needs
 * to supply the three tap callbacks (which own the actual
 * launchers/permission checks — kept there per the existing "smart
 * container vs. pure UI" split between InputBar.kt and this file).
 *
 * The button wraps an [IconButton] (default 48dp touch target) around the
 * 40dp visual circle, matching every other icon-only control in the input
 * bar (see SendButton/StopButton/MicButton) — only the invisible hit area
 * grows, the glyph and tint background stay pixel-identical.
 */
@Composable
fun AttachmentMenuButton(
    enabled: Boolean,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val minClearancePx = remember(density) { with(density) { MIN_CLEARANCE_DP.dp.roundToPx() } }
    val verticalGapPx = remember(density) { with(density) { VERTICAL_GAP_DP.dp.roundToPx() } }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(durationMillis = 200, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "attachmentMenuPlusRotation"
    )

    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        expanded -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.outline
    }
    val background = if (expanded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent

    IconButton(
        onClick = { expanded = !expanded },
        enabled = enabled,
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorPositionInWindow = coordinates.positionInWindow()
        }
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (expanded) "Close attachment menu" else "Open attachment menu",
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }

    if (expanded) {
        Popup(
            popupPositionProvider = remember(anchorPositionInWindow, minClearancePx, verticalGapPx) {
                UpwardAnchoredPositionProvider(
                    anchorPosition = IntOffset(
                        anchorPositionInWindow.x.roundToInt(),
                        anchorPositionInWindow.y.roundToInt()
                    ),
                    minClearancePx = minClearancePx,
                    verticalGapPx = verticalGapPx
                )
            },
            onDismissRequest = { expanded = false },
            // Non-focusable so opening the menu doesn't pull focus off the
            // text field and drop the keyboard mid-compose. Outside taps still
            // dismiss (the popup watches those without holding focus); back
            // press is the one thing focus bought, hence the handler below.
            properties = PopupProperties(focusable = false)
        ) {
            BackHandler(enabled = true) { expanded = false }
            AttachmentMenuPanel(
                onCameraClick = { expanded = false; onCameraClick() },
                onGalleryClick = { expanded = false; onGalleryClick() },
                onFileClick = { expanded = false; onFileClick() }
            )
        }
    }
}

/**
 * Positions the panel just above the "+" button, left-aligned to it — unless
 * there isn't [minClearancePx] of vertical room above the button (a very
 * short screen, or the button sitting high up already), in which case the
 * panel falls back to screen center instead of rendering clipped or
 * upside-down.
 *
 * Takes the anchor position as measured by the caller's own
 * [androidx.compose.ui.layout.onGloballyPositioned] rather than the
 * [anchorBounds] this callback receives — both describe the same button, but
 * measuring it explicitly keeps the "+" button's own position (not
 * whatever ancestor layout node Popup happens to attach to) as the single
 * source of truth for anchoring. [minClearancePx]/[verticalGapPx] are
 * already density-converted by the caller, since this provider has no
 * [androidx.compose.ui.unit.Density] of its own to convert dp with.
 */
private class UpwardAnchoredPositionProvider(
    private val anchorPosition: IntOffset,
    private val minClearancePx: Int,
    private val verticalGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val hasClearanceAbove = anchorPosition.y >= minClearancePx
        return if (hasClearanceAbove) {
            IntOffset(
                x = anchorPosition.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                y = (anchorPosition.y - popupContentSize.height - verticalGapPx).coerceAtLeast(0)
            )
        } else {
            IntOffset(
                x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
                y = ((windowSize.height - popupContentSize.height) / 2).coerceAtLeast(0)
            )
        }
    }
}

// Design spec values: fall back to centered if less than 180dp of clearance
// sits above the button; otherwise leave an 8dp gap between the button and
// the panel.
private const val MIN_CLEARANCE_DP = 180
private const val VERTICAL_GAP_DP = 8

@Composable
private fun AttachmentMenuPanel(
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AttachmentMenuItem(
            icon = Icons.Default.PhotoCamera,
            label = "Camera",
            hint = "Take a photo",
            onClick = onCameraClick
        )
        AttachmentMenuDivider()
        AttachmentMenuItem(
            icon = Icons.Default.Image,
            label = "Photo library",
            hint = "Choose from your photos",
            onClick = onGalleryClick
        )
        AttachmentMenuDivider()
        AttachmentMenuItem(
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            label = "Browse files",
            hint = "Documents, PDFs, and more",
            onClick = onFileClick
        )
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.08f else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "attachmentMenuItemPress"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = pressAlpha))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Hairline row separator, inset 16dp on each side — a full-bleed divider read as too heavy at this row height. */
@Composable
private fun AttachmentMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
