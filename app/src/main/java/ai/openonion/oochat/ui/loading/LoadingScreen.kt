package ai.openonion.oochat.ui.loading

import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.Motion
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** How long the success checkmark is held before handing off to the chat route. */
private const val SUCCESS_HOLD_MS = 250L

/** Staggers the CTA behind the halo and body copy so the three land in order. */
private const val CTA_DELAY_MS = 180

/**
 * Loading screen shown during automatic reconnection attempts.
 *
 * Purely observational — all connection logic, timing, and retry state
 * lives in [LoadingViewModel]; this Composable only renders [LoadingUiState]
 * and reacts to [LoadingEvent]s for navigation.
 *
 * Displays app branding and connection progress: a determinate ring +
 * 5-dot step indicator while connecting, and a checkmark success state
 * with an explicit "Open Chat" confirmation (rather than auto-navigating)
 * once connected — matching the Figma design's completed-state beat.
 *
 * The ONBOARD_REQUIRED outcome gets a gentler treatment: the VpnKey
 * icon springs in with a soft tertiary halo that breathes, the explanation
 * line and "Continue to Onboarding" button fade + slide in over a few
 * hundred ms so the screen "settles" into the new state instead of
 * snapping. The in-progress and connected outcomes use the existing
 * instant render — animations here only affect the onboarding-required
 * state because that is the path users actually have to act on.
 *
 * @param onConnectionSuccess Called when the user taps "Open Chat"/"Continue to
 * Onboarding" after the connection attempt resolves. `onboardPending` is true
 * when the outcome was [LoadingOutcome.ONBOARD_REQUIRED] rather than a plain
 * connect — lets the destination screen skip re-announcing a "connecting"
 * state it already knows the answer to.
 * @param onConnectionFailed Called when connection fails (with error message)
 * @param onCancel Called when user cancels the connection attempt
 */
@Composable
fun LoadingScreen(
    onConnectionSuccess: (onboardPending: Boolean) -> Unit,
    onConnectionFailed: (String) -> Unit,
    onCancel: () -> Unit = {},
    viewModel: LoadingViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.events.collect { event ->
            when (event) {
                is LoadingEvent.Failed -> onConnectionFailed(event.message)
                LoadingEvent.Cancelled -> onCancel()
                LoadingEvent.ConnectSucceeded -> {
                    // Brief hold so the checkmark registers before the route
                    // swap — without it the success state would flash by.
                    // Trimmed from 400ms: the fade-through out of this route
                    // reads as part of the same beat, so the hold no longer
                    // has to carry it alone.
                    kotlinx.coroutines.delay(SUCCESS_HOLD_MS)
                    onConnectionSuccess(false)
                }
            }
        }
    }

    LoadingContent(
        uiState = uiState,
        onCancel = viewModel::cancel,
        onContinueToOnboarding = { onConnectionSuccess(true) }
    )
}

/**
 * The screen's whole visual surface, with no ViewModel of its own — the long
 * wait is only reachable by outwaiting a connection, and this is what lets a
 * baseline capture it directly.
 */
@Composable
internal fun LoadingContent(
    uiState: LoadingUiState,
    onCancel: () -> Unit,
    onContinueToOnboarding: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // No Scaffold here, so the screen takes its own insets now that the
            // app draws edge-to-edge; the background above still fills the whole
            // window behind the transparent bars.
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xl),
            modifier = Modifier.padding(MaterialTheme.spacing.xxl)
        ) {
            // Auxiliary tier. Was a 32sp wordmark + 16sp tagline — the two
            // biggest things on screen, and the two carrying no information.
            Text(
                text = "ConnectOnion",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxl))

            val outcome = uiState.outcome
            val accentColor = when (outcome) {
                LoadingOutcome.ONBOARD_REQUIRED -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }

            // The three outcome glyphs differ in size (48dp ring/checkmark vs
            // the 96dp halo), so a bare `when` snapped everything below them up
            // or down. SizeTransform animates that gap; the scale+fade makes
            // "connected" — the one moment here with any emotional payload —
            // land softly.
            AnimatedContent(
                targetState = outcome,
                transitionSpec = {
                    (
                        fadeIn(tween(Motion.Short4, easing = Motion.EmphasizedDecelerate)) +
                            scaleIn(
                                animationSpec = tween(Motion.Medium2, easing = Motion.Emphasized),
                                initialScale = 0.7f
                            )
                        ) togetherWith fadeOut(
                        tween(Motion.Short2, easing = Motion.EmphasizedAccelerate)
                    ) using SizeTransform(clip = false)
                },
                contentAlignment = Alignment.Center,
                label = "loadingOutcomeIcon"
            ) { target ->
                when (target) {
                    LoadingOutcome.CONNECTED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    LoadingOutcome.ONBOARD_REQUIRED -> {
                        GentleHaloIcon(
                            icon = Icons.Default.VpnKey,
                            tint = accentColor
                        )
                    }
                    LoadingOutcome.IN_PROGRESS -> {
                        DeterminateProgressRing(progress = uiState.progress)
                    }
                }
            }

            // Status message — turns accent-colored + bold once finished
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (outcome != LoadingOutcome.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal,
                color = if (outcome != LoadingOutcome.IN_PROGRESS) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // The one honest thing left to say while the bar sits at its last
            // known milestone: how long this has been going. It reports the
            // wait, not progress, so it cannot mislead the way a moving bar
            // with nothing behind it would.
            uiState.waitingSeconds?.let { seconds ->
                Text(
                    text = "Still waiting — ${seconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }

            // Explanation line — only shown for the onboarding-required outcome,
            // and it fades + slides down gently into place rather than appearing
            // instantly with the rest of the Column.
            AnimatedVisibility(
                visible = outcome == LoadingOutcome.ONBOARD_REQUIRED,
                enter = fadeIn(tween(Motion.Medium2, easing = Motion.EmphasizedDecelerate)) +
                    slideInVertically(tween(Motion.Medium2, easing = Motion.EmphasizedDecelerate)) { it / 6 },
                exit = fadeOut(tween(Motion.Short4, easing = Motion.EmphasizedAccelerate))
            ) {
                Text(
                    text = "This agent requires an invite code or payment before you can chat. You'll be redirected to complete onboarding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            StepDots(progress = uiState.progress, accentColor = accentColor)

            // Progress bar
            LinearProgressIndicator(
                // material3 1.4 gave LinearProgressIndicator a stop indicator — a
                // dot parked at 100% — and a gap between the filled part and the
                // track. On a determinate bar that already ends at the track end,
                // the dot reads as a second, stalled progress marker. Both off.
                progress = { uiState.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

            when (outcome) {
                LoadingOutcome.CONNECTED -> {
                    // Auto-navigated by LaunchedEffect above — no button
                    // needed. The body stays on screen for SUCCESS_HOLD_MS so
                    // the user sees the "Connected!" state before the route
                    // swap hands off to ChatScreen.
                }
                LoadingOutcome.ONBOARD_REQUIRED -> {
                    // CTA fades + slides up a touch as the rest of the screen
                    // settles — the whole sequence (halo, body, cta) reads as a
                    // soft "ok, here's what's next" instead of a hard cut.
                    // MutableTransitionState, not `visible = true`: the boolean
                    // overload starts with current == target and so never
                    // animates on first composition, which is the only time this
                    // branch is ever entered. The enter spec was dead code.
                    val ctaState = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = ctaState,
                        enter = fadeIn(
                            tween(Motion.Medium2, delayMillis = CTA_DELAY_MS, easing = Motion.EmphasizedDecelerate)
                        ) + slideInVertically(
                            tween(Motion.Medium2, delayMillis = CTA_DELAY_MS, easing = Motion.EmphasizedDecelerate)
                        ) { it / 8 } + scaleIn(
                            animationSpec = tween(
                                Motion.Medium2,
                                delayMillis = CTA_DELAY_MS,
                                easing = Motion.EmphasizedDecelerate
                            ),
                            initialScale = 0.96f
                        )
                    ) {
                        Button(
                            onClick = onContinueToOnboarding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonToken.Compact),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Continue to Onboarding")
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                LoadingOutcome.IN_PROGRESS -> {
                    // Text, not outlined: an outline reads as an equal of the
                    // filled button. Cancel is the escape hatch, not an option.
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ButtonToken.Compact),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Icon with a soft breathing halo behind it. Used for the
 * ONBOARD_REQUIRED outcome so the screen "settles" into the onboarding
 * required state instead of snapping to it — the halo color is the
 * accent at low alpha, pulsing softly between two stops, while the icon
 * itself uses a spring entrance the first time it's composed.
 *
 * Halo runs forever (until [Composable] leaves the tree); the spring
 * only plays on first composition so re-entering the screen doesn't
 * re-animate the icon.
 */
@Composable
private fun GentleHaloIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    val transition = rememberInfiniteTransition(label = "gentleHalo")
    val haloAlpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    val haloScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )

    // Icon spring entrance — fires once per screen entry (no key) so
    // repeated recompositions on the same outcome don't replay it.
    var hasEntered by remember { mutableStateOf(false) }
    val iconScale by animateFloatAsState(
        targetValue = if (hasEntered) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "iconScale"
    )
    LaunchedEffect(Unit) { hasEntered = true }

    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        // Halo: a tinted circle drawn behind the icon. Scaled & alpha-pulsed
        // by the InfiniteTransition. Drawn as a Canvas (not just a tinted
        // Box) so it stays a true circle regardless of the icon's ink
        // bounds — a VpnKey vector's bounding box is wider than tall and a
        // plain Box would render an ellipse that reads as "off".
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (size.minDimension / 2f) * haloScale
            drawCircle(
                color = tint.copy(alpha = haloAlpha),
                radius = radius
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(48.dp)
                .scale(iconScale)
        )
    }
}

/** Custom determinate ring (Canvas-drawn), replacing the stock indeterminate spinner. */

/** Custom determinate ring (Canvas-drawn), replacing the stock indeterminate spinner. */
@Composable
private fun DeterminateProgressRing(progress: Float) {
    val animatedProgress by animateFloatAsState(progress, label = "loadingRingProgress")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val ringColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(48.dp)) {
        val strokeWidth = 4.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = androidx.compose.ui.geometry.Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * animatedProgress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/** 5 dots that fill in as [progress] advances, matching the Figma step indicator. */
@Composable
private fun StepDots(progress: Float, accentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    val filledCount = (progress * 5).toInt().coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        repeat(5) { index ->
            val filled = index < filledCount
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) accentColor
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}
