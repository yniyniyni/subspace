// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// camelCase throughout — same DEX-040 constraint as ConnectControlTest.kt,
// SubspaceBottomSheetTest.kt and FloatingNavigationBarTest.kt. Fix round 1,
// finding 3: this file existed nowhere before code review — GroupCard's
// caret, overflow menu and their wiring to GroupCardActions were unverified
// beyond compiling and passing the JVM ViewModel suite.
class GroupCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val contentTag = "group-card-content"

    private fun noopActions(
        onToggleExpand: () -> Unit = {},
        onRename: () -> Unit = {},
        onDelete: () -> Unit = {},
        onAddProfile: () -> Unit = {},
    ) = GroupCardActions(
        onToggleExpand = onToggleExpand,
        onRename = onRename,
        onDelete = onDelete,
        onAddProfile = onAddProfile,
    )

    @Test
    fun collapsedGroupHidesItsContent() {
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(name = "Local configs", profileCount = 3, expanded = false, actions = noopActions()) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNodeWithTag(contentTag).assertDoesNotExist()
    }

    @Test
    fun expandedGroupShowsItsContent() {
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(name = "Local configs", profileCount = 3, expanded = true, actions = noopActions()) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNodeWithTag(contentTag).assertExists()
    }

    @Test
    fun tappingTheHeaderInvokesOnToggleExpand() {
        var toggled = false
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "Local configs",
                    profileCount = 3,
                    expanded = false,
                    actions = noopActions(onToggleExpand = { toggled = true }),
                ) {}
            }
        }
        // "Expand", not "Collapse": expanded = false above, and GroupCard's
        // toggle description names the action a tap performs next — the
        // same convention ConnectControl's own contentDescription follows.
        composeRule.onNodeWithContentDescription("Expand Local configs, 3 servers").performClick()
        toggled shouldBe true
    }

    @Test
    fun theOverflowMenuOffersRenameDeleteAndAddProfile() {
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(name = "Local configs", profileCount = 3, expanded = false, actions = noopActions()) {}
            }
        }
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Rename").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
        composeRule.onNodeWithText("Add profile").assertExists()
    }

    @Test
    fun choosingRenameFromTheOverflowMenuInvokesOnRenameOnly() {
        var renamed = false
        var deleted = false
        var added = false
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "Local configs",
                    profileCount = 3,
                    expanded = false,
                    actions =
                    noopActions(
                        onRename = { renamed = true },
                        onDelete = { deleted = true },
                        onAddProfile = { added = true },
                    ),
                ) {}
            }
        }
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Rename").performClick()

        renamed shouldBe true
        deleted shouldBe false
        added shouldBe false
    }

    @Test
    fun choosingDeleteFromTheOverflowMenuInvokesOnDeleteOnly() {
        var renamed = false
        var deleted = false
        var added = false
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "Local configs",
                    profileCount = 3,
                    expanded = false,
                    actions =
                    noopActions(
                        onRename = { renamed = true },
                        onDelete = { deleted = true },
                        onAddProfile = { added = true },
                    ),
                ) {}
            }
        }
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        deleted shouldBe true
        renamed shouldBe false
        added shouldBe false
    }

    @Test
    fun choosingAddProfileFromTheOverflowMenuInvokesOnAddProfileOnly() {
        var renamed = false
        var deleted = false
        var added = false
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "Local configs",
                    profileCount = 3,
                    expanded = false,
                    actions =
                    noopActions(
                        onRename = { renamed = true },
                        onDelete = { deleted = true },
                        onAddProfile = { added = true },
                    ),
                ) {}
            }
        }
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Add profile").performClick()

        added shouldBe true
        renamed shouldBe false
        deleted shouldBe false
    }

    @Test
    fun choosingAMenuItemClosesTheMenu() {
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(name = "Local configs", profileCount = 3, expanded = false, actions = noopActions()) {}
            }
        }
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }
}
