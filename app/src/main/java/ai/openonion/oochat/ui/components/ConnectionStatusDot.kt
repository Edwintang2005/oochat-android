package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.theme.statusColors
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Small color-coded connection dot — colour and pulse only, no label. Lives
 * in [ai.openonion.oochat.ui.chat.components.ChatTopBar]'s fixed
 * 64dp title row, which has no room left for a status line once the row
 * carries the title too; `ConnectionBanner` just below the bar already
 * spells out connecting/reconnecting/error in words. [contentDescription]
 * still carries the state for TalkBack, since nothing visible on that row
 * says "Connected" any more.
 *
 * Was `ConnectionStatusIndicator` (dot + text) — split apart because its one
 * caller no longer has anywhere to put the text.
 *
 * @param awaitingOnboardCode When true, the underlying socket is live and the
 *   agent has already responded — it's just gated on an invite code — so this
 *   reads as connected (green, no pulse) rather than a literal "Connecting…",
 *   which would otherwise look stuck for a state that never resolves to
 *   [ConnectionState.Connected] on its own (see [ConnectionBanner]'s
 *   `awaitingOnboardCode` for the same fix applied there).
 */
@Composable
fun ConnectionStatusDot(
    connectionState: ConnectionState,
    awaitingOnboardCode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when {
            awaitingOnboardCode && connectionState.isConnecting() -> MaterialTheme.statusColors.successDot
            connectionState is ConnectionState.Connected -> MaterialTheme.statusColors.successDot
            connectionState is ConnectionState.Connecting -> MaterialTheme.statusColors.warningDot
            connectionState is ConnectionState.Reconnecting -> MaterialTheme.statusColors.warningDot
            connectionState is ConnectionState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(durationMillis = 300),
        label = "connectionDotColor"
    )

    // Pulse the dot's opacity while a connection attempt is in flight —
    // except while awaiting an onboard code, where the connection is
    // already live and a pulsing dot would misread as still-connecting.
    val pulse = rememberInfiniteTransition(label = "connectionPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connectionPulseAlpha"
    )
    val dotAlpha = if (connectionState.isConnecting() && !awaitingOnboardCode) pulseAlpha else 1f

    val statusLabel = if (awaitingOnboardCode && connectionState.isConnecting()) {
        "Connected · Invite code needed"
    } else {
        when (connectionState) {
            is ConnectionState.Idle -> "Idle"
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            is ConnectionState.Error -> "Connection failed"
            is ConnectionState.Disconnected -> "Disconnected"
        }
    }

    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { alpha = dotAlpha }
            .clip(CircleShape)
            .background(dotColor)
            .semantics { contentDescription = statusLabel }
    )
}
