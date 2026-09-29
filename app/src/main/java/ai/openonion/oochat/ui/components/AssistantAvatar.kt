package ai.openonion.oochat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Agent-brand avatar (star on rounded square), reused in chat bubbles/typing
 * indicator (20dp) and Settings' font-size preview (28dp).
 *
 * The corner radius used to be a parameter defaulting to 6dp — the app's only
 * value off the 4/8/12/16/28 shape scale, and every caller either took it or
 * passed 8dp. It's now `shapes.small` for everyone.
 */
@Composable
fun AssistantAvatar(
    size: Dp = 20.dp,
    iconSize: Dp = 10.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(iconSize)
        )
    }
}
