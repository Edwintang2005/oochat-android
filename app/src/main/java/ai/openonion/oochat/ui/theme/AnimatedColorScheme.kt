package ai.openonion.oochat.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Returns a copy of this [ColorScheme] where every color role animates toward
 * its target value instead of snapping, matching the Figma design's theme
 * transition (`themeSwitch` fade, ~250ms). Used by [ConnectOnionTheme] so
 * toggling Light/Dark/System — from the drawer or Settings — cross-fades the
 * whole UI instead of hard-cutting.
 *
 * `ColorScheme.copy()` is the pattern Google's own Material3 samples use for
 * this: read each role through [animateColorAsState] and rebuild the scheme
 * from the animated values every recomposition.
 */
@Composable
fun ColorScheme.animated(durationMillis: Int = 250): ColorScheme {
    val spec = tween<Color>(durationMillis = durationMillis)
    @Composable
    fun animate(target: Color, label: String) = animateColorAsState(target, spec, label = label).value

    return copy(
        primary = animate(primary, "primary"),
        onPrimary = animate(onPrimary, "onPrimary"),
        primaryContainer = animate(primaryContainer, "primaryContainer"),
        onPrimaryContainer = animate(onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = animate(inversePrimary, "inversePrimary"),
        secondary = animate(secondary, "secondary"),
        onSecondary = animate(onSecondary, "onSecondary"),
        secondaryContainer = animate(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = animate(onSecondaryContainer, "onSecondaryContainer"),
        tertiary = animate(tertiary, "tertiary"),
        onTertiary = animate(onTertiary, "onTertiary"),
        tertiaryContainer = animate(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = animate(onTertiaryContainer, "onTertiaryContainer"),
        background = animate(background, "background"),
        onBackground = animate(onBackground, "onBackground"),
        surface = animate(surface, "surface"),
        onSurface = animate(onSurface, "onSurface"),
        surfaceVariant = animate(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = animate(onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = animate(surfaceTint, "surfaceTint"),
        inverseSurface = animate(inverseSurface, "inverseSurface"),
        inverseOnSurface = animate(inverseOnSurface, "inverseOnSurface"),
        error = animate(error, "error"),
        onError = animate(onError, "onError"),
        errorContainer = animate(errorContainer, "errorContainer"),
        onErrorContainer = animate(onErrorContainer, "onErrorContainer"),
        outline = animate(outline, "outline"),
        outlineVariant = animate(outlineVariant, "outlineVariant"),
        scrim = animate(scrim, "scrim"),
        surfaceBright = animate(surfaceBright, "surfaceBright"),
        surfaceDim = animate(surfaceDim, "surfaceDim"),
        surfaceContainer = animate(surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = animate(surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = animate(surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = animate(surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = animate(surfaceContainerLowest, "surfaceContainerLowest"),
        // The twelve *Fixed roles landed after 1.2.1. Named arguments meant
        // leaving them out still compiled — they just passed through
        // un-animated, so a theme switch would have snapped them while every
        // other role crossfaded.
        primaryFixed = animate(primaryFixed, "primaryFixed"),
        primaryFixedDim = animate(primaryFixedDim, "primaryFixedDim"),
        onPrimaryFixed = animate(onPrimaryFixed, "onPrimaryFixed"),
        onPrimaryFixedVariant = animate(onPrimaryFixedVariant, "onPrimaryFixedVariant"),
        secondaryFixed = animate(secondaryFixed, "secondaryFixed"),
        secondaryFixedDim = animate(secondaryFixedDim, "secondaryFixedDim"),
        onSecondaryFixed = animate(onSecondaryFixed, "onSecondaryFixed"),
        onSecondaryFixedVariant = animate(onSecondaryFixedVariant, "onSecondaryFixedVariant"),
        tertiaryFixed = animate(tertiaryFixed, "tertiaryFixed"),
        tertiaryFixedDim = animate(tertiaryFixedDim, "tertiaryFixedDim"),
        onTertiaryFixed = animate(onTertiaryFixed, "onTertiaryFixed"),
        onTertiaryFixedVariant = animate(onTertiaryFixedVariant, "onTertiaryFixedVariant"),
    )
}
