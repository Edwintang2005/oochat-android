package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.components.AssistantAvatar
import ai.openonion.oochat.ui.theme.bubbleShape
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * "Agent is typing" indicator: avatar + label + a pill bubble with 3 dots
 * bouncing on a staggered cycle, matching the Figma design's TypingIndicator
 * (dotBounce, 1.3s cycle, 0.18s stagger per dot). Label reads "Agent" rather
 * than Figma's "Aria" — this app keeps its own generic multi-agent branding.
 *
 * This is visually distinct from [ChatItem.Thinking]'s spinner+status-text
 * rendering elsewhere in ChatScreen (which carries real model/duration
 * metadata this simple boolean indicator doesn't have) — both are kept; this
 * one is for the lightweight "no content yet, just show activity" moment.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(start = MaterialTheme.spacing.lg, end = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Agent is typing"
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
    ) {
        Box(modifier = Modifier.padding(top = 20.dp)) { AssistantAvatar() }

        Column {
            Text(
                text = "Agent",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    // Same tail-corner shape as a real agent bubble, so the
                    // placeholder doesn't change silhouette when the reply lands.
                    .clip(bubbleShape(isUser = false))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { index ->
                    BounceDot(delayMillis = index * 180)
                }
            }
        }
    }
}

@Composable
private fun BounceDot(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "typingDot")
    val cycleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMillis)
        ),
        label = "typingDotOffset"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            // The phase is read inside the lambda so each frame only re-runs
            // the draw phase — this indicator lives in a LazyColumn item.
            .graphicsLayer {
                // 0->0.3: rise+fade in, 0.3->0.6: fall+fade out, 0.6->1: rest —
                // approximates the CSS dotBounce keyframe (up at 30%, back down
                // by 60%, idle to 100%).
                val progress = cycleProgress
                val bounce = when {
                    progress < 0.3f -> progress / 0.3f
                    progress < 0.6f -> 1f - (progress - 0.3f) / 0.3f
                    else -> 0f
                }
                translationY = -5.dp.toPx() * bounce
                alpha = 0.35f + 0.65f * bounce
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}
