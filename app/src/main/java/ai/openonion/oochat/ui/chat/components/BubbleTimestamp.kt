package ai.openonion.oochat.ui.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun BubbleTimestamp(timestamp: String, modifier: Modifier = Modifier) {
    Text(
        text = timestamp,
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
    )
}
