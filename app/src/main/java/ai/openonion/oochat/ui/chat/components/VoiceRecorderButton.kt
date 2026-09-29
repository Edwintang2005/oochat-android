package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.InputBarToken
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.tabularFigures
import ai.openonion.oochat.util.formatVoiceDuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The composer's dictation starter, alongside Send rather than in place of it.
 * One label, one job: finishing a dictation belongs to the waveform in
 * [VoiceRecordingRow], so this slot never swaps into a second control
 * mid-recording — it just goes inert until the dictation ends.
 */
@Composable
internal fun MicButton(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
    }
    // No explicit size: default 48dp touch target, matching Send/Stop's
    // now-48dp footprint (both wrap a 40dp visual inside an IconButton too).
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Dictate a message",
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Timer, level meter and a way out, shown above the field while dictating.
 *
 * The meter is the finish control — tapping the thing that is visibly reacting
 * to your voice ends the dictation. Cancel keeps its own ✕ because discarding
 * is the opposite act, not a variant of finishing. It carries no Send: what the
 * recognizer hears goes into the field, and sending is the field's own job.
 *
 * Only [VoiceInputPhase.LISTENING] gets the timer and the meter. The two phases
 * with no open microphone — waiting for one, and waiting for a transcript —
 * share the quiet ring and a label saying which.
 */
@Composable
internal fun VoiceRecordingRow(
    elapsedSeconds: Float,
    onCancel: () -> Unit,
    onFinish: () -> Unit = {},
    phase: VoiceInputPhase = VoiceInputPhase.LISTENING,
    onQueryVoiceAmplitude: () -> Float = { 0f }
) {
    // Neither of these is a recording, so neither may look like one.
    val waitingLabel = when (phase) {
        VoiceInputPhase.PREPARING -> "Preparing…"
        VoiceInputPhase.TRANSCRIBING -> "Transcribing…"
        else -> null
    }
    // The same two rings the pill below uses, on the same rule: full weight
    // while live, quieter at rest. A live recording earns the focused ring;
    // a row waiting on a microphone or a POST does not, and holding it at
    // 1.5dp there reads as "still recording".
    val ring = if (waitingLabel != null) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        BorderStroke(InputBarToken.BorderWidth, MaterialTheme.colorScheme.error)
    }
    // Cancel → Timer → Waveform → pulse indicator. Padding matched to
    // TextInputField's pill, outer and inner both, so the two stacked boxes are
    // the same width in the same gutter and the ✕ lands directly above the
    // pill's attach button.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.spacing.xs)
            .clip(RoundedCornerShape(InputBarToken.BorderRadius))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(ring, RoundedCornerShape(InputBarToken.BorderRadius))
            .padding(MaterialTheme.spacing.xs2)
    ) {
        // No explicit size: default 48dp touch target.
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel dictation",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }

        if (waitingLabel != null) {
            // Nothing to meter: no microphone is open in either of these — one
            // is not yet, the other is already closed with a POST in flight.
            Text(
                text = waitingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs)
            )
            return@Row
        }

        Text(
            text = formatVoiceDuration(elapsedSeconds),
            style = MaterialTheme.typography.labelMedium.tabularFigures(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs)
        )

        // Bars drawn 28dp tall, tapped across the row's full 48dp. The tinted
        // track and the trailing ✓ are the affordance — a bare level meter
        // gives a sighted user nothing that says "press me".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(InputBarToken.BorderRadius))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                .clickable(role = Role.Button, onClick = onFinish)
                .semantics { contentDescription = "Finish dictation" }
                .padding(horizontal = 10.dp)
        ) {
            LiveWaveform(
                onQueryVoiceAmplitude = onQueryVoiceAmplitude,
                modifier = Modifier.weight(1f).height(28.dp)
            )
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp)
            )
        }

        // Recording indicator: a static dot with a separate ring expanding
        // outward and fading, looping — not a single dot breathing alpha.
        val pulseTransition = rememberInfiniteTransition(label = "recPulse")
        val ringScale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "recRingScale"
        )
        val ringAlpha by pulseTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "recRingAlpha"
        )
        Box(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.spacing.xs)
                .size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

/** Why a dictation produced nothing, in the composer where the mic is. */
@Composable
internal fun VoiceInputError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

/**
 * A working dictation the user should still know something about. Caption to
 * [VoiceRecordingRow], so it belongs above that row — between the two boxes it
 * read as an unrelated line splitting the composer.
 */
@Composable
internal fun VoiceInputNotice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

/**
 * Bars whose heights track the real microphone input level on a sliding
 * window (oldest value shifted out, one new value pushed in). Polls
 * [onQueryVoiceAmplitude] rather than animating a fake signal, so a silent
 * or failed mic capture shows as a flat line instead of misleadingly
 * implying audio is being recorded. Each bar's opacity scales with its own
 * height, matching Figma's dimmer-for-quieter-values treatment.
 */
@Composable
private fun LiveWaveform(onQueryVoiceAmplitude: () -> Float = { 0f }, modifier: Modifier = Modifier) {
    var heights by remember { mutableStateOf(List(28) { 0.05f }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            val level = onQueryVoiceAmplitude().coerceIn(0f, 1f)
            heights = (heights.drop(1) + (0.05f + level * 0.95f))
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        heights.forEach { h ->
            val animatedHeight by animateFloatAsState(h, label = "waveformBar")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.4f + h * 0.6f))
            )
        }
    }
}
