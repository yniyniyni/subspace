// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// camelCase throughout — same DEX-040 constraint core/ui's ConnectControlTest.kt
// and GroupCardTest.kt document. Fix round 1, finding 3: this is the
// dialog-confirm/cancel wiring code review flagged as untested — a group's
// rename and its cascading delete are exactly the two places a wiring bug
// here would either silently do nothing or, worse for delete, remove the
// wrong thing with no way back.
class ServersDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val group = ServersGroup(id = 7L, name = "Local configs", totalProfileCount = 3, profiles = emptyList())

    private fun noopActions(
        onRenameConfirm: (Long, String) -> Unit = { _, _ -> },
        onRenameDismiss: () -> Unit = {},
        onDeleteConfirm: (Long) -> Unit = {},
        onDeleteDismiss: () -> Unit = {},
    ) = ServersDialogActions(
        onRenameConfirm = onRenameConfirm,
        onRenameDismiss = onRenameDismiss,
        onDeleteConfirm = onDeleteConfirm,
        onDeleteDismiss = onDeleteDismiss,
    )

    @Test
    fun neitherDialogRendersWhenBothTargetsAreNull() {
        composeRule.setContent {
            SubspaceTheme { ServersDialogs(renameTarget = null, deleteTarget = null, actions = noopActions()) }
        }
        composeRule.onNodeWithText("Rename group").assertDoesNotExist()
        composeRule.onNodeWithText("Delete", substring = true).assertDoesNotExist()
    }

    @Test
    fun renameDialogPrefillsTheCurrentName() {
        composeRule.setContent {
            SubspaceTheme { ServersDialogs(renameTarget = group, deleteTarget = null, actions = noopActions()) }
        }
        composeRule.onNodeWithText("Local configs").assertExists()
    }

    @Test
    fun confirmingRenameInvokesOnRenameConfirmWithTheNewNameAndTheGroupId() {
        var confirmedId: Long? = null
        var confirmedName: String? = null
        composeRule.setContent {
            SubspaceTheme {
                ServersDialogs(
                    renameTarget = group,
                    deleteTarget = null,
                    actions =
                    noopActions(
                        onRenameConfirm = { id, name ->
                            confirmedId = id
                            confirmedName = name
                        },
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Local configs").performTextReplacement("Frankfurt group")
        composeRule.onNodeWithText("Rename").performClick()

        confirmedId shouldBe group.id
        confirmedName shouldBe "Frankfurt group"
    }

    @Test
    fun cancellingRenameInvokesOnRenameDismissNotOnRenameConfirm() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            SubspaceTheme {
                ServersDialogs(
                    renameTarget = group,
                    deleteTarget = null,
                    actions =
                    noopActions(
                        onRenameConfirm = { _, _ -> confirmed = true },
                        onRenameDismiss = { dismissed = true },
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()

        dismissed shouldBe true
        confirmed shouldBe false
    }

    @Test
    fun deleteDialogStatesTheGroupsRealProfileCount() {
        composeRule.setContent {
            SubspaceTheme { ServersDialogs(renameTarget = null, deleteTarget = group, actions = noopActions()) }
        }
        // group.totalProfileCount = 3 above — the plural "servers" branch.
        composeRule.onNodeWithText("This removes 3 servers with it. This cannot be undone.").assertExists()
    }

    @Test
    fun confirmingDeleteInvokesOnDeleteConfirmWithTheGroupId() {
        var confirmedId: Long? = null
        composeRule.setContent {
            SubspaceTheme {
                ServersDialogs(
                    renameTarget = null,
                    deleteTarget = group,
                    actions = noopActions(onDeleteConfirm = { id -> confirmedId = id }),
                )
            }
        }
        composeRule.onNodeWithText("Delete").performClick()
        confirmedId shouldBe group.id
    }

    @Test
    fun cancellingDeleteInvokesOnDeleteDismissNotOnDeleteConfirm() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            SubspaceTheme {
                ServersDialogs(
                    renameTarget = null,
                    deleteTarget = group,
                    actions =
                    noopActions(
                        onDeleteConfirm = { confirmed = true },
                        onDeleteDismiss = { dismissed = true },
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()

        dismissed shouldBe true
        confirmed shouldBe false
    }
}
