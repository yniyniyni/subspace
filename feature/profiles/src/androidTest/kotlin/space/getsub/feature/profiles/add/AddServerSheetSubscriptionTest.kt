// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.add

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.profiles.R

/**
 * Task 13: the fifth [AddServerSheetContent] route, "From subscription URL"
 * — M3's plan Part 2 line 715 deferred it to M4.
 *
 * Same harness as [AddServerSheetTest]: [AddServerSheetContent] directly,
 * with a plain [ImportState] and recording lambdas, no Hilt-backed
 * [ImportViewModel]. [ImportViewModel.addSubscription]'s own behaviour (the
 * URL validation, the add-then-sync call, deleting on a failed first sync)
 * is not this file's concern — it has no real [SubscriptionRepository][
 * space.getsub.core.data.SubscriptionRepository] or
 * [SubscriptionSyncer][space.getsub.core.data.sync.SubscriptionSyncer]
 * to exercise it against (both have `internal` constructors scoped to
 * `:core:data`, the same reason [ProfileSource][space.getsub.feature.profiles.ProfileSource]
 * exists as a fakeable seam for JVM tests). What this file owns: that the
 * entry exists, that the URL field's own text reaches
 * [ImportActions.onAddSubscription] verbatim, and that every
 * [SyncResult][space.getsub.core.data.sync.SyncResult] outcome
 * [ImportState.subscriptionResult] can carry renders its own distinct
 * string — [SubscriptionImportTest] proves the mapping to a resource id is
 * exhaustive and distinct at the JVM layer; this proves each resource id
 * actually resolves to readable, distinguishable English.
 *
 * v2 `createComposeRule` and camelCase names throughout — same DEX-040
 * constraint [AddServerSheetTest]'s own file-level KDoc documents.
 */
class AddServerSheetSubscriptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ImportState = ImportState(),
        onAddSubscription: (String) -> Unit = {},
    ) = setContent(mutableStateOf(state), onAddSubscription)

    /**
     * The [MutableState] overload, for the one test that needs the state to *change* after the
     * user has typed — see [addSubscriptionButtonIsDisabledWhileBusyEvenWithAUrlTyped].
     */
    private fun setContent(
        state: MutableState<ImportState>,
        onAddSubscription: (String) -> Unit = {},
    ): MutableState<ImportState> {
        composeRule.setContent {
            SubspaceTheme {
                AddServerSheetContent(
                    state = state.value,
                    actions =
                    ImportActions(
                        onInputChanged = {},
                        onImportClick = {},
                        onImportFromFileClick = {},
                        onScanClick = {},
                        onAddSubscription = onAddSubscription,
                    ),
                )
            }
        }
        return state
    }

    @Test
    fun theSubscriptionEntryIsPresent() {
        setContent()

        composeRule.onNodeWithText("From subscription URL").assertExists()
        composeRule.onNodeWithTag(ADD_SUBSCRIPTION_FIELD_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Add subscription").assertExists()
    }

    @Test
    fun addSubscriptionButtonIsDisabledWhenTheUrlFieldIsBlank() {
        setContent()

        composeRule.onNodeWithText("Add subscription").assertIsNotEnabled()
    }

    @Test
    fun addSubscriptionButtonIsEnabledOnceAUrlIsTyped() {
        setContent()

        composeRule.onNodeWithTag(ADD_SUBSCRIPTION_FIELD_TEST_TAG).performTextInput("https://example.com/sub")

        composeRule.onNodeWithText("Add subscription").assertIsEnabled()
    }

    @Test
    fun addSubscriptionButtonIsDisabledWhileBusyEvenWithAUrlTyped() {
        // The URL field is itself `enabled = !busy`, so the text has to be typed before busy flips —
        // which is also the only way this state is reached in production: type a URL, press Add,
        // addSubscription() sets busy while the first sync runs. Starting at busy = true and typing
        // would fail on a disabled field and prove nothing about the button.
        val state = setContent(mutableStateOf(ImportState()))

        composeRule.onNodeWithTag(ADD_SUBSCRIPTION_FIELD_TEST_TAG).performTextInput("https://example.com/sub")
        composeRule.onNodeWithText("Add subscription").assertIsEnabled()

        state.value = ImportState(busy = true)

        // busy dominates the `!busy && url.isNotBlank()` guard even though the URL is still there.
        composeRule.onNodeWithText("Add subscription").assertIsNotEnabled()
    }

    @Test
    fun enteringAUrlAndConfirmingInvokesTheCallbackWithIt() {
        var added: String? = null
        setContent(onAddSubscription = { added = it })

        composeRule.onNodeWithTag(ADD_SUBSCRIPTION_FIELD_TEST_TAG).performTextInput("https://example.com/sub")
        composeRule.onNodeWithText("Add subscription").performClick()

        added shouldBe "https://example.com/sub"
    }

    @Test
    fun noSubscriptionResultMessageWhenNothingHasBeenAttempted() {
        setContent(state = ImportState(subscriptionResult = null))

        composeRule.onNodeWithText("Added 1 server").assertDoesNotExist()
    }

    // Success (SyncResult.Synced): one distinct message per plural form,
    // proving the <plurals> resource itself resolves, not just that
    // toUserMessage() picked the right id (SubscriptionImportTest's job).

    @Test
    fun aSuccessfulAddOfOneServerUsesTheSingularForm() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.plurals.subscription_added, 1)))

        composeRule.onNodeWithText("Added 1 server").assertExists()
    }

    @Test
    fun aSuccessfulAddOfSeveralServersUsesThePluralForm() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.plurals.subscription_added, 12)))

        composeRule.onNodeWithText("Added 12 servers").assertExists()
    }

    // Every failure string is distinct — the Compose-layer half of
    // SubscriptionImportTest's "every SubscriptionSyncFailure maps to a
    // distinct string" JVM assertion.

    @Test
    fun hwidRequiredRendersTheDeviceIdString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_hwid_required)))

        composeRule.onNodeWithText("This subscription requires a device ID. Enable it in Settings.").assertExists()
    }

    @Test
    fun deviceLimitReachedRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_device_limit)))

        composeRule.onNodeWithText("Device limit reached. Remove a device in your account.").assertExists()
    }

    @Test
    fun notFoundRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_not_found)))

        composeRule.onNodeWithText("Subscription not found. Check the URL.").assertExists()
    }

    @Test
    fun unreachableRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_unreachable)))

        composeRule.onNodeWithText("Could not reach the server.").assertExists()
    }

    @Test
    fun timedOutRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_timed_out)))

        composeRule.onNodeWithText("The server did not respond in time.").assertExists()
    }

    @Test
    fun tlsFailureRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_tls)))

        composeRule.onNodeWithText("Could not establish a secure connection.").assertExists()
    }

    @Test
    fun clientErrorRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_client)))

        composeRule.onNodeWithText("The server rejected the request.").assertExists()
    }

    @Test
    fun serverErrorRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_server)))

        composeRule.onNodeWithText("The server reported an error.").assertExists()
    }

    @Test
    fun noServersRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_no_servers)))

        composeRule.onNodeWithText("No servers in this subscription.").assertExists()
    }
}
