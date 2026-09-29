package ai.openonion.oochat.ui.theme

import ai.openonion.oochat.domain.model.ThemeMode
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds the current [ThemeMode] and a setter, exposed via [LocalThemeController]
 * so any composable (drawer toggle, settings) can read and change the theme
 * without prop-drilling through the whole tree.
 *
 * Provided at the app root (MainActivity), backed by persisted preferences.
 *
 * @param mode Currently active theme mode
 * @param setMode Persist a new theme mode; triggers recomposition + live switch
 */
data class ThemeController(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val setMode: (ThemeMode) -> Unit = {}
)

/**
 * Access the app-wide theme controller. Defaults to a no-op controller so
 * previews and tests render without an explicit provider.
 */
val LocalThemeController = staticCompositionLocalOf { ThemeController() }
