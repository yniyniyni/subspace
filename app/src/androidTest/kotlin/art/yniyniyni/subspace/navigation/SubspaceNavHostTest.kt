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
// startDestination defaults to Settings, not the production Home, in every
// test here. SubspaceNavHost wires Home to feature:home's real HomeScreen and
// (as of Task 18's fix round 1) Servers to feature:profiles' real
// ServersScreen — both resolve a ViewModel through hiltViewModel(), which
// needs a Hilt-aware host, and these tests are exercising routing/pill
// behaviour, not either screen's own content. Settings is still this
// project's own placeholder (no Hilt yet), so starting there tests the exact
// same NavHost wiring without pulling in infrastructure this task does not
// otherwise need. See SubspaceNavHost's own KDoc on the startDestination
// parameter.
//
// The "pill is visible" marker below checks "Connect" (Home's nav item), not
// "Settings": Settings is the selected destination in every test here, and
// FloatingNavigationBar gives a contentDescription only to UNSELECTED items
// (the selected one is named by its own visible Text instead — see its KDoc).
// Home stays unselected throughout, so its content description is a stable
// marker independent of which top-level destination happens to be current.
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
                    startDestination = Settings,
                )
            }
        }

        // "Connect" (Home) is never the selected destination in this test
        // (Settings is), so it is reachable by content description the whole
        // time the pill exists — see the file-level comment above.
        composeRule.onNodeWithContentDescription("Connect").assertExists()

        composeRule.runOnIdle { navController.navigate(Editor(EDITOR_TEST_PROFILE_ID)) }
        composeRule.onNodeWithContentDescription("Connect").assertDoesNotExist()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.onNodeWithContentDescription("Connect").assertExists()
    }

    @Test
    fun selectingADestinationTwiceDoesNotStackDuplicates() {
        composeRule.setContent {
            navController = rememberNavController()
            SubspaceTheme {
                SubspaceNavHost(
                    onRequestConsent = {},
                    navController = navController,
                    startDestination = Settings,
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
