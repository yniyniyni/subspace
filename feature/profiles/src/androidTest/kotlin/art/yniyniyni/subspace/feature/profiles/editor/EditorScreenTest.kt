// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.editor

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.PassthroughAdvisory
import art.yniyniyni.subspace.core.parser.PassthroughRejection
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import art.yniyniyni.subspace.feature.profiles.R
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * Exercises [EditorContent] directly — the stateless half split out of [EditorScreen] for the
 * same reason [art.yniyniyni.subspace.feature.profiles.add.AddServerSheetContent] is (see that
 * file's own KDoc). No Hilt, [EditorViewModel] or [art.yniyniyni.subspace.feature.profiles.ProfileSource]
 * needed — a plain [EditorState] and recording lambdas drive every branch: loading, not-found,
 * a TYPED profile's field form, and a RAW_JSON profile's read-only view.
 *
 * v2 `createComposeRule` and camelCase names throughout — same DEX-040 constraint
 * [art.yniyniyni.subspace.feature.profiles.add.AddServerSheetTest] documents; no
 * `@Suppress("DEPRECATION")` here or anywhere else in this codebase.
 */
class EditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val typedState =
        EditorState(
            loading = false,
            exists = true,
            id = 1L,
            kind = ProfileKind.TYPED,
            protocol = "vless",
            name = "Typed server",
            groupId = 10L,
            availableGroups = listOf(EditorGroupOption(10L, "Local configs")),
            address = "198.51.100.7",
            port = "443",
            primaryCredential = "11111111-1111-1111-1111-111111111111",
        )

    // Task 10 review, Minor: runsAsWritten = true by default — StoredProfile.runsAsWritten is
    // `kind == RAW_JSON && passthroughRejection == null`, so a real row can never be
    // (runsAsWritten = false, passthroughRejection = null) the way the old default combined
    // them. Tests that need the ineligible state set both fields together explicitly instead.
    private val rawJsonState =
        EditorState(
            loading = false,
            exists = true,
            id = 2L,
            kind = ProfileKind.RAW_JSON,
            protocol = "vless",
            name = "Raw server",
            rawJson = """{  "outbounds" : [ { "protocol":"vless" } ]  }""",
            runsAsWritten = true,
        )

    @Suppress("LongParameterList")
    private fun noOpActions(
        onBack: () -> Unit = {},
        onNameChanged: (String) -> Unit = {},
        onAddressChanged: (String) -> Unit = {},
        onPortChanged: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onConvertRouting: () -> Unit = {},
    ): EditorActions =
        EditorActions(
            onBack = onBack,
            onNameChanged = onNameChanged,
            onGroupChanged = {},
            onAddressChanged = onAddressChanged,
            onPortChanged = onPortChanged,
            onPrimaryCredentialChanged = {},
            onSecondaryCredentialChanged = {},
            onMethodChanged = {},
            onFlowChanged = {},
            onAlterIdChanged = {},
            onVmessSecurityChanged = {},
            onNetworkChanged = {},
            onSecurityKindChanged = {},
            onTlsServerNameChanged = {},
            onTlsFingerprintChanged = {},
            onTlsAllowInsecureChanged = {},
            onRealityServerNameChanged = {},
            onRealityPublicKeyChanged = {},
            onRealityShortIdChanged = {},
            onRealityFingerprintChanged = {},
            onRealitySpiderXChanged = {},
            onWsPathChanged = {},
            onGrpcServiceNameChanged = {},
            onXhttpPathChanged = {},
            onXhttpHostChanged = {},
            onXhttpModeChanged = {},
            onSave = onSave,
            onConvertRouting = onConvertRouting,
        )

    private fun setContent(
        state: EditorState,
        actions: EditorActions = noOpActions(),
    ) {
        setContent(mutableStateOf(state), actions)
    }

    /**
     * The [MutableState] overload, for the one test that needs the state to *change* after the
     * first assertion — same reason [AddServerSheetSubscriptionTest][
     * art.yniyniyni.subspace.feature.profiles.add.AddServerSheetSubscriptionTest] has one: v2's
     * `createComposeRule` rejects a second `composeRule.setContent` call on the same activity, so
     * a single composition driven by mutable state is how one test observes two states.
     */
    private fun setContent(
        state: MutableState<EditorState>,
        actions: EditorActions = noOpActions(),
    ): MutableState<EditorState> {
        composeRule.setContent {
            SubspaceTheme {
                EditorContent(state = state.value, actions = actions)
            }
        }
        return state
    }

    @Test
    fun theLoadingSpinnerShowsWhileLoadingAndNothingElseDoes() {
        setContent(state = EditorState(loading = true))

        composeRule.onNodeWithTag(EDITOR_LOADING_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun theLoadingSpinnerIsGoneOnceLoaded() {
        setContent(state = typedState)

        composeRule.onNodeWithTag(EDITOR_LOADING_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun anUnknownProfileShowsTheNotFoundMessage() {
        setContent(state = EditorState(loading = false, exists = false))

        composeRule.onNodeWithText("Server not found").assertExists()
    }

    @Test
    fun tappingGoBackOnTheNotFoundScreenInvokesOnBack() {
        var backCalled = false
        setContent(
            state = EditorState(loading = false, exists = false),
            actions = noOpActions(onBack = { backCalled = true }),
        )

        composeRule.onNodeWithText("Go back").performClick()

        backCalled shouldBe true
    }

    @Test
    fun tappingTheBackButtonInvokesOnBack() {
        var backCalled = false
        setContent(state = typedState, actions = noOpActions(onBack = { backCalled = true }))

        composeRule.onNodeWithContentDescription("Back").performClick()

        backCalled shouldBe true
    }

    @Test
    fun aTypedProfileShowsItsNameAddressAndPort() {
        setContent(state = typedState)

        composeRule.onNodeWithText("Typed server").assertExists()
        composeRule.onNodeWithText("198.51.100.7").assertExists()
        composeRule.onNodeWithText("443").assertExists()
    }

    @Test
    fun typingIntoTheNameFieldInvokesOnNameChanged() {
        var changed: String? = null
        setContent(state = typedState, actions = noOpActions(onNameChanged = { changed = it }))

        composeRule.onNodeWithText("Typed server").performTextInput("x")

        // performTextInput inserts at cursor position 0 (no prior focus/selection), so the
        // new character lands at the start, not the end — same behaviour, same assertion
        // shape AddServerSheetTest's own typing test relies on (its field starts empty, so
        // the position never showed).
        changed shouldBe "xTyped server"
    }

    @Test
    fun typingIntoTheAddressFieldInvokesOnAddressChanged() {
        var changed: String? = null
        setContent(state = typedState, actions = noOpActions(onAddressChanged = { changed = it }))

        composeRule.onNodeWithText("198.51.100.7").performTextInput("9")

        changed shouldBe "9198.51.100.7"
    }

    @Test
    fun tappingSaveInvokesOnSave() {
        var saveCalled = false
        setContent(state = typedState, actions = noOpActions(onSave = { saveCalled = true }))

        composeRule.onNodeWithText("Save").performClick()

        saveCalled shouldBe true
    }

    @Test
    fun aPortValidationErrorIsShown() {
        val invalid =
            typedState.copy(
                port = "70000",
                errors = mapOf(EditorFieldKey.Port to FailureDetail.Range(DetailField.Port, 1, 65_535, 70_000)),
            )
        setContent(state = invalid)

        composeRule.onNodeWithText("Port must be from 1 to 65535 (actual: 70000)").assertExists()
    }

    @Test
    fun aRawJsonProfileShowsItsBytesReadOnly() {
        setContent(state = rawJsonState)

        composeRule.onNodeWithTag(EDITOR_RAW_JSON_TEST_TAG).assertExists()
        composeRule.onNodeWithText(rawJsonState.rawJson!!).assertExists()
    }

    @Test
    fun aRawJsonProfileHasNoAddressOrPortField() {
        // §6: RAW_JSON is read-only plus rename — no field editor at all, so
        // neither the address nor the port from the typed projection appears
        // as an editable field (there is none of either state on rawJsonState
        // in the first place, but this also pins that TypedFields is not
        // rendered for this kind).
        setContent(state = rawJsonState)

        composeRule.onNodeWithText("Address").assertDoesNotExist()
        composeRule.onNodeWithText("Port").assertDoesNotExist()
    }

    @Test
    fun aRawJsonProfilesNameFieldIsStillEditable() {
        var changed: String? = null
        setContent(state = rawJsonState, actions = noOpActions(onNameChanged = { changed = it }))

        composeRule.onNodeWithText("Raw server").performTextInput("x")

        changed shouldBe "xRaw server"
    }

    @Test
    fun aRawJsonProfileShowsTheHonestyNotice() {
        setContent(state = rawJsonState)

        composeRule.onNodeWithText(
            "This server was added from a pasted config file, and Subspace runs that file as " +
                "written. Only the name can be changed — editing the text would risk breaking a " +
                "config the app cannot re-derive.",
        ).assertExists()
    }

    // M7: passthrough execution lands, so the editor no longer claims this config's own
    // routing and dns blocks go unused.
    @Test
    fun aRawJsonProfileNoLongerSaysItsRoutingIsUnapplied() {
        setContent(state = rawJsonState.copy(runsAsWritten = true))

        composeRule.onNodeWithText(
            context.getString(R.string.editor_raw_json_notice),
        ).assertExists()
        // Symmetric with anIneligibleRawProfileSaysWhichCheckItFailed's own assertions below:
        // an eligible row shows the "runs as written" claim and never the neutral read-only
        // notice that replaces it when ineligible.
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_read_only_notice)).assertDoesNotExist()
    }

    @Test
    fun theOverrideWarningAppearsOnlyWhenRoutingWouldReplaceTheConfig() {
        val state =
            setContent(
                mutableStateOf(rawJsonState.copy(runsAsWritten = true, routingOverridesThisConfig = true)),
            )
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_override_warning)).assertExists()

        state.value = rawJsonState.copy(runsAsWritten = true, routingOverridesThisConfig = false)
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_override_warning)).assertDoesNotExist()
    }

    // Final review C1/I2: an eligible row whose routing/DNS is overridden used to render both
    // editor_raw_json_notice ("Subspace runs that file as written") and
    // editor_raw_json_override_warning ("so it does not run as written") at once — no existing
    // case asserted both flags together against the top notice. This one does.
    @Test
    fun aRawJsonProfileWithRoutingOverrideDoesNotClaimToRunAsWritten() {
        setContent(
            state = rawJsonState.copy(runsAsWritten = true, routingOverridesThisConfig = true),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_notice)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_read_only_notice)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_override_warning)).assertExists()
    }

    @Test
    fun anIneligibleRawProfileSaysWhichCheckItFailed() {
        setContent(
            state = rawJsonState.copy(
                runsAsWritten = false,
                passthroughRejection = PassthroughRejection.SeveralServers,
            ),
        )

        composeRule.onNodeWithText(
            context.getString(R.string.editor_raw_json_rejected_several_servers),
        ).assertExists()
        // Task 10 review, Critical 1: the "runs as written" claim and a rejection string must
        // never render together — an ineligible row is exactly the state that used to show both.
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_notice)).assertDoesNotExist()
        // The neutral read-only notice takes its place — this is not just "the claim is gone",
        // the row still needs the "only the name is editable" explanation.
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_read_only_notice)).assertExists()
    }

    // Task 10 review, Minor: the exhaustive `when` in EditorScreen.kt's messageRes() catches a
    // *missing* branch for a future fifth PassthroughRejection member, but not a mis-mapping
    // between two existing ones — CoreRejected in particular had zero rendering before this
    // task, which is exactly the kind of gap a compiler check alone would not have caught here
    // either. One assertion per remaining member pins each string to its own rejection.
    @Test
    fun anIneligibleRawProfileSaysNotJsonWhenTheFileIsNotJson() {
        setContent(
            state = rawJsonState.copy(runsAsWritten = false, passthroughRejection = PassthroughRejection.NotJson),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_rejected_not_json)).assertExists()
    }

    @Test
    fun anIneligibleRawProfileSaysNoOutboundsWhenTheFileListsNoServers() {
        setContent(
            state = rawJsonState.copy(runsAsWritten = false, passthroughRejection = PassthroughRejection.NoOutbounds),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_rejected_no_outbounds)).assertExists()
    }

    @Test
    fun anIneligibleRawProfileSaysCoreRejectedWhenXrayRefusedIt() {
        setContent(
            state = rawJsonState.copy(runsAsWritten = false, passthroughRejection = PassthroughRejection.CoreRejected),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_rejected_core_rejected)).assertExists()
    }

    // Fix round 1, Important 1: PassthroughAdvisory.messageRes() is a member->string mapping
    // added in one edit, in the same file, rendered by the same composable as
    // PassthroughRejection.messageRes() above — an exhaustive `when` catches a *missing* branch
    // for a future fourth member, but not a *mis-mapping* between three that already exist. Same
    // one-test-per-member shape as anIneligibleRawProfileSaysNotJsonWhenTheFileIsNotJson and its
    // siblings.
    @Test
    fun aRawProfileShowsTheDanglingRoutingReferenceAdvisory() {
        setContent(state = rawJsonState.copy(advisories = listOf(PassthroughAdvisory.DanglingRoutingReference)))

        composeRule.onNodeWithText(
            context.getString(R.string.editor_raw_json_advisory_dangling_reference),
        ).assertExists()
    }

    @Test
    fun aRawProfileShowsTheSniffingCannotServeOwnRulesAdvisory() {
        setContent(state = rawJsonState.copy(advisories = listOf(PassthroughAdvisory.SniffingCannotServeOwnRules)))

        composeRule.onNodeWithText(
            context.getString(R.string.editor_raw_json_advisory_sniffing_cannot_serve_rules),
        ).assertExists()
    }

    @Test
    fun aRawProfileShowsTheFakeDnsWithoutSniffingOverrideAdvisory() {
        setContent(state = rawJsonState.copy(advisories = listOf(PassthroughAdvisory.FakeDnsWithoutSniffingOverride)))

        composeRule.onNodeWithText(
            context.getString(R.string.editor_raw_json_advisory_fakedns_without_sniffing),
        ).assertExists()
    }

    @Test
    fun aRawProfileWithNoAdvisoriesRendersNoneOfTheThreeStrings() {
        setContent(state = rawJsonState.copy(advisories = emptyList()))

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_advisory_dangling_reference))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_advisory_sniffing_cannot_serve_rules))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_advisory_fakedns_without_sniffing))
            .assertDoesNotExist()
    }

    // Task 15: the entry point that makes Task 13's convertXrayRouting and Task 14's
    // startConversionReview reachable in production — a config whose own routing rules the
    // conversion can carry offers to keep them.
    @Test
    fun aRawProfileThatRunsAsWrittenOffersToConvertItsRouting() {
        setContent(
            state = rawJsonState.copy(
                runsAsWritten = true,
                rawJson = """{"routing":{"rules":[{"domain":["a.com"],"outboundTag":"direct"}]},""" +
                    """"outbounds":[{"tag":"direct","protocol":"freedom"}]}""",
                canConvertRouting = true,
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_convert_routing))
            .assertExists()
            .assertIsEnabled()
    }

    @Test
    fun aConfigWithNoRoutingRulesOffersNothingToConvert() {
        setContent(state = rawJsonState.copy(runsAsWritten = true, canConvertRouting = false))

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_convert_routing))
            .assertDoesNotExist()
    }

    // Completeness: canConvertRouting = false must also hide the action on an ineligible
    // (runsAsWritten = false) row — the state EditorViewModel.load() actually produces for a
    // rejected passthrough, since canConvertRouting there is always false whenever runsAsWritten
    // is (see EditorViewModel.canConvertRoutingNow()).
    @Test
    fun anIneligibleRawProfileOffersNothingToConvertEither() {
        setContent(
            state = rawJsonState.copy(
                runsAsWritten = false,
                passthroughRejection = PassthroughRejection.SeveralServers,
                canConvertRouting = false,
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_convert_routing))
            .assertDoesNotExist()
    }

    @Test
    fun tappingConvertRoutingInvokesOnConvertRouting() {
        var convertCalled = false
        setContent(
            state = rawJsonState.copy(runsAsWritten = true, canConvertRouting = true),
            actions = noOpActions(onConvertRouting = { convertCalled = true }),
        )

        composeRule.onNodeWithText(context.getString(R.string.editor_raw_json_convert_routing)).performClick()

        convertCalled shouldBe true
    }
}
