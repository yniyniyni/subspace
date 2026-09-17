// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import space.getsub.core.ui.component.SettingRow

/**
 * Spec §7, §7.1, §7.2: the settings that keep the tunnel running under the platform —
 * always-on VPN (a deep link, never a switch: see [onOpenVpnSettings]'s own KDoc below),
 * connect-at-startup, the fail-closed kill switch, and battery-optimisation exemption.
 *
 * The two switch rows pass `labelCarriesSemantics = true` to [SettingRow] — see that
 * parameter's own KDoc for why a plain trailing `Switch` is not enough to let a test say "the
 * switch belonging to *this* label".
 *
 * @param onOpenVpnSettings launches `Settings.ACTION_VPN_SETTINGS`. This app cannot enable
 *   always-on VPN or its "Block connections without VPN" lockdown itself — both are system
 *   settings only the user (or a device policy owner) can set. Rendering this as a switch would
 *   claim ownership of state this app does not have; a row that explains the setting and opens the
 *   real control is the only honest affordance.
 * @param onOpenBatterySettings launches `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 *   (the settings *list*, not a direct request — see [SettingsScreen]'s wiring for why).
 */
// state plus one callback per row action, plus the idiomatic modifier slot every composable in
// this module carries — the same six-orthogonal-parameters shape SettingRow's own
// LongParameterList suppression documents for the identical count.
@Suppress("LongParameterList")
@Composable
internal fun SettingsTunnelSection(
    state: SettingsState,
    onBootAutostartChange: (Boolean) -> Unit,
    onFailClosedChange: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_tunnel_title),
            style = MaterialTheme.typography.titleMedium,
        )

        SettingRow(
            icon = Icons.Default.Lock,
            label = stringResource(R.string.settings_always_on_title),
            supportingText = stringResource(R.string.settings_always_on_summary),
            onClick = onOpenVpnSettings,
            // Not cosmetic, and not copied from the row below by habit: without
            // this the row is not a semantics *ancestor* of its own trailing
            // content, so SettingsTunnelSectionTest's "no Switch under this
            // label" assertion has no ancestor to match and can never fail —
            // it passed against an injected Switch. See SettingRow's KDoc.
            labelCarriesSemantics = true,
            trailing = {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )

        val bootAutostartTitle = stringResource(R.string.settings_boot_autostart_title)
        SettingRow(
            icon = Icons.Default.PlayArrow,
            label = bootAutostartTitle,
            supportingText = stringResource(R.string.settings_boot_autostart_summary),
            labelCarriesSemantics = true,
            trailing = {
                Switch(
                    checked = state.bootAutostart,
                    onCheckedChange = onBootAutostartChange,
                    modifier = Modifier.semantics { contentDescription = bootAutostartTitle },
                )
            },
        )

        val failClosedTitle = stringResource(R.string.settings_fail_closed_title)
        SettingRow(
            icon = Icons.Default.Warning,
            label = failClosedTitle,
            supportingText = stringResource(R.string.settings_fail_closed_summary),
            labelCarriesSemantics = true,
            trailing = {
                Switch(
                    checked = state.failClosed,
                    onCheckedChange = onFailClosedChange,
                    modifier = Modifier.semantics { contentDescription = failClosedTitle },
                )
            },
        )

        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_battery_title),
            supportingText = stringResource(R.string.settings_battery_summary),
            onClick = onOpenBatterySettings,
            trailing = {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )
    }
}
