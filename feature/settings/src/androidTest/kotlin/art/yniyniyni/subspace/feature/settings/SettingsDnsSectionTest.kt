// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import org.junit.Rule
import org.junit.Test

class SettingsDnsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun douShowsResolverWithoutBootstrap() {
        setContent(SettingsState(dnsTransport = DnsTransport.DOU, dnsAddress = ""))

        composeRule.onNodeWithText("Resolver").assertIsDisplayed()
        composeRule.onNodeWithText("Bootstrap IP").assertDoesNotExist()
    }

    @Test
    fun dohShowsBootstrap() {
        setContent(
            SettingsState(
                dnsTransport = DnsTransport.DOH,
                dnsAddress = "",
            ),
        )

        composeRule.onNodeWithText("Bootstrap IP").assertIsDisplayed()
    }

    @Test
    fun activeProfileOverrideShowsNotice() {
        setContent(SettingsState(dnsOverriddenByProfile = true))

        composeRule.onNodeWithText("The active routing profile sets its own DNS").assertIsDisplayed()
    }

    private fun setContent(state: SettingsState) {
        composeRule.setContent {
            SubspaceTheme {
                SettingsDnsSection(state = state, actions = actions)
            }
        }
    }

    private val actions =
        SettingsActions(
            onThemeChanged = { _: ThemePreference -> },
            onHwidEnabledChanged = {},
            onPingModeChanged = { _: PingMode -> },
            onPingCheckUrlChanged = {},
            onPingTimeoutChanged = {},
            onPingOnLaunchChanged = {},
            onPingOnLaunchMeteredChanged = {},
            onDnsTransportChanged = { _: DnsTransport -> },
            onDnsAddressChanged = {},
            onDnsBootstrapIpChanged = {},
            onGeoSourceSelected = {},
            onGeoUpdateNow = {},
            onAddCustomGeoSource = { _: String, _: String, _: GeoDataKind -> },
            onGeoRefreshOnMeteredChanged = {},
            onRemoveCustomGeoSource = {},
        )
}
