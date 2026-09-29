package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.domain.model.UserProfile
import ai.openonion.oochat.ui.settings.components.AccountSection
import ai.openonion.oochat.ui.settings.components.BackupSeedSheet
import ai.openonion.oochat.ui.settings.components.FontSizeSheet
import ai.openonion.oochat.ui.settings.components.SectionDivider
import ai.openonion.oochat.ui.settings.components.SectionFootnote
import ai.openonion.oochat.ui.settings.components.SettingsRow
import ai.openonion.oochat.ui.settings.components.SettingsSection
import ai.openonion.oochat.ui.settings.components.SwitchRow
import ai.openonion.oochat.ui.settings.components.ThemeModeRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings surfaces: the account block, the bare rows and their dividers, the
 * theme-mode toggle, both `Switch` states, and two of the app's three
 * `ModalBottomSheet` call sites.
 *
 * `SettingsScreen` itself is not captured — it pulls a `SettingsViewModel`
 * (and through it the whole app container: Room, DataStore, encrypted prefs)
 * and reads the installed package's version name. [SettingsBody] below
 * reassembles the same components in the same order from plain values, so
 * what a reviewer compares is the real row/divider/account chrome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    /**
     * The account block — the only container left on this screen by design,
     * and the one place the app paints a fixed dark gradient that ignores the
     * palette. Captured on its own because the full screen scrolls well past
     * one viewport, and a snapshot only ever sees the first one.
     */
    private fun captureAccount(palette: Palette) =
        composeRule.captureThemed("settings_account", palette) {
            Column(modifier = Modifier.fillMaxSize()) {
                AccountSection(
                    walletAddress = WALLET_ADDRESS,
                    apiKey = API_KEY,
                    onBackupSeed = {},
                    onImportKey = {},
                    onReset = {},
                )
            }
        }

    @Test
    fun `settings account - light`() = captureAccount(Palette.Light)

    @Test
    fun `settings account - dark`() = captureAccount(Palette.Dark)

    /** The bare rows, their dividers, both `Switch` states, and the footnote. */
    private fun captureRows(palette: Palette) =
        composeRule.captureThemed("settings_rows", palette) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSection("Appearance") {
                    ThemeModeRow(current = ThemeMode.SYSTEM, onChange = {})
                }

                // Both Switch states: the off state is where the track color
                // lives, and it is one of the roles flagged as moving in dark.
                SettingsSection("Responses") {
                    SwitchRow(
                        label = "Show live progress",
                        subtitle = "Thinking and tool steps shown as they happen",
                        checked = true,
                        onChange = {},
                    )
                    SectionDivider()
                    SwitchRow(
                        label = "Render Markdown",
                        subtitle = "Plain text only",
                        checked = false,
                        onChange = {},
                    )
                }

                SettingsSection("Interface") {
                    SettingsRow(label = "Font size", value = "Default", onClick = {})
                    SectionDivider()
                    SettingsRow(
                        label = "Custom instructions",
                        subtitle = "Set default behavior and tone",
                        onClick = {},
                    )
                }

                SettingsSection("Data & Privacy") {
                    SettingsRow(
                        icon = Icons.Default.Person,
                        label = "Agent List",
                        subtitle = "Manage your connected agents",
                        onClick = {},
                    )
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Default.Description,
                        label = "Logs",
                        subtitle = "View application logs",
                        onClick = {},
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Delete all conversations",
                        labelColor = MaterialTheme.colorScheme.error,
                        onClick = {},
                    )
                    SectionFootnote(
                        "Conversations and attached images are stored locally on this device. " +
                            "Voice recordings are never sent or saved.",
                    )
                }

                SettingsSection("About") {
                    SettingsRow(label = "Version", value = "0.1.0", showChevron = false, onClick = {})
                }
            }
        }

    @Test
    fun `settings rows - light`() = captureRows(Palette.Light)

    @Test
    fun `settings rows - dark`() = captureRows(Palette.Dark)

    /**
     * The un-revealed backup sheet: a `ModalBottomSheet` whose reveal gate is
     * an `OutlinedButton` with no explicit `colors`, so its label takes the
     * material3 default content color — a value the upgrade review flagged as
     * one that moves.
     */
    private fun captureBackupSheet(palette: Palette) =
        composeRule.captureThemed("settings_backup_seed_sheet", palette) {
            BackupSeedSheet(export = KeyManager.BackupExport.Phrase(MNEMONIC), onDismiss = {})
        }

    @Test
    fun `backup seed sheet - light`() = captureBackupSheet(Palette.Light)

    @Test
    fun `backup seed sheet - dark`() = captureBackupSheet(Palette.Dark)

    /** The `SettingsSheetScaffold` flavour of `ModalBottomSheet` — slider, card, actions. */
    private fun captureFontSizeSheet(palette: Palette) =
        composeRule.captureThemed("settings_font_size_sheet", palette) {
            FontSizeSheet(initialIndex = 1, onConfirm = {}, onDismiss = {})
        }

    @Test
    fun `font size sheet - light`() = captureFontSizeSheet(Palette.Light)

    @Test
    fun `font size sheet - dark`() = captureFontSizeSheet(Palette.Dark)

    private companion object {
        const val WALLET_ADDRESS = "0x8f2a4c7b1e9d3f6a5c8b2e4d7a1f9c3b6e8d2a4f"
        const val API_KEY = "oo_live_••••••••••••••••"
        const val MNEMONIC =
            "ripple canvas orbit meadow tunnel cactus velvet harbor " +
                "lantern prism quarry summit"

        val PROFILE = UserProfile(
            publicKey = WALLET_ADDRESS,
            creditsUsd = 25.00,
            totalCostUsd = 3.42,
            balanceUsd = 21.5800,
        )
    }
}
