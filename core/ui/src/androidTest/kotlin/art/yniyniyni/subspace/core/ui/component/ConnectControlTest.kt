// SPDX-License-Identifier: AGPL-3.0-or-later
// The v2 rule (androidx.compose.ui.test.junit4.v2.createComposeRule) that this
// module's compose-compiler suggests replacing this with produced
// "IllegalStateException: No compose hierarchies found in the app" on every
// assertion/interaction that ran after setContent, on this device
// (Pixel 8, API 37) — confirmed by running both: v1 attaches content and
// finds it every time, v2 did not once across five tests. v1 is not removed,
// only deprecated in favor of v2's StandardTestDispatcher-based coroutine
// semantics, which this file's synchronous, non-coroutine assertions do not
// need. Suppressed rather than chasing an API that fails on real hardware.
@file:Suppress("DEPRECATION")

package art.yniyniyni.subspace.core.ui.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// Backtick names with spaces are avoided here (unlike the task brief's literal
// text) for the same reason SubspaceDatabaseTest.kt gives: this module's
// minSdk (26, from subspace.android.library) makes D8 emit a DEX version that
// rejects spaces in synthetic class names, and any lambda passed to a test
// method inherits that method's JVM name. camelCase throughout, matching
// every other androidTest in this repo.
class ConnectControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theControlReportsItsActionNotItsStateToAccessibility() {
        composeRule.setContent {
            SubspaceTheme { ConnectControl(state = ConnectVisualState.Connected, onClick = {}) }
        }
        // A screen-reader user needs to know what tapping does. "Connected" as a
        // label would announce the state and hide the action.
        composeRule.onNodeWithContentDescription("Disconnect").assertExists()
    }

    @Test
    fun tappingWhileConnectingDoesNothing() {
        var clicks = 0
        composeRule.setContent {
            SubspaceTheme { ConnectControl(state = ConnectVisualState.Connecting, onClick = { clicks++ }) }
        }
        composeRule.onRoot().performClick()
        clicks shouldBe 0
    }

    // Beyond the brief's two required tests: proves the halo does not merely
    // render at zero alpha while disconnected but is genuinely absent from
    // composition, so the rememberInfiniteTransition inside it never starts —
    // no continuously-invalidating animation clock subscription exists to cost
    // battery in the states where the design explicitly forbids one.
    @Test
    fun haloIsAbsentWhenDisconnected() {
        composeRule.setContent {
            SubspaceTheme { ConnectControl(state = ConnectVisualState.Disconnected, onClick = {}) }
        }
        composeRule.onNodeWithTag(CONNECT_HALO_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun haloIsAbsentWhileConnecting() {
        composeRule.setContent {
            SubspaceTheme { ConnectControl(state = ConnectVisualState.Connecting, onClick = {}) }
        }
        composeRule.onNodeWithTag(CONNECT_HALO_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun haloExistsWhenConnected() {
        composeRule.setContent {
            SubspaceTheme { ConnectControl(state = ConnectVisualState.Connected, onClick = {}) }
        }
        composeRule.onNodeWithTag(CONNECT_HALO_TEST_TAG).assertExists()
    }
}
