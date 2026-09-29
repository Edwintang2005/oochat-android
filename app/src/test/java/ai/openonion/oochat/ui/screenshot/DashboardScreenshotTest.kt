package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.ui.dashboard.DashboardScreen
import ai.openonion.oochat.ui.dashboard.DashboardScreenContent
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent's Home page screen.
 *
 * Robolectric's `WebView` is a stub that paints nothing, so the loaded case
 * captures the chrome around it (top bar, insets, the frame the page gets)
 * rather than a rendered dashboard — the document itself is covered exactly
 * where it can be, in
 * [ai.openonion.oochat.ui.dashboard.DashboardDocumentTest]. The
 * no-snapshot state is fully rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun captureDashboard(palette: Palette) =
        composeRule.captureThemed("dashboard_screen", palette) {
            DashboardScreen(
                html = "<h1>research-assistant</h1><p>3 skills · 12 tools</p>",
                allowedSkills = listOf("web-scraping"),
                onRunSkill = {},
                onNavigateBack = {},
                agentName = "research-assistant",
            )
        }

    /**
     * A gate opened while the reader is over here. The bar sits above the page
     * rather than inside it, because the page is arbitrary HTML of any length.
     */
    private fun captureAwaiting(palette: Palette) =
        composeRule.captureThemed("dashboard_screen_awaiting", palette) {
            DashboardScreen(
                html = "<h1>research-assistant</h1><p>3 skills · 12 tools</p>",
                allowedSkills = listOf("web-scraping"),
                onRunSkill = {},
                onNavigateBack = {},
                agentName = "research-assistant",
                chatAwaitsUser = true,
            )
        }

    @Test
    fun `dashboard awaiting the user - light`() = captureAwaiting(Palette.Light)

    @Test
    fun `dashboard awaiting the user - dark`() = captureAwaiting(Palette.Dark)

    @Test
    fun `dashboard - light`() = captureDashboard(Palette.Light)

    @Test
    fun `dashboard - dark`() = captureDashboard(Palette.Dark)

    /** The window between a reconnect clearing the old snapshot and the new one landing. */
    private fun captureEmpty(palette: Palette) =
        composeRule.captureThemed("dashboard_screen_empty", palette) {
            DashboardScreen(
                html = null,
                allowedSkills = null,
                onRunSkill = {},
                onNavigateBack = {},
            )
        }

    @Test
    fun `dashboard with no snapshot - light`() = captureEmpty(Palette.Light)

    @Test
    fun `dashboard with no snapshot - dark`() = captureEmpty(Palette.Dark)

    /**
     * A link tap got intercepted. The page stays underneath a slim
     * informational bar instead of losing the whole screen — see
     * [DashboardScreenContent]'s `navigationBlocked` param, driven directly
     * here since Robolectric's stub WebView never fires the real trigger.
     */
    private fun captureNavBlocked(palette: Palette) =
        composeRule.captureThemed("dashboard_screen_nav_blocked", palette) {
            DashboardScreenContent(
                document = "<h1>research-assistant</h1><p>3 skills · 12 tools</p>",
                allowedSkills = listOf("web-scraping"),
                onRunSkill = {},
                onNavigateBack = {},
                agentName = "research-assistant",
                chatAwaitsUser = false,
                navigationBlocked = true,
                onNavigationBlocked = {},
                onDismissNavigationBlocked = {},
            )
        }

    @Test
    fun `dashboard with a blocked link - light`() = captureNavBlocked(Palette.Light)

    @Test
    fun `dashboard with a blocked link - dark`() = captureNavBlocked(Palette.Dark)

    /** Both bars stacked — the stalled-run bar stays pinned above the merely-informational one. */
    private fun captureNavBlockedAndAwaiting(palette: Palette) =
        composeRule.captureThemed("dashboard_screen_nav_blocked_and_awaiting", palette) {
            DashboardScreenContent(
                document = "<h1>research-assistant</h1><p>3 skills · 12 tools</p>",
                allowedSkills = listOf("web-scraping"),
                onRunSkill = {},
                onNavigateBack = {},
                agentName = "research-assistant",
                chatAwaitsUser = true,
                navigationBlocked = true,
                onNavigationBlocked = {},
                onDismissNavigationBlocked = {},
            )
        }

    @Test
    fun `dashboard with a blocked link while the chat is waiting - light`() = captureNavBlockedAndAwaiting(Palette.Light)

    @Test
    fun `dashboard with a blocked link while the chat is waiting - dark`() = captureNavBlockedAndAwaiting(Palette.Dark)
}
