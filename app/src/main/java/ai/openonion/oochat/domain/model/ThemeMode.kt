package ai.openonion.oochat.domain.model

/**
 * User-selectable theme mode. Single source of truth for theme selection across
 * the UI (drawer toggle, settings) and the persistence layer.
 *
 * - [LIGHT] / [DARK]: force the corresponding palette regardless of the OS setting.
 * - [SYSTEM]: follow the device's dark-mode setting (`isSystemInDarkTheme()`).
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}
