// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

/**
 * Spec §7, §7.1, §7.2: covers the tunnel section — always-on VPN as a link (never a switch,
 * since this app cannot own that system state) and the boot-autostart/fail-closed switches.
 */
class SettingsTunnelSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun alwaysOnIsALinkAndNotASwitch() {
        var opened = false
        composeRule.setContent {
            SubspaceTheme {
                SettingsTunnelSection(
                    state = tunnelState(),
                    onBootAutostartChange = {},
                    onFailClosedChange = {},
                    onOpenVpnSettings = { opened = true },
                    onOpenBatterySettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Always-on VPN").performClick()

        // Spec §7.1: the app cannot enable always-on or its lockdown; both are
        // system settings. A switch here would lie about who owns the state.
        opened shouldBe true

        // The click assertion above only proves the row is clickable — a regression that kept
        // it clickable while also adding a Switch to the row would still pass it. Assert the
        // absence directly: no toggle may exist anywhere under this row's label.
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Always-on VPN")))
            .assertDoesNotExist()
    }

    @Test
    fun failClosedRendersItsStoredValue() {
        composeRule.setContent {
            SubspaceTheme {
                SettingsTunnelSection(
                    state = tunnelState(failClosed = true),
                    onBootAutostartChange = {},
                    onFailClosedChange = {},
                    onOpenVpnSettings = {},
                    onOpenBatterySettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Block traffic when disconnected").assertIsDisplayed()
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Block traffic when disconnected")))
            .assertIsOn()
    }

    @Test
    fun togglingBootAutostartReportsUpward() {
        var toggled: Boolean? = null
        composeRule.setContent {
            SubspaceTheme {
                SettingsTunnelSection(
                    state = tunnelState(bootAutostart = false),
                    onBootAutostartChange = { toggled = it },
                    onFailClosedChange = {},
                    onOpenVpnSettings = {},
                    onOpenBatterySettings = {},
                )
            }
        }

        composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Connect at startup"))).performClick()

        toggled shouldBe true
    }

    private fun tunnelState(bootAutostart: Boolean = false, failClosed: Boolean = true) =
        SettingsState(bootAutostart = bootAutostart, failClosed = failClosed)
}
