// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.StartupStage

/**
 * M1's whole UI: paste a server, connect, watch the state.
 *
 * Deliberately ugly. The milestone's deliverable is traffic on a device (§10.1),
 * not a screen anyone would want to use — M3 replaces this entirely.
 *
 * @param onRequestConsent must launch `VpnService.prepare` and invoke its
 *   callback only on approval. A ViewModel cannot start an activity for a
 *   result, and without consent `establish()` returns null and the start
 *   sequence fails at [StartupStage.EstablishingTun] — which looks like a bug
 *   rather than a missing permission.
 */
@Composable
fun HomeScreen(
    onRequestConsent: (onGranted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    HomeScreenContent(
        state = state,
        onInputChanged = viewModel::onInputChanged,
        onConnect = { onRequestConsent(viewModel::onConsentGranted) },
        onDisconnect = viewModel::onDisconnect,
        modifier = modifier,
    )
}

/**
 * The stateless half.
 *
 * Split out so the screen can be rendered from a state value alone — the
 * ViewModel stays internal to this module, and §11's Compose UI tests get
 * something they can drive without Hilt.
 */
@Composable
internal fun HomeScreenContent(
    state: HomeState,
    onInputChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChanged,
            label = { Text(stringResource(R.string.home_input_label)) },
            isError = state.inputError != null,
            modifier = Modifier.fillMaxWidth(),
        )

        state.inputError?.let { errorRes ->
            Text(
                text = stringResource(errorRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // §10.4: the parser's detail is the only diagnostic a user can hand
        // back, and it is already redacted (§5.6) — safe to show as-is.
        state.inputErrorDetail?.let { detail ->
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // The stage is shown, not just "Connecting". §10.4: when the start
        // sequence fails, this is the only diagnostic a user can hand back —
        // §5.6 forbids logging the config that would otherwise explain it.
        Text(
            text = stringResource(state.connection.labelRes()),
            style = MaterialTheme.typography.titleMedium,
        )

        (state.connection as? ConnectionState.Failed)?.let { failed ->
            // detail is redacted at construction (§5.6), so it is safe to show.
            // It is usually the core's own words, which beats a generic message.
            Text(
                text = failed.detail,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.canDisconnect) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_disconnect))
            }
        } else {
            Button(
                onClick = onConnect,
                enabled = state.canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_connect))
            }
        }
    }
}

private fun ConnectionState.labelRes(): Int =
    when (this) {
        is ConnectionState.Disconnected -> R.string.state_disconnected
        is ConnectionState.Disconnecting -> R.string.state_disconnecting
        is ConnectionState.Connected -> R.string.state_connected
        is ConnectionState.Connecting -> stage.labelRes()
        is ConnectionState.Failed -> reason.labelRes()
    }

private fun StartupStage.labelRes(): Int =
    when (this) {
        StartupStage.AllocatingPort -> R.string.stage_allocating_port
        StartupStage.GeneratingConfig -> R.string.stage_generating_config
        StartupStage.ValidatingConfig -> R.string.stage_validating_config
        StartupStage.StartingCore -> R.string.stage_starting_core
        StartupStage.EstablishingTun -> R.string.stage_establishing_tun
        StartupStage.StartingTunnel -> R.string.stage_starting_tunnel
    }

private fun FailureReason.labelRes(): Int =
    when (this) {
        FailureReason.ConfigGenerationFailed -> R.string.failure_config_generation
        FailureReason.ConfigRejected -> R.string.failure_config_rejected
        FailureReason.PortAllocationFailed -> R.string.failure_port_allocation
        FailureReason.CoreStartFailed -> R.string.failure_core_start
        FailureReason.VpnPermissionMissing -> R.string.failure_vpn_permission
        FailureReason.TunEstablishFailed -> R.string.failure_tun_establish
        FailureReason.TunnelStartFailed -> R.string.failure_tunnel_start
        FailureReason.Revoked -> R.string.failure_revoked
        FailureReason.ProtocolNotSupported -> R.string.failure_protocol_not_supported
        FailureReason.ProfileDecodeFailed -> R.string.failure_profile_decode
    }
