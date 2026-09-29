package ai.openonion.oochat.ui.common

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for the app's 4 font-size levels, keyed by the
 * `fontSizeIndex` (0..3) persisted via
 * [ai.openonion.oochat.data.local.AppSettings].
 *
 * Previously duplicated as three parallel arrays kept in sync by hand: the
 * chat screen's `fontScale` lookup, [ai.openonion.oochat.ui.settings.components.FontSizeSheet]'s
 * label list, and its preview-size list. [scale] matches Figma's
 * `FONT_SIZE_SCALE` and composes with the system Accessibility font scale in
 * [ai.openonion.oochat.ui.theme.ConnectOnionTheme]; [previewSize] matches
 * Figma's `FONT_SIZE_PX = [13, 15, 17, 20]` used by the size-picker sheet's
 * live preview (intentionally *not* multiplied by [scale] there — see
 * FontSizeSheet's own doc).
 */
enum class FontSizeOption(val label: String, val scale: Float, val previewSize: TextUnit) {
    SMALL("Small", 0.8f, 13.sp),
    MEDIUM("Medium", 1.0f, 15.sp),
    LARGE("Large", 1.2f, 17.sp),
    EXTRA_LARGE("Extra Large", 1.4f, 20.sp);

    companion object {
        /** Resolves a persisted `fontSizeIndex`, coercing out-of-range values into `entries`' bounds. */
        fun fromIndex(index: Int): FontSizeOption = entries[index.coerceIn(0, entries.lastIndex)]
    }
}
