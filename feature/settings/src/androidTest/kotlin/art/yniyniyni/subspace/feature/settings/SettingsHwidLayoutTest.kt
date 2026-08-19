// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * M4's device run: the HWID was passed to `SettingRow`'s `trailing` slot, which is measured at its
 * intrinsic width before the label column's `weight(1f)` is resolved. Forty-three unbreakable
 * monospace characters therefore claimed the whole row and squeezed the label to roughly one
 * character wide, so "Your device ID" rendered as a vertical stack of single letters.
 *
 * No JVM test could catch this — [SettingsHwidTest] asserts the state carries the value, and it
 * did. The defect was purely in layout, which is why it took a screenshot to find.
 *
 * camelCase throughout, same DEX-040 constraint the other instrumented tests in this repo document.
 */
class SettingsHwidLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val hwid = "K7dQ2mX9pL4nR8vT1yU3wA6sD5fG0hJ2kZ9xC4vB7nM"

    private fun setContent() {
        composeRule.setContent {
            SubspaceTheme {
                SettingsScreenContent(
                    state = SettingsState(hwid = hwid, hwidEnabled = true),
                    actions =
                    SettingsActions(
                        onThemeChanged = {},
                        onHwidEnabledChanged = {},
                        onPingModeChanged = {},
                        onPingCheckUrlChanged = {},
                        onPingTimeoutChanged = {},
                        onPingOnLaunchChanged = {},
                        onPingOnLaunchMeteredChanged = {},
                        onGeoSourceSelected = {},
                        onGeoUpdateNow = {},
                        onAddCustomGeoSource = { _, _, _ -> },
                        onGeoRefreshOnMeteredChanged = {},
                        onRemoveCustomGeoSource = {},
                    ),
                    onNavigateToRouting = {},
                    onNavigateToPerApp = {},
                )
            }
        }
    }

    @Test
    fun theHwidLabelIsNotSqueezedIntoAColumnOfSingleCharacters() {
        setContent()

        // "Wider than it is tall" is the direct expression of the defect and needs no calibrated
        // dp threshold: laid out normally the label is one line — roughly 110dp wide by 20dp tall.
        // Squeezed to a single character per line it inverts completely, a few dp wide and
        // hundreds tall. Asserting an absolute width would instead pin the glyph run's own size.
        val label = composeRule.onNodeWithText("Your device ID").fetchSemanticsNode()

        (label.size.width > label.size.height) shouldBe true
    }

    @Test
    fun theWholeHwidIsShownAndNotTruncated() {
        setContent()

        composeRule.onNodeWithText(hwid).assertIsDisplayed()
    }

    @Test
    fun theSendDeviceIdToggleIsStillPresent() {
        setContent()

        composeRule.onNodeWithText("Send device ID").assertIsDisplayed()
    }
}
