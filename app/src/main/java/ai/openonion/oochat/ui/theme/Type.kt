package ai.openonion.oochat.ui.theme

import ai.openonion.oochat.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Manrope, bundled as a single variable font (res/font/manrope.ttf). Each weight is
 * derived from the variable axis via FontVariation, so no per-weight files are needed.
 * Works offline on any device (minSdk 26 supports variable fonts).
 */
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    resId = R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val ManropeFontFamily = FontFamily(
    manrope(400), // Regular
    manrope(500), // Medium
    manrope(600), // SemiBold
    manrope(700), // Bold
    manrope(800), // ExtraBold
)

// Start from Material 3 defaults, swapping only the font family so sizes/line-heights
// keep the tuned M3 type scale.
private val baseline = Typography()

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = ManropeFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = ManropeFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = ManropeFontFamily),
    headlineLarge = baseline.headlineLarge.copy(
        fontFamily = ManropeFontFamily,
        letterSpacing = (-0.4).sp // Tighter for large headings
    ),
    headlineMedium = baseline.headlineMedium.copy(
        fontFamily = ManropeFontFamily,
        letterSpacing = (-0.2).sp // Matches Figma spec
    ),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = ManropeFontFamily),
    titleLarge = baseline.titleLarge.copy(
        fontFamily = ManropeFontFamily,
        letterSpacing = (-0.2).sp // Tighter for prominent titles
    ),
    titleMedium = baseline.titleMedium.copy(fontFamily = ManropeFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = ManropeFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = ManropeFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = ManropeFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = ManropeFontFamily),
    labelLarge = baseline.labelLarge.copy(
        fontFamily = ManropeFontFamily,
        letterSpacing = 0.03.sp // Slightly expanded for chips/labels (matches Figma 0.03em)
    ),
    labelMedium = baseline.labelMedium.copy(fontFamily = ManropeFontFamily),
    labelSmall = baseline.labelSmall.copy(
        fontFamily = ManropeFontFamily,
        letterSpacing = 0.04.sp // Expanded for small caps/uppercase (matches Figma 0.04em)
    ),
)

/**
 * The de facto chat body style — message bubbles, markdown paragraphs, and
 * the input field all render at 15sp/22, a size that sits between M3's
 * bodyMedium (14) and bodyLarge (16) and previously existed only as
 * repeated fontSize/lineHeight literals at 8+ call sites. Tune the chat
 * font here, in one place.
 */
val Typography.chatBody: TextStyle
    get() = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)

/**
 * ALL-CAPS *badges* that sit inline with content and need to be noticed:
 * "SOON" and the discovery screen's "RELAY"/"DIRECT" connection-mode pill.
 *
 * Same 11sp as [Typography.labelSmall] — the extra tracking is what uppercase
 * needs to stay legible, nothing more. Group *headings* are a different job,
 * see [Typography.sectionLabel] — badges stay Bold because they *are* the
 * content at their position, headings are Medium because they aren't.
 */
val Typography.sectionOverline: TextStyle
    get() = labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

/**
 * ALL-CAPS group headings: "ACCOUNT", "BALANCE", "AGENTS", "UP NEXT", and
 * every SettingsSection title.
 *
 * Same 11sp and tracking as [Typography.sectionOverline] but Medium, not
 * Bold — see that doc for why the two are split.
 */
val Typography.sectionLabel: TextStyle
    get() = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

/*
 * ── The app's type ladder ────────────────────────────────────────────────
 *
 * One size per hierarchy level. When adding a Text, pick the level below and
 * use its role verbatim — don't pass a `fontSize` override, and don't reach
 * for a neighbouring role because a particular screen "looks better" a step
 * up or down. If a level genuinely doesn't exist here yet, add it here first.
 *
 *   headlineLarge  32  Unused. Reserved as the top of the ladder — nothing on
 *                      any screen currently outranks headlineMedium.
 *   headlineMedium 28  The splash wordmark and the account balance figure.
 *   titleLarge     22  Bottom-sheet titles; BackTopAppBar screen titles (M3
 *                      TopAppBar default). ChatTopBar is the one exception —
 *                      its title stacks over a status line, see its comment.
 *   titleMedium    16  Card titles in a list (agent cards), drawer wordmark
 *   titleSmall     14  Centred state titles (empty / error / success) and
 *                      warning-card headings
 *   bodyLarge      16  Settings/choice row labels; screen lead paragraph
 *   bodyMedium     14  Default body copy; sheet body; nav rows
 *   bodySmall      12  Secondary/supporting text under a row, field, or
 *                      button; monospace tool + log output
 *   labelLarge     14  Every button and pill-toggle label; chat card titles
 *   labelMedium    12  Field labels above inputs; status-pill text
 *   labelSmall     11  Captions: timestamps, mixed-case badges, status chips,
 *                      sender labels, slider ticks
 *   sectionOverline 11 ALL-CAPS badges — SOON, RELAY/DIRECT (see above)
 *   sectionLabel   11  ALL-CAPS group headings (see above)
 *   chatBody       15  Chat message text (see above)
 */

/**
 * Fixed-width digits. Manrope's default figures are proportional, so a value
 * that ticks — a recording timer, a playback position — changes width as it
 * counts and nudges everything beside it. `tnum` is present in the bundled
 * font, so this is a real substitution rather than a no-op.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
