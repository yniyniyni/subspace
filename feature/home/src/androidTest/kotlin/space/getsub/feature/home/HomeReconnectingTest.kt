// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.ui.theme.SubspaceTheme

/**
 * Spec §7.3 / §5.5: [ConnectionState.Reconnecting] is neither [ConnectionState.Connected]
 * nor [ConnectionState.Failed], and rendering it as either is a lie the user acts on.
 */
class HomeReconnectingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reconnectingIsNotRenderedAsConnected() {
        setContent(homeState(connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2)))

        composeRule.onNodeWithText("Reconnecting…", substring = true).assertIsDisplayed()
    }

    @Test
    fun reconnectingIsNotRenderedAsFailed() {
        setContent(homeState(connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2)))

        // "Disconnected" alone would not catch this: ConnectionState.Failed never renders that
        // literal string — it renders reason.labelRes(), which for CoreStartFailed is exactly
        // "The core failed to start". That is the text a Reconnecting-rendered-as-Failed
        // regression would actually put on screen, so that is what must be absent.
        composeRule.onNodeWithText("The core failed to start", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Reconnecting…", substring = true).assertIsDisplayed()
    }

    /**
     * Spec §7.3, and ruling R23. A `Retryable` reason retries for as long as a network
     * exists, so this state has no bound; with the kill switch on (the default) the user has
     * no connectivity while it retries. Home is therefore the exit, and this asserts the tap
     * actually reaches [HomeActions.onDisconnect].
     *
     * Three independent refusals had to be lifted together for this to pass — [HomeState
     * .canDisconnect] excluded `Reconnecting`, `toVisualState` mapped it to
     * `ConnectVisualState.Connecting`, and `ConnectControl` refuses every tap in that visual
     * state — so a regression restoring any one of them fails here.
     */
    @Test
    fun reconnectingOffersAWorkingDisconnect() {
        var disconnects = 0
        val state = homeState(connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2))
        composeRule.setContent {
            SubspaceTheme {
                HomeScreenContent(state = state, actions = actions.copy(onDisconnect = { disconnects++ }))
            }
        }

        // By content description, not by the visible label: ConnectControl reports the
        // *action* a tap performs, and "Disconnect" being announced at all is half of what
        // this test is for — a screen-reader user who is told "Reconnecting" has no way to
        // discover the exit.
        composeRule.onNodeWithContentDescription("Disconnect").performClick()

        disconnects shouldBe 1
    }

    private fun setContent(state: HomeState) {
        composeRule.setContent {
            SubspaceTheme {
                HomeScreenContent(state = state, actions = actions)
            }
        }
    }

    private fun homeState(connection: ConnectionState) = HomeState(connection = connection)

    private val actions =
        HomeActions(
            onConnect = {},
            onDisconnect = {},
            onNavigateToServers = {},
            onAddServer = {},
            onTestLatency = {},
        )
}
