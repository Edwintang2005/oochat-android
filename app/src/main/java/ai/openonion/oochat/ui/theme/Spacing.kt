package ai.openonion.oochat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Central spacing scale for paddings, gaps and spacers. Mirrors [AppShapes] /
 * [AppTypography] so the whole app's rhythm can be retuned from one file.
 *
 * Access from composables via `MaterialTheme.spacing.lg` etc. Values are calibrated
 * to the existing call sites and match Figma design specifications.
 *
 * Common usage patterns:
 * - none (0dp): No spacing, reset
 * - xxs (2dp): Minimal gap between tight elements
 * - xs (4dp): Small gaps (icon-to-text, tight list items)
 * - xs2 (6dp): Assistant avatar margin
 * - sm (8dp): Standard element gaps, padding inside compact components
 * - sm2 (10dp): Button/chip horizontal padding
 * - md (12dp): Card/surface padding, standard gaps
 * - md2 (14dp): Form input padding, medium gaps
 * - lg (16dp): Section padding, list item padding
 * - xl (24dp): Screen edge padding, section spacing
 * - xxl (32dp): Large section dividers
 * - xxxl (48dp): Major screen sections, dramatic spacing
 *
 * This covers spacing only — intrinsic component sizes (icon `size()`, button
 * heights, stroke widths) intentionally stay as literals for precision.
 */
@Immutable
data class SpacingTokens(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val xs2: Dp = 6.dp,
    val sm: Dp = 8.dp,
    val sm2: Dp = 10.dp,
    val md: Dp = 12.dp,
    val md2: Dp = 14.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { SpacingTokens() }

/** Convenience accessor: `MaterialTheme.spacing.lg`. */
val MaterialTheme.spacing: SpacingTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
