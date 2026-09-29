package ai.openonion.oochat.ui.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The reading at which the ring turns `error`, safely below the server's 90% auto-compact. */
internal const val CTX_WARN_PERCENT = 80

/**
 * Below this the ring is not worth drawing — see [isCtxRingLegible] for how the
 * number was arrived at.
 */
internal const val CTX_RING_MIN_PERCENT = 10

/**
 * Whether a reading is large enough for the ring to say anything.
 *
 * Read off rendered frames at the ring's real 10dp/1.5dp size, not chosen: up
 * to ~5% the drawn arc is shorter than its own round cap, so 1% and 5% paint
 * an identical dot and the gauge reports presence rather than magnitude. The
 * mark only starts following the ring's curvature — the one thing a ring gauge
 * is for — around [CTX_RING_MIN_PERCENT], in both themes.
 */
internal fun isCtxRingLegible(percent: Int): Boolean = percent >= CTX_RING_MIN_PERCENT

/**
 * Context-window usage as a ring gauge — what replaced the "N% ctx" text in
 * the session strip, now the only place context is surfaced at all.
 *
 * Draws exactly the reading it is given; whether a reading is worth drawing is
 * the caller's question ([isCtxRingLegible]). With the number gone from the
 * screen, the arc turning `error` at [CTX_WARN_PERCENT] is the only warning
 * left before a compact; the percentage stays available to a screen reader.
 */
@Composable
fun CtxRing(
    percent: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 10.dp,
    strokeWidth: Dp = 1.5.dp
) {
    val clamped = percent.coerceIn(0, 100)
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val arcColor = ctxRingArcColor(clamped)
    val description = "Context: $clamped%"

    Canvas(
        modifier = modifier
            .size(diameter)
            .sitOnBaseline()
            .semantics { contentDescription = description }
    ) {
        val stroke = strokeWidth.toPx()
        // Inset by half the stroke so the ring's outer edge lands on `diameter`
        // rather than bleeding half a stroke past it.
        val inner = size.minDimension - stroke
        drawCircle(
            color = trackColor,
            radius = inner / 2f,
            style = Stroke(width = stroke)
        )
        if (clamped > 0) {
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * clamped / 100f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(inner, inner),
                // Round, so a single-digit reading still shows as a mark
                // instead of a hairline that disappears.
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * The arc's tint. Its own function because this is the whole warning now: with
 * the percentage off the screen, `error` at [CTX_WARN_PERCENT] is the only cue
 * a user gets before the conversation is compacted.
 */
@Composable
internal fun ctxRingArcColor(percent: Int): Color =
    if (percent >= CTX_WARN_PERCENT) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

/**
 * Publishes a baseline at the ring's bottom edge, so `Modifier.alignByBaseline()`
 * seats it on the text baseline beside it and the row reads as one line rather
 * than as text-plus-icon.
 */
private fun Modifier.sitOnBaseline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(FirstBaseline to placeable.height)) {
        placeable.place(0, 0)
    }
}
