// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.DnsValidation

/** The app-level resolver, used while no routing profile supplies effective DNS behavior. */
@Composable
internal fun SettingsDnsSection(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_dns_title),
            style = MaterialTheme.typography.titleMedium,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DnsTransport.entries.forEachIndexed { index, transport ->
                SegmentedButton(
                    selected = state.dnsTransport == transport,
                    onClick = { actions.onDnsTransportChanged(transport) },
                    shape = SegmentedButtonDefaults.itemShape(index, DnsTransport.entries.size),
                    label = { Text(transport.wireValue) },
                )
            }
        }

        val isDoh = state.dnsTransport == DnsTransport.DOH
        DnsAddressField(state = state, actions = actions, isDoh = isDoh)

        if (isDoh) {
            DnsBootstrapField(state = state, actions = actions)
        }

        if (state.dnsOverriddenByProfile) {
            Text(
                text = stringResource(R.string.settings_dns_overridden),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DnsAddressField(
    state: SettingsState,
    actions: SettingsActions,
    isDoh: Boolean,
) {
    var draft by remember(state.dnsAddress) { mutableStateOf(state.dnsAddress) }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> draft = typed },
        label = { Text(stringResource(R.string.settings_dns_address)) },
        isError = draft.isNotBlank() && !isDnsAddressValid(draft, state.dnsTransport),
        supportingText = {
            Text(
                stringResource(
                    if (isDoh) R.string.settings_dns_invalid_https else R.string.settings_dns_invalid_ip,
                ),
            )
        },
        singleLine = true,
        modifier =
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.hasFocus && draft != state.dnsAddress) {
                    actions.onDnsAddressChanged(draft)
                }
            },
    )
}

@Composable
private fun DnsBootstrapField(
    state: SettingsState,
    actions: SettingsActions,
) {
    var draft by remember(state.dnsBootstrapIp) { mutableStateOf(state.dnsBootstrapIp) }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> draft = typed },
        label = { Text(stringResource(R.string.settings_dns_bootstrap)) },
        isError = draft.isNotBlank() && !DnsValidation.isAddressLiteral(draft),
        supportingText = { Text(stringResource(R.string.settings_dns_bootstrap_summary)) },
        singleLine = true,
        modifier =
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.hasFocus && draft != state.dnsBootstrapIp) {
                    actions.onDnsBootstrapIpChanged(draft)
                }
            },
    )
}

/** The import parser's validators, so the settings screen cannot accept a different resolver shape. */
private fun isDnsAddressValid(
    value: String,
    transport: DnsTransport,
): Boolean =
    when (transport) {
        DnsTransport.DOH -> DnsValidation.isHttpsUrl(value)
        DnsTransport.DOU -> DnsValidation.isAddressLiteral(value)
    }
