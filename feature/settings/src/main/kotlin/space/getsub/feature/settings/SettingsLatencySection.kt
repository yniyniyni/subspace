// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import space.getsub.core.model.PingMode
import space.getsub.core.ui.component.SettingRow

/**
 * Mode, check URL, timeout, and the two ping-on-launch switches.
 *
 * Two modes only, and the labels say why in the strings file: `icmp` needs root
 * (Appendix D), and a GET-based `proxy` mode is not implementable against
 * libXray v26.7.11 — hence "Proxy (HEAD)" rather than a bare "Proxy", which
 * would promise a request this build cannot make.
 */
@Composable
internal fun LatencyControls(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PingMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.pingMode == mode,
                    onClick = { actions.onPingModeChanged(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PingMode.entries.size),
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }

        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_ping_mode),
            supportingText = stringResource(state.pingMode.summaryRes()),
        )

        // A plain field, not a picker: this is user-owned. `check-url-via-proxy`
        // stays unwired precisely because a provider choosing what this device
        // fetches through the tunnel is §A.1's threat model, and honouring it
        // would need the explicit-confirmation step that milestone did not build.
        // Edited locally and committed on focus loss, rather than written on every
        // keystroke. The repository maps a stored blank back to the default, so a
        // per-keystroke write meant select-all-delete instantly repopulated the
        // field and the user could never type a replacement from scratch. It also
        // put a Room write plus a full flow round-trip between every character.
        var draft by remember(state.pingCheckUrl) { mutableStateOf(state.pingCheckUrl) }
        OutlinedTextField(
            value = draft,
            onValueChange = { typed -> draft = typed },
            label = { Text(stringResource(R.string.settings_ping_check_url)) },
            supportingText = { Text(stringResource(R.string.settings_ping_check_url_summary)) },
            singleLine = true,
            modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (!focus.hasFocus && draft != state.pingCheckUrl) {
                        actions.onPingCheckUrlChanged(draft)
                    }
                },
        )

        TimeoutStepper(
            seconds = state.pingTimeoutSeconds,
            onChanged = actions.onPingTimeoutChanged,
        )

        LaunchTestingSwitches(state = state, actions = actions)
    }
}

@Composable
private fun TimeoutStepper(
    seconds: Int,
    onChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        icon = Icons.Default.Info,
        label = stringResource(R.string.settings_ping_timeout),
        supportingText = stringResource(R.string.settings_ping_timeout_value, seconds),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The repository clamps to 1–15 on read as well as on write, so
                // these cannot walk the value out of range even if held down.
                IconButton(onClick = { onChanged(seconds - 1) }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.settings_ping_timeout_decrease),
                    )
                }
                IconButton(onClick = { onChanged(seconds + 1) }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.settings_ping_timeout_increase),
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun LaunchTestingSwitches(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val launchTitle = stringResource(R.string.settings_ping_on_launch)
        SettingRow(
            icon = Icons.Default.Refresh,
            label = launchTitle,
            supportingText = stringResource(R.string.settings_ping_on_launch_summary),
            trailing = {
                Switch(
                    checked = state.pingOnLaunch,
                    onCheckedChange = actions.onPingOnLaunchChanged,
                    modifier = Modifier.semantics { contentDescription = launchTitle },
                )
            },
        )

        val meteredTitle = stringResource(R.string.settings_ping_on_launch_metered)
        SettingRow(
            icon = Icons.Default.Refresh,
            label = meteredTitle,
            supportingText = stringResource(R.string.settings_ping_on_launch_metered_summary),
            trailing = {
                Switch(
                    checked = state.pingOnLaunchMetered,
                    // Only meaningful when the launch run is on at all.
                    enabled = state.pingOnLaunch,
                    onCheckedChange = actions.onPingOnLaunchMeteredChanged,
                    modifier = Modifier.semantics { contentDescription = meteredTitle },
                )
            },
        )
    }
}

private fun PingMode.labelRes(): Int =
    when (this) {
        PingMode.TCP -> R.string.settings_ping_mode_tcp
        PingMode.PROXY_HEAD -> R.string.settings_ping_mode_proxy_head
    }

private fun PingMode.summaryRes(): Int =
    when (this) {
        PingMode.TCP -> R.string.settings_ping_mode_tcp_summary
        PingMode.PROXY_HEAD -> R.string.settings_ping_mode_proxy_head_summary
    }
