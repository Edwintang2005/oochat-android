package ai.openonion.oochat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Central corner-radius scale for the app. Component code should reference these via
 * `MaterialTheme.shapes.{small,medium,large,...}` instead of hardcoding RoundedCornerShape,
 * so the whole app's roundness can be tuned from one place.
 *
 * Values follow the Material 3 shape scale; adjust here to restyle globally.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),   // small surfaces (log console, chips)
    medium = RoundedCornerShape(12.dp), // cards, sheets
    large = RoundedCornerShape(16.dp),  // chat bubbles, icon boxes
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Chat message bubble shape: fully rounded except a small "tail" corner on
 * the sender's side (bottom-end for the user, top-start for the agent).
 * Lives in the theme (not chat/components) because Settings' font-size
 * preview renders the same bubbles — radius comes from
 * [MessageBubbleToken.BorderRadius] so the whole app tunes in one place.
 */
fun bubbleShape(isUser: Boolean): RoundedCornerShape {
    val cornerRadius = MessageBubbleToken.BorderRadius
    val tailSize = 4.dp
    return if (isUser) {
        RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = cornerRadius,
            bottomEnd = tailSize
        )
    } else {
        RoundedCornerShape(
            topStart = tailSize,
            topEnd = cornerRadius,
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius
        )
    }
}
