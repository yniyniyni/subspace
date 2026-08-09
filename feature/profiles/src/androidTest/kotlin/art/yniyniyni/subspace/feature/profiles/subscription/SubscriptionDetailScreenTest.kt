// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.subscription

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

class SubscriptionDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pinnedInterval =
        DirectiveRow(
            kind = DirectiveRowKind.Hours,
            labelRes = art.yniyniyni.subspace.feature.profiles.R.string.subscription_row_interval_label,
            row = SettingRowState("profile-update-interval", "24", declinedProviderValue = "6", isPinned = true),
        )

    private fun setContent(
        state: SubscriptionDetailState = detailState(),
        actions: SubscriptionDetailActions = noOpActions(),
    ) {
        composeRule.setContent {
            SubspaceTheme { SubscriptionDetailContent(state = state, actions = actions) }
        }
    }

    @Test
    fun pinnedValueShowsTheDeclinedProviderSuggestion() {
        setContent()

        composeRule.onNodeWithText("Provider suggests 6 h · you pinned 24 h").assertExists()
    }

    @Test
    fun theFullSubscriptionUrlIsNeverRendered() {
        setContent()

        composeRule.onNodeWithText("https://panel.example/sub/secret-token").assertDoesNotExist()
        composeRule.onNodeWithText("https://panel.example/…").assertExists()
    }

    @Test
    fun deletingAsksForConfirmationBeforeInvokingTheAction() {
        var deleteCalls = 0
        setContent(actions = noOpActions(onDeleteConfirmed = { deleteCalls++ }))

        composeRule.onNodeWithText("Delete subscription").performClick()
        composeRule.onNodeWithText("Delete “Provider”?").assertExists()
        deleteCalls shouldBe 0

        composeRule.onNodeWithText("Delete").performClick()
        deleteCalls shouldBe 1
    }

    @Test
    fun tappingUnpinInvokesTheRowAction() {
        var unpinnedKey: String? = null
        setContent(actions = noOpActions(onUnpinRow = { unpinnedKey = it }))

        composeRule.onNodeWithContentDescription("Use provider value").performClick()

        unpinnedKey shouldBe "profile-update-interval"
    }

    private fun noOpActions(
        onDeleteConfirmed: () -> Unit = {},
        onUnpinRow: (String) -> Unit = {},
    ) =
        SubscriptionDetailActions(
            onBack = {},
            onRefreshNow = {},
            onDismissRefreshResult = {},
            onPinRow = { _, _ -> },
            onUnpinRow = onUnpinRow,
            onHwidEnabledChanged = {},
            onUserAgentOverrideChanged = {},
            onDeleteConfirmed = onDeleteConfirmed,
        )

    private fun detailState() =
        SubscriptionDetailState(
            subscriptionId = 7L,
            loading = false,
            groupName = "Provider",
            profileCount = 2,
            redactedUrl = "https://panel.example/…",
            rows = listOf(pinnedInterval),
        )
}
