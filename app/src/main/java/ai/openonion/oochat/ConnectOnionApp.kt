package ai.openonion.oochat

import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.ui.navigation.NavigationGraph
import ai.openonion.oochat.ui.navigation.Routes
import ai.openonion.oochat.ui.theme.Motion
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.runCatchingCancellable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimum time the branded splash stays up. Covers the bulk of the wordmark
 * entrance without paying for all of it — the crossfade out overlaps the tail.
 * It used to be 700ms, the first of three serial holds that put well over a
 * second of chrome in front of a connection that itself takes ~200ms.
 */
private const val MIN_SPLASH_MS = 450L

/**
 * Main application composable with navigation.
 *
 * Determines the initial screen based on configuration state:
 * - No config → OnboardingScreen
 * - Has config → LoadingScreen (auto-reconnect)
 *
 * While the start destination is being resolved (async disk read), shows
 * a branded splash screen so the user never sees a blank window. The splash
 * crossfades into the app once the destination is known.
 */
@Composable
fun ConnectOnionApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    var startDestination by remember { mutableStateOf<String?>(null) }

    // Determine start destination based on configuration. Hold the splash for at
    // least MIN_SPLASH_MS (run in parallel with the disk read) so the entrance
    // animation plays smoothly instead of being cut off by a fast config read.
    //
    // Cold-start routing:
    //  - No config           → onboarding/setup screen.
    //  - Has saved config   → LoadingScreen. The previous detour through
    //                         ChatScreen (with isConnected = false) showed
    //                         a degraded empty state — a "Connect to an
    //                         agent first" placeholder in the input —
    //                         before ChatViewModel finished its connect.
    //                         LoadingScreen has the halo, progress ring,
    //                         and gentle fade-in animation specifically
    //                         designed for this in-progress beat. It then
    //                         auto-navigates to ChatScreen on connect
    //                         success (no "Open Chat" tap) and routes via
    //                         the onboardPending flag on the gate card.
    //                         Only Onboarding "Connect" and Recovery
    //                         "Try again" still go through LoadingScreen
    //                         explicitly.
    LaunchedEffect(Unit) {
        val minVisible = launch { delay(MIN_SPLASH_MS) }
        val container = context.appContainer

        // The splash hold is otherwise dead time on the IO threads. Everything
        // on the container is a `by lazy` singleton, so building these here
        // makes every later main-thread access a plain cache hit — without
        // touching a single call site. keyManager is the expensive one: its
        // first touch builds an EncryptedSharedPreferences (AndroidKeyStore +
        // Tink) and, on a fresh install, runs BIP39 PBKDF2 to mint an identity.
        // Deliberately not joined — a slow warm-up must never delay the first
        // screen, and a failure here is harmless because the real call site
        // hits the same code path and handles its own errors.
        launch(Dispatchers.IO) {
            runCatchingCancellable {
                container.agentRepository
                container.connectToAgentUseCase
                container.keyManager.loadOrGenerate()
            }
        }

        // The container's singleton, resolved on IO — not a second repository
        // in a remember{}. That one built its own EncryptedSharedPreferences
        // (AndroidKeyStore + Tink, ~140ms measured) during the initial
        // composition, i.e. before the first frame, and gave the same prefs
        // file a second _configFlow that no writer ever updated.
        val hasConfig = withContext(Dispatchers.IO) {
            container.configRepository.hasConfig()
        }
        minVisible.join()
        startDestination = if (hasConfig) Routes.LOADING else Routes.ONBOARDING
    }

    // Crossfade from splash → app so there's no hard cut once the destination resolves.
    Crossfade(
        targetState = startDestination,
        animationSpec = tween(durationMillis = Motion.Medium1),
        label = "splashToApp"
    ) { destination ->
        if (destination != null) {
            NavigationGraph(
                navController = navController,
                startDestination = destination,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SplashScreen()
        }
    }
}

/**
 * Branded splash shown during async startup. Full-bleed brand green (matches the
 * system cold-start splash for a seamless handoff), with the wordmark fading in and
 * rising slightly, and a three-dot pulse in place of a spinner. Plain Compose — no
 * extra dependency, no logo.
 */
@Composable
private fun SplashScreen() {
    // Single driver for the entrance: 0 → 1 over 500ms. Fades the wordmark in and
    // lifts it up ~12dp.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
            }
        ) {
            Text(
                text = "ConnectOnion",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
            PulsingDots(color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * Three dots pulsing in a staggered wave — a subtler, more branded loading cue than
 * a CircularProgressIndicator.
 */
@Composable
private fun PulsingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulsingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    // Phase-shift each dot so the pulse travels left→right.
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dotAlpha$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
