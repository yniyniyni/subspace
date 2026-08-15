// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import org.junit.Rule
import org.junit.Test

/**
 * Task 15: proves the two activation-gate markers render distinctly (never
 * conflated — see [RuleSetRow]'s own KDoc) and that the empty state appears
 * with nothing stored. [RoutingViewModelTest] is the runnable proof that the
 * gate is *enforced*; this is the layout/rendering half no plain JVM test can
 * see, the same split [art.yniyniyni.subspace.feature.settings.SettingsHwidLayoutTest]
 * documents for the identical reason.
 *
 * **Not run in this environment** — no Android device or emulator is
 * reachable here (see the M5 Task 15 report). Compiled and left for a
 * `connectedDebugAndroidTest` pass on a real device or CI runner.
 *
 * camelCase test names throughout, same DEX-040 constraint every other
 * instrumented test in this repo documents.
 */
class RoutingListScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val blockedRow =
        RuleSetRow(
            id = 1,
            name = "ads",
            entryCount = 3,
            isActive = false,
            missingGeoFiles = setOf("geosite.dat"),
        )
    private val failedButActivatableRow =
        RuleSetRow(
            id = 2,
            name = "lan",
            entryCount = 1,
            isActive = true,
            missingGeoFiles = emptySet(),
            hasFailedGeoUpdate = true,
        )

    private fun setContent(state: RoutingState) {
        composeRule.setContent {
            SubspaceTheme {
                RoutingListScreenContent(
                    state = state,
                    actions =
                    RoutingListActions(
                        onActivate = {},
                        onDelete = {},
                        onCreateRuleSet = {},
                        onEditRuleSet = {},
                        onBack = {},
                    ),
                )
            }
        }
    }

    @Test
    fun aRuleSetMissingGeoFilesShowsTheBlockingMarkerAndADisabledSelector() {
        setContent(RoutingState(ruleSets = listOf(blockedRow)))

        composeRule.onNodeWithText("Needs geo files: geosite.dat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Activate ads").assertIsNotEnabled()
    }

    // The point of this screen: a failed-update marker never disables the selector the way a
    // missing-geo-files marker does — conflating the two is the defect Task 15's brief calls out.
    @Test
    fun aFailedUpdateShowsItsOwnMarkerWithoutBlockingActivation() {
        setContent(RoutingState(ruleSets = listOf(failedButActivatableRow)))

        composeRule.onNodeWithText("Last update failed").assertIsDisplayed()
        composeRule.onNodeWithText("Needs geo files:", substring = true).assertDoesNotExist()
        // The half this test is named for but did not assert: a failed refresh
        // is a warning, not a gate. Without this line, making
        // hasFailedGeoUpdate disable the selector leaves the test green.
        // Fix round 2: this test renders failedButActivatableRow ("lan"), not
        // blockedRow ("ads") — the finder previously matched zero nodes.
        composeRule.onNodeWithContentDescription("Activate lan").assertIsEnabled()
    }

    @Test
    fun noRuleSetsShowsTheEmptyState() {
        setContent(RoutingState(ruleSets = emptyList()))

        composeRule.onNodeWithText("No rule sets yet").assertIsDisplayed()
    }

    @Test
    fun theOffRowIsAlwaysPresent() {
        setContent(RoutingState(ruleSets = listOf(blockedRow)))

        composeRule.onNodeWithText("Off").assertIsDisplayed()
    }
}
