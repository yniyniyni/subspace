// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
// second entry point that is present whenever the screen is. This covers
// that the header action exists, is clickable, and invokes
// ServersActions.onAddProfile — the same action Home's chip is meant to
// reach, already wired to showAddSheet = true in ServersScreen.
class ServersAddActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val group = ServersGroup(id = 7L, name = "Local configs", totalProfileCount = 3, profiles = emptyList())

    private fun setContent(onAddProfile: () -> Unit = {}) {
        composeRule.setContent {
            SubspaceTheme {
                ServersScreenContent(
                    state = ServersState(groups = listOf(group)),
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
    fun theHeaderAddActionExistsAndIsClickableWhenGroupsArePresent() {
        setContent()

        composeRule.onNodeWithText("Add server").assertHasClickAction()
    }

    @Test
    fun tappingTheHeaderAddActionInvokesOnAddProfile() {
        var invoked = false
        setContent(onAddProfile = { invoked = true })

        composeRule.onNodeWithText("Add server").performClick()

        invoked shouldBe true
    }
}
