// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * [DiscardChangesDialog] on its own, not through [PerAppScreenContent] or
 * [PerAppScreen]. [PerAppScreen]'s own KDoc explains why the dialog is hoisted
 * there rather than into [PerAppScreenContent]: the system back gesture
 * ([androidx.activity.compose.BackHandler]) and the on-screen back button need
 * to raise the *same* confirmation, and only the stateful half can see both.
 * That hoist is exactly what puts the dialog out of
 * [PerAppScreenContentTest]'s reach — [PerAppScreenContentTest] never
 * constructs [PerAppScreen] at all, since that would need a Hilt-provided
 * [PerAppViewModel]. This class covers the dialog's content and its callback
 * wiring instead, which needs no ViewModel.
 *
 * Not covered here, or anywhere in this module's test suite:
 * [androidx.activity.compose.BackHandler]'s own `enabled = state.isDirty`
 * gating (never raising this dialog on a clean draft). That needs a real back
 * gesture dispatched through a real `OnBackPressedDispatcher` against a
 * running [PerAppScreen], which only the device checklist (§11) exercises —
 * recorded there, not asserted by CI.
 */
class DiscardChangesDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theThreeDiscardStringsAreDisplayed() {
        rule.setContent { DiscardChangesDialog(onConfirm = {}, onDismiss = {}) }

        rule.onNodeWithText("Discard changes?").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").assertIsDisplayed()
    }

    @Test
    fun confirmingInvokesOnlyTheConfirmCallback() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            DiscardChangesDialog(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }

        rule.onNodeWithText("Discard").performClick()

        confirmed shouldBe true
        dismissed shouldBe false
    }

    @Test
    fun dismissingInvokesOnlyTheDismissCallback() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            DiscardChangesDialog(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }

        rule.onNodeWithText("Keep editing").performClick()

        confirmed shouldBe false
        dismissed shouldBe true
    }
}
