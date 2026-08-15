// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * Task 16's own fix round 1 (Finding 5): this task shipped zero Compose tests, even non-running
 * ones, unlike Task 15's [RoutingListScreenContentTest] — this closes that gap for the same
 * layout/rendering facts a JVM state test cannot see: whether the category picker actually shows
 * or hides, whether a rejected entry's text and reason actually render where the user is typing.
 * [RuleSetEditorViewModelTest] is the runnable proof of the underlying state transitions; this is
 * the rendering half, same split [RoutingListScreenContentTest]'s own KDoc documents.
 *
 * **Not run in this environment** — no Android device or emulator is reachable here. Compiled and
 * left for a `connectedDebugAndroidTest` pass on a real device or CI runner.
 *
 * camelCase test names throughout, same DEX-040 constraint every other instrumented test in this
 * repo documents.
 */
class RuleSetEditorScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val noOpActions =
        RuleSetEditorActions(
            onBack = {},
            onNameChanged = {},
            onAddEntry = { _, _, _ -> },
            onRemoveEntry = { _, _, _ -> },
            onMoveEarlier = {},
            onMoveLater = {},
            onDomainStrategyChanged = {},
            onSave = {},
        )

    private fun setContent(
        state: RuleSetEditorState,
        actions: RuleSetEditorActions = noOpActions,
    ) {
        composeRule.setContent {
            SubspaceTheme {
                RuleSetEditorContent(state = state, actions = actions)
            }
        }
    }

    @Test
    fun aLoadingDraftShowsTheSpinnerAndNothingElse() {
        setContent(RuleSetEditorState(loading = true))

        composeRule.onNodeWithTag(RULE_SET_EDITOR_LOADING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun anUnnamedDraftHasADisabledSaveButton() {
        setContent(RuleSetEditorState(loading = false, name = ""))

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aNamedDraftHasAnEnabledSaveButton() {
        setContent(RuleSetEditorState(loading = false, name = "ads"))

        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    // Finding 5: the category picker's show/hide rule was pinned by nothing before this file.
    @Test
    fun theCategoryPickerOnlyAppearsForAFieldThatHasCategories() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                siteCategories = listOf(GeoCategory("category-ads-all", 42)),
                ipCategories = emptyList(),
            ),
        )

        // Six sections exist (BLOCK/PROXY/DIRECT × SITES/IPS); only the three SITES ones offer
        // "Browse categories" — the three IPS ones must not, since ipCategories is empty.
        composeRule.onAllNodesWithText("Browse categories").assertCountEquals(3)
    }

    @Test
    fun noCategoriesMeansTheBrowseAffordanceNeverAppears() {
        setContent(RuleSetEditorState(loading = false, name = "ads"))

        composeRule.onNodeWithText("Browse categories").assertDoesNotExist()
    }

    // Finding 4: a rejected entry must keep its text and show its reason on the field it belongs
    // to, not clear silently and report off-screen.
    @Test
    fun typingAMalformedAddressKeepsTheTextAndShowsTheReasonInline() {
        var added: String? = null
        setContent(
            state = RuleSetEditorState(loading = false, name = "ads"),
            actions = noOpActions.copy(onAddEntry = { _, _, entry -> added = entry }),
        )

        composeRule.onAllNodesWithText("Domain, address or geosite:/geoip: code")[0].performTextInput("999.1.1.1")

        composeRule.onNodeWithText("999.1.1.1").assertIsDisplayed()
        composeRule.onNodeWithText("Not a valid address or CIDR range").assertIsDisplayed()
        added.shouldBeNull()
    }

    @Test
    fun anExistingEntryHasARemoveControl() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("example.com"))),
            ),
        )

        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Remove entry")[0].assertIsDisplayed()
    }

    @Test
    fun aNameConflictProblemIsShownNearSave() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                saveProblem = SaveProblem.NameConflict("ads"),
            ),
        )

        val message = "A rule set named \"ads\" already exists. Choose a different name."
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun tappingSaveInvokesTheCallback() {
        var saved = false
        setContent(
            state = RuleSetEditorState(loading = false, name = "ads"),
            actions = noOpActions.copy(onSave = { saved = true }),
        )

        composeRule.onNodeWithText("Save").performClick()

        saved shouldBe true
    }
}
