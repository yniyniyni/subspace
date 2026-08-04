// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.ParseFailureReason
import art.yniyniyni.subspace.core.parser.parseFailure
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * Fix round 1, finding 1: [AddServerSheet] shipped with no instrumented
 * coverage at all — flagged as a known gap in its own task report
 * ("Handover to Task 20") on the same "GroupCard had none either" precedent
 * [art.yniyniyni.subspace.core.ui.component.GroupCardTest]'s own header
 * records, and review upheld it as a real gap here too. The stakes are
 * higher than GroupCard's: this sheet is the only place untrusted input — a
 * paste or a picked file — reaches [art.yniyniyni.subspace.core.parser.SubscriptionParser]
 * and then the database.
 *
 * Exercises [AddServerSheetContent] directly — the stateless half split out
 * of [AddServerSheetBody] for exactly this reason (see its own KDoc) — with
 * a plain [ImportState] and recording lambdas. No Hilt-backed
 * [ImportViewModel] or [art.yniyniyni.subspace.feature.profiles.ProfileSource]
 * is needed to drive the paste field, the Import/Import-from-file/Scan
 * buttons' `enabled` gating, the busy indicator, the file-read-failure
 * message, or the failure list's expand/collapse — [AddServerSheetContent]
 * is a pure function of [ImportState] and four callbacks. Task 20 fix round
 * 1 adds the "Scan QR code" button's three assertions (below, mirroring the
 * file button's own) — this is deliberately as far as this file's coverage
 * of the QR entry point goes: what happens after the tap (real navigation to
 * `QrScan`, sharing the same [ImportViewModel] instance across the
 * `NavBackStackEntry` boundary) needs a real `NavController` and Hilt, which
 * this Hilt-free harness does not have — see `SubspaceNavHostTest`'s own
 * file-level KDoc for that boundary and why it is not closed with a new
 * `@HiltAndroidTest` harness in this round.
 *
 * v2 `createComposeRule` and camelCase names throughout — same DEX-040
 * constraint [art.yniyniyni.subspace.core.ui.component.GroupCardTest] and
 * [art.yniyniyni.subspace.core.ui.component.ConnectControlTest] document; no
 * `@Suppress("DEPRECATION")` here or anywhere else in this codebase.
 */
class AddServerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ImportState = ImportState(),
        onInputChanged: (String) -> Unit = {},
        onImportClick: () -> Unit = {},
        onImportFromFileClick: () -> Unit = {},
        onScanClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            SubspaceTheme {
                AddServerSheetContent(
                    state = state,
                    actions =
                    ImportActions(
                        onInputChanged = onInputChanged,
                        onImportClick = onImportClick,
                        onImportFromFileClick = onImportFromFileClick,
                        onScanClick = onScanClick,
                    ),
                )
            }
        }
    }

    @Test
    fun thePasteFieldShowsTheCurrentInputFromState() {
        setContent(state = ImportState(input = "vless://11111111-1111-1111-1111-111111111111@198.51.100.7:443"))
        composeRule.onNodeWithText("vless://11111111-1111-1111-1111-111111111111@198.51.100.7:443").assertExists()
    }

    @Test
    fun typingIntoThePasteFieldInvokesOnInputChanged() {
        var changed: String? = null
        setContent(onInputChanged = { changed = it })

        composeRule.onNodeWithTag(IMPORT_PASTE_FIELD_TEST_TAG).performTextInput("a")

        changed shouldBe "a"
    }

    @Test
    fun importButtonIsDisabledWhenInputIsBlank() {
        setContent(state = ImportState(input = ""))
        composeRule.onNodeWithText("Import").assertIsNotEnabled()
    }

    @Test
    fun importButtonIsEnabledWithNonBlankInputWhileNotBusy() {
        setContent(state = ImportState(input = "vless://x", busy = false))
        composeRule.onNodeWithText("Import").assertIsEnabled()
    }

    @Test
    fun importButtonIsDisabledWhileBusyEvenWithInput() {
        // AddServerSheet.kt:122's gate is `!state.busy && state.input.isNotBlank()`
        // — both halves matter independently; this pins the busy half.
        setContent(state = ImportState(input = "vless://x", busy = true))
        composeRule.onNodeWithText("Import").assertIsNotEnabled()
    }

    @Test
    fun tappingImportInvokesOnImportClick() {
        var clicked = false
        setContent(state = ImportState(input = "vless://x"), onImportClick = { clicked = true })

        composeRule.onNodeWithText("Import").performClick()

        clicked shouldBe true
    }

    @Test
    fun importFromFileButtonIsEnabledEvenWithBlankInput() {
        // Unlike the paste Import button, the file button's own gate is only
        // ever `!state.busy` — it does not read state.input at all.
        setContent(state = ImportState(input = "", busy = false))
        composeRule.onNodeWithText("Import from file").assertIsEnabled()
    }

    @Test
    fun importFromFileButtonIsDisabledWhileBusy() {
        setContent(state = ImportState(busy = true))
        composeRule.onNodeWithText("Import from file").assertIsNotEnabled()
    }

    @Test
    fun tappingImportFromFileInvokesOnImportFromFileClick() {
        var clicked = false
        setContent(onImportFromFileClick = { clicked = true })

        composeRule.onNodeWithText("Import from file").performClick()

        clicked shouldBe true
    }

    // Task 20 fix round 1: the "Scan QR code" button, mirroring the file
    // button's own three assertions above (enabled with blank input, disabled
    // while busy, tapping invokes its callback) — its gate is the identical
    // `!state.busy` alone, never state.input.
    @Test
    fun scanQrButtonIsEnabledEvenWithBlankInput() {
        setContent(state = ImportState(input = "", busy = false))
        composeRule.onNodeWithText("Scan QR code").assertIsEnabled()
    }

    @Test
    fun scanQrButtonIsDisabledWhileBusy() {
        setContent(state = ImportState(busy = true))
        composeRule.onNodeWithText("Scan QR code").assertIsNotEnabled()
    }

    @Test
    fun tappingScanQrInvokesOnScanClick() {
        var clicked = false
        setContent(onScanClick = { clicked = true })

        composeRule.onNodeWithText("Scan QR code").performClick()

        clicked shouldBe true
    }

    @Test
    fun theBusyIndicatorIsAbsentWhenNotBusy() {
        setContent(state = ImportState(busy = false))
        composeRule.onNodeWithTag(IMPORT_BUSY_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theBusyIndicatorAppearsWhileBusy() {
        setContent(state = ImportState(busy = true))
        composeRule.onNodeWithTag(IMPORT_BUSY_TEST_TAG).assertExists()
    }

    @Test
    fun aFileReadFailureShowsAVisibleMessage() {
        // Fix round 1, finding 2: the sheet must never swallow a file-read
        // failure. This is the UI half of that fix — ImportViewModel's
        // reportFileReadFailure is the state-producing half, covered by its
        // own JVM test.
        setContent(state = ImportState(fileReadFailed = true))
        composeRule.onNodeWithText("Could not read that file").assertExists()
    }

    @Test
    fun noFileReadFailureMessageWhenNothingFailed() {
        setContent(state = ImportState(fileReadFailed = false))
        composeRule.onNodeWithText("Could not read that file").assertDoesNotExist()
    }

    @Test
    fun theFailureListIsCollapsedByDefault() {
        val failures = listOf(parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.None))
        setContent(state = ImportState(completed = true, imported = 0, failures = failures))

        composeRule.onNodeWithText("Entry 1 could not be read").assertDoesNotExist()
    }

    @Test
    fun tappingShowFailuresExpandsTheList() {
        val failures = listOf(parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.None))
        setContent(state = ImportState(completed = true, imported = 0, failures = failures))

        composeRule.onNodeWithText("Show 1 failure").performClick()

        composeRule.onNodeWithText("Entry 1 could not be read").assertExists()
    }

    @Test
    fun tappingHideFailuresCollapsesTheListAgain() {
        val failures = listOf(parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.None))
        setContent(state = ImportState(completed = true, imported = 0, failures = failures))
        composeRule.onNodeWithText("Show 1 failure").performClick()

        composeRule.onNodeWithText("Hide failures").performClick()

        composeRule.onNodeWithText("Entry 1 could not be read").assertDoesNotExist()
    }
}
