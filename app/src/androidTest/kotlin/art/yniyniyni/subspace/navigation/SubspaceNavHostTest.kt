// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test

// Backtick names with spaces are avoided here (unlike the task brief's literal
// text) for the same reason ConnectControlTest.kt gives: minSdk 26 makes D8
// emit a DEX version that rejects spaces in synthetic class names, and any
// lambda passed to a test method inherits that method's JVM name. camelCase
// throughout, matching every other androidTest in this repo.
//
// startDestination defaults to Settings, not the production Home, in every
// test here. SubspaceNavHost wires Home to feature:home's real HomeScreen,
// (as of Task 18's fix round 1) Servers to feature:profiles' real
// ServersScreen, and (as of Task 20's fix round 1) QrScan to feature:profiles'
// real QrScanRoute — all three resolve a ViewModel through hiltViewModel()
// (QrScanRoute's own is scoped to the Servers back-stack entry specifically,
// which additionally does not exist unless Servers has actually been
// navigated to first), and these tests are exercising routing/pill
// behaviour, not any individual screen's own content. Settings is still this
// project's own placeholder (no Hilt yet), so starting there tests the exact
// same NavHost wiring without pulling in infrastructure this task does not
// otherwise need. See SubspaceNavHost's own KDoc on the startDestination
// parameter.
//
// DISCLOSED GAP, WIDENED IN TASK 21: QrScan's own pill-hiding was already not
// separately exercised here (Task 20 fix round 1) because this file stays
// Hilt-free deliberately and QrScan needs Hilt. That gap previously leaned on
// Editor for proof of the shared `else -> null` branch of SubspaceNavHost's
// `selectedTopLevelValue()`, because Editor was still a Hilt-free placeholder.
// Task 21 replaces that placeholder with the real, Hilt-backed EditorScreen —
// closing the LAST pushed (non-top-level) route this file could navigate to
// without crashing. `selectedTopLevelValueIsNullOnlyForPushedRoutes` below no
// longer drives the real SubspaceNavHost() to prove this; it exercises `selectedTopLevelValue()`
// (now `internal`, not `private`, for exactly this reason) directly against a
// small local NavHost built from the same five route types with trivial,
// Hilt-free bodies. That is a real, non-fabricated proof of the actual mapping
// function — the one thing that decides whether the pill shows or hides — but
// it is not an end-to-end proof that SubspaceNavHost's own `if (selected !=
// null) FloatingNavigationBar(...)` gating (an unmodified one-line condition)
// wires that mapping correctly against the real screens. A true Hilt-aware
// instrumented test of the full graph would need this project's first
// `@HiltAndroidTest` harness, which does not exist yet — noted here rather
// than built ad hoc for one test, same call Task 20's fix round 1 already made.
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

    /**
     * The pill exists (and is interactable) while [SubspaceNavHost] sits on a real, running
     * top-level destination. The original version of this test additionally proved the pill
     * *hides* by pushing [Editor] on top and back — [Editor] became a real, Hilt-backed screen
     * in Task 21, which this Hilt-free harness cannot compose (see the file-level KDoc); that
     * half of the proof moved to [selectedTopLevelValueIsNullOnlyForPushedRoutes] below, against
     * a small local graph instead of the full [SubspaceNavHost].
     */
    @Test
    fun theNavBarIsVisibleOnATopLevelDestination() {
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
    }

    /**
     * The real proof that a pushed (non-top-level) route hides the pill — see the file-level
     * KDoc for why this no longer drives the full, now entirely Hilt-backed [SubspaceNavHost]
     * for [Editor]/[QrScan] specifically. A small local [NavHost] carrying the same five route
     * types with trivial bodies exercises [selectedTopLevelValue] (now `internal`) directly:
     * [Home]/[Servers]/[Settings] resolve to a non-null pill value, [Editor]/[QrScan] to `null`.
     */
    @Test
    fun selectedTopLevelValueIsNullOnlyForPushedRoutes() {
        lateinit var localNavController: NavHostController
        composeRule.setContent {
            localNavController = rememberNavController()
            NavHost(navController = localNavController, startDestination = Settings) {
                composable<Home> { }
                composable<Servers> { }
                composable<Settings> { }
                composable<Editor> { }
                composable<QrScan> { }
            }
        }

        composeRule.runOnIdle { localNavController.currentDestination.selectedTopLevelValue() shouldNotBe null }

        composeRule.runOnIdle { localNavController.navigate(Home) }
        composeRule.runOnIdle { localNavController.currentDestination.selectedTopLevelValue() shouldNotBe null }

        composeRule.runOnIdle { localNavController.navigate(Servers) }
        composeRule.runOnIdle { localNavController.currentDestination.selectedTopLevelValue() shouldNotBe null }

        composeRule.runOnIdle { localNavController.navigate(Editor(EDITOR_TEST_PROFILE_ID)) }
        composeRule.runOnIdle { localNavController.currentDestination.selectedTopLevelValue() shouldBe null }

        composeRule.runOnIdle { localNavController.popBackStack() }
        composeRule.runOnIdle { localNavController.navigate(QrScan) }
        composeRule.runOnIdle { localNavController.currentDestination.selectedTopLevelValue() shouldBe null }
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
