package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.ui.theme.Motion
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full-width status banner shown below the top bar during transient connection
 * states. Disconnected shows the same Reconnect banner as Error, since a
 * dropped/manually-disconnected session otherwise leaves the user with no way
 * back into chat. onStop avoids waiting out AgentConnection's backoff.
 * awaitingOnboardCode avoids a stuck-looking "Connecting…" for a state that
 * won't resolve on its own.
 *
 * @param connectionState Current connection state driving which banner shows
 * @param onReconnect Invoked when the user taps "Reconnect" in the failed or
 *   disconnected banner
 * @param onStop Invoked when the user taps "Stop" in the connecting banner
 * @param awaitingOnboardCode When true, shows "Waiting for verification code…"
 *   instead of the generic connecting copy
 * @param isOffline The device itself has no network, already debounced by
 *   `offlineSustainedFor` — outranks every [connectionState] banner below
 * @param modifier Optional modifier for the banner container
 */
@Composable
fun ConnectionBanner(
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onStop: (() -> Unit)? = null,
    awaitingOnboardCode: Boolean = false,
    isOffline: Boolean = false,
    modifier: Modifier = Modifier
) {
    // One AnimatedContent, not three sibling AnimatedVisibility: the states are
    // mutually exclusive, but as siblings two banners were both laid out at full
    // height during a swap, shoving the message list down ~40dp and back.
    // Android aborts a backgrounded app's sockets, so returning from a picker
    // or the camera routinely lands in Reconnecting for a second or two. That
    // is housekeeping, not news — announcing it makes a normal round trip look
    // like a fault. Held back briefly; a reconnect that outlives the delay is
    // one the user should hear about. A first connect is not debounced: there
    // the banner is the only thing saying anything is happening.
    var reconnectOutstayed by remember { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Reconnecting) {
            delay(RECONNECT_BANNER_DELAY_MS)
            reconnectOutstayed = true
        } else {
            reconnectOutstayed = false
        }
    }

    val kind = when {
        // First, and above even the connecting states. The socket only learns
        // it is dead when a ping goes unanswered — 15-30s during which
        // [connectionState] still says Connected and this banner said nothing
        // at all, which is the whole reason a device-level signal exists. Once
        // it fires, "Connecting to agent…" or "Connection failed" would also
        // be the wrong story: nothing is wrong with the agent, and no
        // Reconnect the user could tap would work until the radio is back.
        isOffline -> BannerKind.Offline
        connectionState is ConnectionState.Reconnecting && !reconnectOutstayed -> BannerKind.None
        connectionState.isConnecting() ->
            if (awaitingOnboardCode) BannerKind.AwaitingCode else BannerKind.Connecting
        connectionState.isError() -> BannerKind.Error
        connectionState.isDisconnected() -> BannerKind.Disconnected
        else -> BannerKind.None
    }

    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            // Fade-through, so two single-line labels never overprint each other
            // mid-swap; SizeTransform absorbs the leftover height difference.
            fadeIn(
                tween(
                    Motion.Short4,
                    delayMillis = Motion.Short2,
                    easing = Motion.EmphasizedDecelerate
                )
            ) togetherWith fadeOut(
                tween(Motion.Short2, easing = Motion.EmphasizedAccelerate)
            ) using SizeTransform(
                clip = false,
                // A bounded tween, not the default spring: the spring's tail
                // would keep nudging the message list after the banner is gone.
                sizeAnimationSpec = { _, _ -> tween(Motion.Medium2, easing = Motion.Emphasized) }
            )
        },
        label = "connectionBanner",
        modifier = modifier
    ) { target ->
        when (target) {
            BannerKind.None -> Unit
            BannerKind.Offline -> OfflineBanner()
            BannerKind.Connecting -> ConnectingBanner(onStop = onStop)
            BannerKind.AwaitingCode -> AwaitingOnboardCodeBanner()
            BannerKind.Error -> ReconnectBanner(title = "Connection failed", onReconnect = onReconnect)
            BannerKind.Disconnected -> ReconnectBanner(title = "Disconnected", onReconnect = onReconnect)
        }
    }
}

/** The single banner the current [ConnectionState] resolves to, if any. */
private enum class BannerKind { None, Offline, Connecting, AwaitingCode, Error, Disconnected }

/**
 * Shared chrome for every banner here: a single-line text row closed by a
 * hairline divider.
 *
 * These were filled amber/red blocks in `labelLarge` SemiBold — louder than the
 * agent's reply below them. Connection state is ambient; the divider does the
 * separating the fill used to.
 */
@Composable
private fun BannerRow(
    leading: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.lg,
                    end = MaterialTheme.spacing.sm,
                    top = MaterialTheme.spacing.xs2,
                    bottom = MaterialTheme.spacing.xs2
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
        ) {
            leading()
            content()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "Connecting to agent…" — an inline spinner and an optional "Stop" action. */
@Composable
private fun ConnectingBanner(onStop: (() -> Unit)? = null) {
    BannerRow(
        leading = {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                // Neutral, not amber: connecting is a transient state the user
                // did not ask about and cannot act on.
                color = MaterialTheme.colorScheme.outline
            )
        }
    ) {
        Text(
            text = "Connecting to agent…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        if (onStop != null) {
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                // ~40dp tap height, deliberately short of the 48dp guideline. This link
                // was already the tallest thing in BannerRow (24dp beat the 16dp icon/
                // title), so it alone dictates the row's height — any padding here grows
                // the "ambient" banner too. 40dp over the full 48dp keeps that growth to
                // ~16dp instead of ~24dp; going all the way to 48dp is what would turn
                // this into a normal-sized banner. Not an oversight — a chosen ceiling.
                modifier = Modifier
                    .clickable(onClick = onStop)
                    .padding(
                        horizontal = MaterialTheme.spacing.xs2,
                        vertical = MaterialTheme.spacing.md
                    )
            )
        }
    }
}

/**
 * "No internet connection" — the device's own radio is down.
 *
 * No action, unlike [ReconnectBanner]: a Reconnect the user can tap but that
 * cannot succeed is worse than no button. Recovery is automatic — the network
 * returning both clears this and nudges the pending reconnect (see
 * ChatViewModel's `networkMonitor` collector). Carries the error colour for
 * the same reason [ReconnectBanner] does: unlike "Connecting…", this is a
 * fault, and the user's next message will not be delivered until it clears.
 */
@Composable
private fun OfflineBanner() {
    val error = MaterialTheme.colorScheme.error
    BannerRow(
        leading = {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(16.dp)
            )
        }
    ) {
        Text(
            text = "No internet connection",
            style = MaterialTheme.typography.labelSmall,
            color = error,
            modifier = Modifier.weight(1f)
        )
    }
}

/** "Waiting for invite code…" — no spinner, since we already know the outcome. */
@Composable
private fun AwaitingOnboardCodeBanner() {
    BannerRow(leading = {}) {
        Text(
            text = "Waiting for invite code…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * Banner with a "Reconnect" action, shared by the [ConnectionState.Error] and
 * [ConnectionState.Disconnected] cases — same treatment, different [title].
 *
 * Keeps the error colour where [ConnectingBanner] drops its amber: this is a
 * failure the user has to act on. Carried by the icon and text, not a fill.
 */
@Composable
private fun ReconnectBanner(title: String, onReconnect: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    BannerRow(
        leading = {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(16.dp)
            )
        }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = error,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Reconnect",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            // Same ~40dp compromise as the Stop link in ConnectingBanner above.
            modifier = Modifier
                .clickable(onClick = onReconnect)
                .padding(horizontal = MaterialTheme.spacing.xs2, vertical = MaterialTheme.spacing.md)
        )
    }
}

/** Long enough to cover a routine background-drop recovery, short enough that a real stall still surfaces. */
private const val RECONNECT_BANNER_DELAY_MS = 1500L
