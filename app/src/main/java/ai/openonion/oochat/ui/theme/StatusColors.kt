package ai.openonion.oochat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Shared warning/success tint scale for status-style chat cards (tool
 * blocked, eval pass/fail, compaction, etc). Mirrors [SpacingTokens] /
 * [LocalSpacing]'s composition-local pattern.
 *
 * The defaults are the dark-theme set; [DarkStatusColors] / [LightStatusColors]
 * name the two instances the theme actually provides. `warning` matches
 * [ai.openonion.oochat.ui.components.ConnectionBanner]'s existing amber
 * (`#CA8A04`) so the two don't visually diverge.
 *
 * Access from composables via `MaterialTheme.statusColors.warning` etc.
 */
@Immutable
data class StatusColors(
    val warning: Color = Color(0xFFCA8A04),
    val warningContainer: Color = Color(0xFFFACC15).copy(alpha = 0.12f),
    val success: Color = Color(0xFF16A34A),
    val successContainer: Color = Color(0xFF22C55E).copy(alpha = 0.12f),
    // Brighter dot variants for the tiny connection-status indicator, where
    // the text-weight warning/success colors read too dark at 8dp.
    val successDot: Color = Color(0xFF4ADE80),
    val warningDot: Color = Color(0xFFFACC15),
    // The logs console background. Same value in both instances below — it's
    // a monospace panel meant to stay dark regardless of app theme, tuned to
    // surfaceContainerLowestDark rather than a neutral black so it reads as
    // this app's console, not a foreign slab.
    val logSurface: Color = surfaceContainerLowestDark
)

/** The original Figma values; 4.9–12.1:1 against the dark surfaces. */
val DarkStatusColors = StatusColors()

/**
 * The same roles re-toned for the light surfaces, where the dark-tuned values
 * collapsed (successDot measured 1.65:1, warningDot 1.45:1). The text roles
 * clear 4.5:1 both on `surface` and on their own container, and the dots clear
 * WCAG 1.4.11's 3.0:1. Container alpha is above dark's 0.12 because a bright
 * tint over a near-white surface barely registers at that strength (1.05:1
 * against surface, now 1.23:1), and for the same reason the tint base is the
 * darker text amber/green rather than the dot colors.
 */
val LightStatusColors = StatusColors(
    warning = Color(0xFF7A5200),
    warningContainer = Color(0xFFCA8A04).copy(alpha = 0.22f),
    success = Color(0xFF0C6A2F),
    successContainer = Color(0xFF16A34A).copy(alpha = 0.22f),
    successDot = Color(0xFF157F3C),
    warningDot = Color(0xFFA06E00)
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

/** Convenience accessor: `MaterialTheme.statusColors.warning`. */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current
