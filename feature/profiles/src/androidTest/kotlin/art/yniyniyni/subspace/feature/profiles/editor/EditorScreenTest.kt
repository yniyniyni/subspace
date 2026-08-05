// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.editor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
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

    private val rawJsonState =
        EditorState(
            loading = false,
            exists = true,
            id = 2L,
            kind = ProfileKind.RAW_JSON,
            protocol = "vless",
            name = "Raw server",
            rawJson = """{  "outbounds" : [ { "protocol":"vless" } ]  }""",
        )

    @Suppress("LongParameterList")
    private fun noOpActions(
        onBack: () -> Unit = {},
        onNameChanged: (String) -> Unit = {},
        onAddressChanged: (String) -> Unit = {},
        onPortChanged: (String) -> Unit = {},
        onSave: () -> Unit = {},
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
            onSave = onSave,
        )

    private fun setContent(
        state: EditorState,
        actions: EditorActions = noOpActions(),
    ) {
        composeRule.setContent {
            SubspaceTheme {
                EditorContent(state = state, actions = actions)
            }
        }
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
            "This server was added from a pasted config file. Subspace runs a parsed copy of it, not " +
                "the file itself, so editing the text here would show you something the app does not " +
                "actually use yet. Only the name can be changed — everything else is shown for reference.",
        ).assertExists()
    }
}
