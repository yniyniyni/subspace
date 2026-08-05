// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import art.yniyniyni.subspace.core.ui.component.FloatingNavigationBar
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
// TASK 22 REWRITE. Every test in this file used to start the real
// SubspaceNavHost() at Settings specifically because Settings was, until
// Task 22, this project's own Hilt-free placeholder — the KDoc this file
// carried through Tasks 18/20/21 says so directly. Task 22 wires
// SettingsScreen (a real hiltViewModel()-backed screen) into that route,
// which means EVERY top-level and pushed destination in the real
// SubspaceNavHost now needs a Hilt-aware host to render at all. There is no
// longer a Hilt-free route left in the production graph for this file to
// start at — closing the exact gap Task 21's fix round 1 disclosed and
// widened, and the one this file's own KDoc warned about at the time.
//
// No @HiltAndroidTest harness exists in this repo yet, and building this
// project's first one is not done ad hoc for one file's three tests (same
// call Task 20's fix round 1 made about QrScan, and Task 21 about Editor).
// The strategy instead — "driving NavHost directly against fakes", one of
// the options the brief for this task names — is [fakeSubspaceNavHost]
// below: a small local NavHost carrying the same five route types as the
// real graph, with trivial, Hilt-free bodies, wired to the REAL production
// functions this file can already reach ([topLevelNavItems],
// [NavHostController.navigateToTopLevel] and [selectedTopLevelValue] are all
// `internal`, not `private`, in SubspaceNavHost.kt for exactly this reason)
// plus the real [FloatingNavigationBar] component.
//
// What this proves: the actual pill-visibility gating
// (`if (selected != null) FloatingNavigationBar(...)`, reproduced verbatim
// here), the actual reselect-dedup wiring (`navigateToTopLevel`, called
// unmodified), and the actual top-level/pushed classification
// (`selectedTopLevelValue`, called unmodified) all behave correctly when
// driven through a real NavHost and a real FloatingNavigationBar. What it
// does NOT prove — the coverage genuinely lost here — is that the real
// SubspaceNavHost composable's own five `composable<...> { ... }` bodies
// (HomeScreen, ServersScreen, EditorScreen, QrScanRoute, SettingsScreen, all
// real Hilt-backed screens now) compose successfully alongside that same
// pill wiring without interfering with it — e.g. a screen that steals focus,
// throws during Hilt resolution, or misplaces window insets in a way that
// covers the pill. That end-to-end proof needs either this project's first
// @HiltAndroidTest harness or a manual device pass (Task 23's checklist);
// this file cannot provide it and does not claim to.
//
// The "pill is visible" marker below checks "Connect" (Home's nav item), not
// "Settings": Settings is the selected destination in every test here, and
// FloatingNavigationBar gives a contentDescription only to UNSELECTED items
// (the selected one is named by its own visible Text instead — see its
// KDoc). Home stays unselected throughout, so its content description is a
// stable marker independent of which top-level destination happens to be
// current.
class SubspaceNavHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    /**
     * The same [Box] + [NavHost] + [FloatingNavigationBar] shape
     * [SubspaceNavHost] itself uses — including its real
     * `if (selected != null)` gate and its real [topLevelNavItems] /
     * [NavHostController.navigateToTopLevel] — over five route bodies that
     * render nothing, so no destination needs Hilt to compose. See the
     * file-level KDoc for what this does and does not prove.
     */
    @Composable
    private fun fakeSubspaceNavHost(
        navController: NavHostController,
        startDestination: Any,
    ) {
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val selected = currentBackStackEntry?.destination.selectedTopLevelValue()

        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = startDestination) {
                composable<Home> { }
                composable<Servers> { }
                composable<Settings> { }
                composable<Editor> { }
                composable<QrScan> { }
            }

            if (selected != null) {
                FloatingNavigationBar(
                    items = topLevelNavItems(),
                    selected = selected,
                    onSelect = { value -> navController.navigateToTopLevel(value) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    /**
     * The pill exists (and is interactable) while [fakeSubspaceNavHost] sits on a real, running
     * top-level destination. The original version of this test additionally proved the pill
     * *hides* by pushing [Editor] on top and back — that half of the proof lives in
     * [selectedTopLevelValueIsNullOnlyForPushedRoutes] below, against the same small local graph.
     */
    @Test
    fun theNavBarIsVisibleOnATopLevelDestination() {
        composeRule.setContent {
            navController = rememberNavController()
            SubspaceTheme {
                fakeSubspaceNavHost(navController = navController, startDestination = Settings)
            }
        }

        // "Connect" (Home) is never the selected destination in this test
        // (Settings is), so it is reachable by content description the whole
        // time the pill exists — see the file-level comment above.
        composeRule.onNodeWithContentDescription("Connect").assertExists()
    }

    /**
     * The real proof that a pushed (non-top-level) route hides the pill — see the file-level
     * KDoc for why this drives [selectedTopLevelValue] (now `internal`) directly against a small
     * local [NavHost] rather than the full, now entirely Hilt-backed, [SubspaceNavHost]:
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
                fakeSubspaceNavHost(navController = navController, startDestination = Settings)
            }
        }

        // Unlike when this test drove the real SubspaceNavHost (whose Settings
        // placeholder body used to also render the literal text "Settings",
        // requiring hasClickAction() to disambiguate from the pill item), the
        // trivial composable<Settings> {} body here renders nothing — the
        // matcher below is kept anyway so this test still fails loudly, for
        // the reason the original comment gave, if a future change makes the
        // pill item itself stop being click-targetable.
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
