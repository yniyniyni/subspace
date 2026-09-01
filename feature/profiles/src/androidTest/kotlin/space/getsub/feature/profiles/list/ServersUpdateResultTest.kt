// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.add.UserMessage

/**
 * M4's device run: with the HWID toggle off the panel refused the fetch and the Servers screen
 * said nothing whatsoever, because [ServersViewModel.onUpdateSubscription] discarded its
 * `SyncResult`. [ServersViewModelTest] covers the state; this covers that the state actually
 * reaches the screen, and that the English a user reads distinguishes the two HWID outcomes —
 * the milestone's whole point.
 *
 * camelCase throughout, same DEX-040 constraint [ServersDialogsTest] documents.
 */
class ServersUpdateResultTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ServersState,
        onDismissUpdateResult: () -> Unit = {},
    ) {
        composeRule.setContent {
            SubspaceTheme {
                ServersScreenContent(
                    state = state,
                    actions =
                    ServersActions(
                        onQueryChanged = {},
                        onProtocolFilterChanged = {},
                        onSortChanged = {},
                        onProfileSelected = {},
                        onRenameGroup = { _, _ -> },
                        onDeleteGroup = {},
                        onAddProfile = {},
                        onProfileEdit = {},
                        onUpdateSubscription = {},
                        onDismissUpdateResult = onDismissUpdateResult,
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
    fun noBannerWhenThereIsNothingToReport() {
        setContent(ServersState())

        composeRule.onNodeWithTag(SERVERS_UPDATE_RESULT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theHwidRequiredReasonIsReadableOnTheServersScreen() {
        setContent(ServersState(updateResult = UserMessage(R.string.subscription_error_hwid_required)))

        composeRule.onNodeWithTag(SERVERS_UPDATE_RESULT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("This subscription requires a device ID. Enable it in Settings.")
            .assertIsDisplayed()
    }

    @Test
    fun theDeviceLimitReasonIsDistinctFromTheHwidRequiredOne() {
        // The two are different problems with different fixes; a user who cannot tell them apart
        // cannot act on either. The fetcher's own classification is what feeds this.
        setContent(ServersState(updateResult = UserMessage(R.string.subscription_error_device_limit)))

        composeRule.onNodeWithText("This subscription requires a device ID. Enable it in Settings.")
            .assertDoesNotExist()
        composeRule.onNodeWithTag(SERVERS_UPDATE_RESULT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun tappingTheBannerDismissesIt() {
        var dismissed = false
        setContent(
            state = ServersState(updateResult = UserMessage(R.string.subscription_error_hwid_required)),
            onDismissUpdateResult = { dismissed = true },
        )

        composeRule.onNodeWithTag(SERVERS_UPDATE_RESULT_TEST_TAG).performClick()

        dismissed shouldBe true
    }
}
