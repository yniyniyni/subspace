// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// Backtick names with spaces are avoided here (unlike the task brief's literal
// text) for the same reason ConnectControlTest.kt gives: minSdk 26 makes D8
// emit a DEX version that rejects spaces in synthetic class names, and any
// lambda passed to a test method inherits that method's JVM name. camelCase
// throughout, matching every other androidTest in this repo.
//
// startDestination defaults to Servers, not the production Home, in every
// test here. SubspaceNavHost wires Home to feature:home's real HomeScreen,
// which resolves its ViewModel through hiltViewModel() — that needs a
// Hilt-aware host, and these tests are exercising routing/pill behaviour, not
// HomeScreen. Servers and Settings are this task's own placeholders (no
// Hilt), so starting there tests the exact same NavHost wiring without
// pulling in infrastructure this task does not otherwise need. See
// SubspaceNavHost's own KDoc on the startDestination parameter.
class SubspaceNavHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun theNavBarIsHiddenOnPushedRoutes() {
        composeRule.setContent {
            navController = rememberNavController()
            SubspaceTheme {
                SubspaceNavHost(
                    onRequestConsent = {},
                    navController = navController,
                    startDestination = Servers,
                )
            }
        }

        // "Settings" is never the selected destination in this test, so it is
        // reachable by content description the whole time the pill exists
        // (FloatingNavigationBar shows a visible Text label only for the
        // selected item — see its own KDoc) — a stable marker for "the pill
        // is on screen" independent of which top-level destination is active.
        composeRule.onNodeWithContentDescription("Settings").assertExists()

        composeRule.runOnIdle { navController.navigate(Editor(EDITOR_TEST_PROFILE_ID)) }
        composeRule.onNodeWithContentDescription("Settings").assertDoesNotExist()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun selectingADestinationTwiceDoesNotStackDuplicates() {
        composeRule.setContent {
            navController = rememberNavController()
            SubspaceTheme {
                SubspaceNavHost(
                    onRequestConsent = {},
                    navController = navController,
                    startDestination = Servers,
                )
            }
        }

        // "Settings" appears twice in the tree once its screen is showing: the
        // pill's own destination item AND that destination's placeholder body
        // (both literally say "Settings" — the placeholder reuses the same
        // string as its title). hasClickAction() disambiguates: the pill item
        // is `.selectable(...)`, the placeholder body is a plain, non-clickable
        // Text. Matching on content-description-or-text alone (as this test
        // first tried) silently taps the inert placeholder body on the second
        // click instead of the pill — the click "succeeds" (a real touch lands
        // somewhere) but does nothing, and the assertion below would pass for
        // the wrong reason. Confirmed with a diagnostic build: without
        // hasClickAction(), the back-stack size was identical before and after
        // the second click even against a deliberately-broken implementation
        // that omits launchSingleTop entirely — the click never reached the
        // nav item in the first place.
        val settingsNavItem = hasClickAction().and(hasText("Settings").or(hasContentDescription("Settings")))

        composeRule.onNode(settingsNavItem).performClick()
        composeRule.waitForIdle()
        val sizeAfterFirstSelect = navController.currentBackStack.value.size

        composeRule.onNode(settingsNavItem).performClick()
        composeRule.waitForIdle()
        val sizeAfterReselect = navController.currentBackStack.value.size

        sizeAfterReselect shouldBe sizeAfterFirstSelect
    }

    private companion object {
        const val EDITOR_TEST_PROFILE_ID = 1L
    }
}
