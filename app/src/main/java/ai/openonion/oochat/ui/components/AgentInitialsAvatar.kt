package ai.openonion.oochat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Initials chip for an agent's name. Shape/color/letter-count knobs let the
 * same rendering logic serve both the circular Agent Address entries and the
 * square single-letter variants used elsewhere.
 */
@Composable
fun AgentInitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    shape: Shape = CircleShape,
    maxInitials: Int = 2,
    backgroundColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    border: BorderStroke? = null
) {
    val initials = name.trim().split(" ").filter { it.isNotBlank() }
        .take(maxInitials).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}
