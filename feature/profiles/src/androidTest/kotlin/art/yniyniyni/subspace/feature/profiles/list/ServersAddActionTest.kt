// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// camelCase throughout, same DEX-040 constraint ServersDialogsTest documents.
//
// Task 2 (M7 device fixes, 2026-08-31): the empty state's own "Add server"
// button is only reachable when there are zero groups. Home's "Add server"
// chip navigates here regardless, so a user who already has a group needs a
// second entry point that is present whenever the screen is.
//
// Fix round 1 (review): the header's own button and the empty state's
// (`EmptyServersState`, private to ServersGroupList.kt) render the same
// literal "Add server" text, and the empty state renders exactly when
// ServersState.groups is empty. Rendering the header's button
// unconditionally would put both on screen at once for an empty list —
// this pins the invariant that exactly one "Add server" node exists in
// either state, not just that a node exists for the fixture this test
// happens to use.
class ServersAddActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val group = ServersGroup(id = 7L, name = "Local configs", totalProfileCount = 3, profiles = emptyList())

    private fun setContent(
        groups: List<ServersGroup> = listOf(group),
        onAddProfile: () -> Unit = {},
    ) {
        composeRule.setContent {
            SubspaceTheme {
                ServersScreenContent(
                    state = ServersState(groups = groups),
                    actions =
                    ServersActions(
                        onQueryChanged = {},
                        onProtocolFilterChanged = {},
                        onSortChanged = {},
                        onProfileSelected = {},
                        onRenameGroup = { _, _ -> },
                        onDeleteGroup = {},
                        onAddProfile = onAddProfile,
                        onProfileEdit = {},
                        onUpdateSubscription = {},
                        onDismissUpdateResult = {},
                        onOpenSubscriptionDetail = {},
                        onTestProfile = {},
                        onTestGroup = {},
                        onCancelTests = {},
                        onGroupSortChanged = { _, _ -> },
                        onServersShown = {},
                    ),
                )
            }
        }
    }

    @Test
    fun exactlyOneHeaderAddActionExistsAndIsClickableWhenGroupsArePresent() {
        setContent(groups = listOf(group))

        composeRule.onAllNodesWithText("Add server").fetchSemanticsNodes().size shouldBe 1
        composeRule.onNodeWithText("Add server").assertHasClickAction()
    }

    @Test
    fun tappingTheHeaderAddActionInvokesOnAddProfile() {
        var invoked = false
        setContent(groups = listOf(group), onAddProfile = { invoked = true })

        composeRule.onNodeWithText("Add server").performClick()

        invoked shouldBe true
    }

    @Test
    fun exactlyOneAddActionExistsWhenGroupsAreEmpty() {
        // The header must not add a second "Add server" node alongside the
        // empty state's own button — this is the sole CTA in this state.
        setContent(groups = emptyList())

        composeRule.onAllNodesWithText("Add server").fetchSemanticsNodes().size shouldBe 1
    }
}
