// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

        composeRule.onNodeWithText("Disconnected", substring = true).assertDoesNotExist()
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
