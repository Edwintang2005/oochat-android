package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.ui.agent.AgentListScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent list's two weights in one image — the default agent keeps a card,
 * everything else is a bare row with a divider — plus the second of the app's
 * two [androidx.compose.material3.DropdownMenu]s.
 *
 * `AgentListScreen` takes its whole state as parameters, so this is the real
 * screen body rather than a stand-in. The empty state is captured too: it is
 * a different composable entirely (`EmptyStateMessage`), not a shorter list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentListScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun captureList(
        name: String,
        palette: Palette,
        agents: List<AgentProfile>,
        interact: (ComposeContentTestRule.() -> Unit)? = null,
    ) = composeRule.captureThemed(name, palette, interact) {
        AgentListScreen(
            agents = agents,
            selectedAgentId = agents.getOrNull(1)?.id,
            defaultAgentId = agents.firstOrNull()?.id,
            onAgentSelect = {},
            onCreateAgent = {},
            onEditAgent = {},
            onDeleteAgent = {},
            onSetDefault = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun `agent list - light`() = captureList("agent_list", Palette.Light, AGENTS)

    @Test
    fun `agent list - dark`() = captureList("agent_list", Palette.Dark, AGENTS)

    @Test
    fun `agent list empty - light`() = captureList("agent_list_empty", Palette.Light, emptyList())

    @Test
    fun `agent list empty - dark`() = captureList("agent_list_empty", Palette.Dark, emptyList())

    @Test
    fun `agent overflow menu - light`() =
        captureList("agent_overflow_menu", Palette.Light, AGENTS) { openFirstOverflowMenu() }

    @Test
    fun `agent overflow menu - dark`() =
        captureList("agent_overflow_menu", Palette.Dark, AGENTS) { openFirstOverflowMenu() }

    /** The default agent's card is first, so index 0 is the card's own menu. */
    private fun ComposeContentTestRule.openFirstOverflowMenu() {
        onAllNodesWithContentDescription("Agent options")[0].performClick()
    }

    private companion object {
        // Fixed, not System.currentTimeMillis(): createdAt/lastConnectedAt are
        // not rendered today, but a live clock in a fixture is how a snapshot
        // suite starts failing on a Tuesday for no reason.
        const val CREATED_AT = 1_705_312_800_000L // 2024-01-15 10:00 UTC

        val AGENTS = listOf(
            AgentProfile(
                id = "agent-default",
                address = "0x4f3a9c2b8e1d7a6f5c4b3a2918d7e6f5c4b3a291",
                name = "research-assistant",
                description = "Reads papers and drafts summaries",
                serverUrl = "https://oo.openonion.ai",
                createdAt = CREATED_AT,
                isActive = true,
                position = 0,
            ),
            AgentProfile(
                id = "agent-build",
                address = "0x91d7e6f5c4b3a2918d7e6f5c4b3a2918d7e6f5c4",
                name = "build-bot",
                serverUrl = "https://oo.openonion.ai",
                createdAt = CREATED_AT,
                isActive = true,
                position = 1,
            ),
            AgentProfile(
                id = "agent-archive",
                address = "0x2918d7e6f5c4b3a2918d7e6f5c4b3a2918d7e6f5",
                name = "archive-indexer",
                serverUrl = "https://relay.example.internal",
                createdAt = CREATED_AT,
                isActive = false,
                position = 2,
            ),
        )
    }
}
