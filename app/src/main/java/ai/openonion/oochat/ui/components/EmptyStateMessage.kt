package ai.openonion.oochat.ui.components

import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centered empty/error-state skeleton: an [icon] slot (each feature keeps
 * its own icon-box styling), a title, a body paragraph, and an optional
 * [action] slot. The Box/Column/typography arrangement was previously
 * copy-pasted across the three discovery states (idle/empty/error) and the
 * agent-list empty state, drifting only in padding and text scale — those
 * differences are the knobs.
 */
@Composable
fun EmptyStateMessage(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 32.dp),
    itemSpacing: Dp = MaterialTheme.spacing.md,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    titleFontWeight: FontWeight = FontWeight.SemiBold,
    bodyStyle: TextStyle = MaterialTheme.typography.bodySmall,
    bodyColor: Color = MaterialTheme.colorScheme.outline,
    action: (@Composable () -> Unit)? = null,
    actionSpacingBefore: Dp = MaterialTheme.spacing.xs
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            icon()
            Text(
                text = title,
                style = titleStyle,
                fontWeight = titleFontWeight,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                style = bodyStyle,
                color = bodyColor,
                textAlign = TextAlign.Center
            )
            if (action != null) {
                // The spacer is itself a child of the spacedBy column, so a
                // non-zero value widens the gap by itemSpacing + this. Pass
                // 0.dp to keep the plain itemSpacing gap (no extra child).
                if (actionSpacingBefore > 0.dp) {
                    Spacer(modifier = Modifier.height(actionSpacingBefore))
                }
                action()
            }
        }
    }
}
