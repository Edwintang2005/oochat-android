package ai.openonion.oochat.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Central motion scale, the missing sibling of [AppShapes] / [SpacingTokens].
 *
 * A plain `object`, not a CompositionLocal: durations and easings are physical
 * constants of the UI, not theme-dependent values, so there is nothing for a
 * light/dark or brand swap to override.
 *
 * Only the steps this app actually uses are here. `MaterialTheme.motionScheme`
 * would supply the full M3 set but needs material3 1.4.0+; we are on 1.2.0.
 */
object Motion {
    /** Icon/indicator state flips — short enough to read as instant. */
    const val Short2 = 100

    /** Fade-out leg of a fade-through, small in-place reveals. */
    const val Short3 = 150

    /** Fade-in leg of a fade-through, menu and banner enters. */
    const val Short4 = 200

    /** Full-screen crossfades where nothing moves. */
    const val Medium1 = 250

    /** Full-screen transitions that also travel (nav slides). */
    const val Medium2 = 300

    /**
     * M3 emphasized easing — the default for anything entering and leaving in
     * one gesture. `AttachmentMenu.kt` already uses this curve inline; that
     * literal is where the value is confirmed against the spec.
     */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For content arriving: fast start, long settle. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** For content leaving: gentle start, quick exit off-screen. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
