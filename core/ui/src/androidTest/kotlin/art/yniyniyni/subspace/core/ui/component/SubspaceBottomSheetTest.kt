// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso
import art.yniyniyni.subspace.core.ui.R
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// camelCase throughout — same DEX-040 constraint as ConnectControlTest.kt and
// FloatingNavigationBarTest.kt.
class SubspaceBottomSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    // A test-only tag identifying the caller-supplied content slot. Not part
    // of SubspaceBottomSheet's own API — the component never sees or sets
    // this tag itself, only the test's own `content` lambda does.
    private val contentTag = "sheet-content"

    @Test
    fun contentIsPresentWhenOpen() {
        composeRule.setContent {
            SubspaceTheme {
                SubspaceBottomSheet(
                    open = true,
                    titleRes = R.string.bottom_sheet_test_title,
                    onDismiss = {},
                ) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNodeWithTag(contentTag).assertExists()
    }

    @Test
    fun contentIsAbsentWhenClosed() {
        composeRule.setContent {
            SubspaceTheme {
                SubspaceBottomSheet(
                    open = false,
                    titleRes = R.string.bottom_sheet_test_title,
                    onDismiss = {},
                ) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNodeWithTag(contentTag).assertDoesNotExist()
    }

    // Beyond the two states in isolation above: proves the SAME `open`
    // parameter toggling at runtime (not two separate compositions) drives
    // the sheet both into and out of existence — the actual shape of how a
    // caller uses this component (a mutable UI state, not a constant).
    @Test
    fun togglingOpenAtRuntimeShowsAndHidesContentInBothDirections() {
        val openState = mutableStateOf(true)
        composeRule.setContent {
            SubspaceTheme {
                SubspaceBottomSheet(
                    open = openState.value,
                    titleRes = R.string.bottom_sheet_test_title,
                    onDismiss = {},
                ) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNodeWithTag(contentTag).assertExists()

        composeRule.runOnIdle { openState.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(contentTag).assertDoesNotExist()

        composeRule.runOnIdle { openState.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(contentTag).assertExists()
    }

    // The scrim and swipe-down dismiss paths are Material 3's own Dialog
    // plumbing, not a Compose semantics node this test can address directly.
    // The system back button is: ModalBottomSheet's underlying dialog
    // registers dismiss-on-back by default, and it is a path a real user (and
    // TalkBack, and the system back gesture) can always trigger, so it is the
    // representative "a caller-reachable dismissal actually calls onDismiss"
    // case.
    @Test
    fun dismissCallbackFiresOnSystemBackPress() {
        var dismissed = false
        composeRule.setContent {
            SubspaceTheme {
                SubspaceBottomSheet(
                    open = true,
                    titleRes = R.string.bottom_sheet_test_title,
                    onDismiss = { dismissed = true },
                ) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
        dismissed shouldBe true
    }

    @Test
    fun titleIsExposedAsAnAccessibilityHeading() {
        composeRule.setContent {
            SubspaceTheme {
                SubspaceBottomSheet(
                    open = true,
                    titleRes = R.string.bottom_sheet_test_title,
                    onDismiss = {},
                ) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.onNode(isHeading() and hasText("Sheet Title")).assertExists()
    }
}
