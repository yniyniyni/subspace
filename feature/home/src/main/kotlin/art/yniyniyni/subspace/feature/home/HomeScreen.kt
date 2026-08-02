// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.ui.component.ConnectControl
import art.yniyniyni.subspace.core.ui.component.ConnectVisualState
import art.yniyniyni.subspace.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import kotlinx.coroutines.delay

private const val UPTIME_TICK_MILLIS = 1_000L

/**
 * The connect screen: what M3 replaces M1's paste-a-link walking skeleton
 * with.
 *
 * Connects to the profile [art.yniyniyni.subspace.core.data.SettingsRepository.activeProfileId]
 * names, never `ProfileRepository`'s first row — retiring the M1 shortcut
 * where pasting a 200-server subscription silently connected to entry #1 with
 * no sign the other 199 existed. Import and the paste field live in
 * `:feature:profiles` now (Task 19); this screen only ever reads.
 *
 * @param onRequestConsent must launch `VpnService.prepare` and invoke its
 *   callback only on approval. A ViewModel cannot start an activity for a
 *   result, and without consent `establish()` returns null and the start
 *   sequence fails at [StartupStage.EstablishingTun] — which looks like a bug
 *   rather than a missing permission.
 * @param onNavigateToServers invoked when the active-server tile is tapped
 *   and at least one profile already exists — the server list is where the
 *   user picks which one is active.
 * @param onAddServer invoked when the active-server tile is tapped and no
 *   profile exists yet (nothing to pick from), and by the standalone "Add
 *   server" chip in every state.
 */
@Composable
fun HomeScreen(
    onRequestConsent: (onGranted: () -> Unit) -> Unit,
    onNavigateToServers: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    HomeScreenContent(
        state = state,
        actions =
        HomeActions(
            onConnect = { onRequestConsent(viewModel::onConsentGranted) },
            onDisconnect = viewModel::onDisconnect,
            onNavigateToServers = onNavigateToServers,
            onAddServer = onAddServer,
        ),
        modifier = modifier,
    )
}

/**
 * [HomeScreenContent]'s four callbacks, grouped into one carrier.
 *
 * A real detekt `LongParameterList` signal, not a suppression candidate —
 * same call [ClashYaml][art.yniyniyni.subspace.core.parser]'s `ClashCommon`
 * makes for the same rule: a carrier keeps the callbacks named at every call
 * site while a parameter list six deep would not.
 */
internal data class HomeActions(
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onNavigateToServers: () -> Unit,
    val onAddServer: () -> Unit,
)

/**
 * The stateless half.
 *
 * Split out so the screen can be rendered from a state value alone — the
 * ViewModel stays internal to this module, and a future Compose UI test gets
 * something it can drive without Hilt.
 */
@Composable
internal fun HomeScreenContent(
    state: HomeState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val visualState = state.connection.toVisualState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            // Home scrolls, so it — not FloatingNavigationBar — is
            // responsible for reserving the space the pill floats over.
            // See FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            .padding(top = 24.dp, bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        ConnectControl(
            state = visualState,
            onClick = {
                when (visualState) {
                    ConnectVisualState.Connected -> actions.onDisconnect()
                    // canConnect gates this rather than ConnectControl itself
                    // (which has no notion of "nothing to connect to") — see
                    // HomeState.canConnect's KDoc for what it guards.
                    ConnectVisualState.Disconnected -> if (state.canConnect) actions.onConnect()
                    // Covers both StartupStage's six real stages and
                    // Disconnecting (mapped here too, see toVisualState) —
                    // unreachable in practice since ConnectControl already
                    // refuses a tap of its own while Connecting, kept only
                    // so this `when` names every branch explicitly.
                    ConnectVisualState.Connecting -> Unit
                }
            },
        )

        Text(
            text = stringResource(state.connection.labelRes()),
            style = MaterialTheme.typography.titleMedium,
        )

        ConnectionDetail(state.connection)

        ActiveServerTile(
            state = state,
            onNavigateToServers = actions.onNavigateToServers,
            onAddServer = actions.onAddServer,
        )

        AssistChip(
            onClick = actions.onAddServer,
            label = { Text(stringResource(R.string.home_add_server)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
            },
        )

        // Deliberately NOT rendered: the DOWN/UP/LATENCY StatTile row and the
        // route chip the design prototype shows. M3 measures neither traffic
        // volume (M7, via the Xray stats API — §14.4) nor latency (M4) nor
        // per-app routing (M5). StatTile exists in :core:ui precisely so
        // those milestones can render it once they have a real value;
        // drawing it here with zeros or placeholder text would tell the user
        // this security tool measured something it did not (§10.1).
    }
}

@Composable
private fun ConnectionDetail(connection: ConnectionState) {
    when (connection) {
        is ConnectionState.Connected -> {
            val elapsed = elapsedSecondsSince(connection.sinceEpochMillis)
            Text(
                text = stringResource(R.string.home_connected_uptime, DateUtils.formatElapsedTime(elapsed)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ConnectionState.Failed -> {
            // detail is redacted at construction (§5.6), so it is safe to
            // show. It is usually the core's own words, which beats a
            // generic message — §10.4: this is the only diagnostic a user
            // can hand back, since §5.6 forbids logging the config that
            // would otherwise explain it.
            Text(
                text = connection.detail,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ConnectionState.Disconnected, ConnectionState.Disconnecting, is ConnectionState.Connecting -> Unit
    }
}

/**
 * Seconds since [sinceEpochMillis], ticking once a second while composed.
 *
 * A real, device-clock-derived measurement — not a placeholder — so it is
 * exempt from this screen's rule against inventing numbers (see the
 * StatTile comment above); it just is not itself a [androidx.compose.runtime.State]
 * the tunnel publishes, so it has to be recomputed locally to stay live.
 */
@Composable
private fun elapsedSecondsSince(sinceEpochMillis: Long): Long {
    var elapsed by remember(sinceEpochMillis) {
        mutableLongStateOf((System.currentTimeMillis() - sinceEpochMillis) / MILLIS_PER_SECOND)
    }
    LaunchedEffect(sinceEpochMillis) {
        while (true) {
            delay(UPTIME_TICK_MILLIS)
            elapsed = (System.currentTimeMillis() - sinceEpochMillis) / MILLIS_PER_SECOND
        }
    }
    return elapsed
}

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The current server, or a prompt to pick or add one.
 *
 * Tapping goes to [onNavigateToServers] whenever at least one profile exists
 * ([HomeState.hasAnyProfile]) — that is where a server is chosen or its
 * active status changed — and to [onAddServer] only when the store is
 * genuinely empty, since there is nothing to navigate to and pick from yet.
 */
@Composable
private fun ActiveServerTile(
    state: HomeState,
    onNavigateToServers: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeProfile = state.activeProfile
    val headline: String
    val actionDescription: String
    val onClick: () -> Unit
    when {
        activeProfile != null -> {
            headline = activeProfile.name
            actionDescription = stringResource(R.string.home_active_server_change_description)
            onClick = onNavigateToServers
        }

        state.hasAnyProfile -> {
            headline = stringResource(R.string.home_choose_a_server)
            actionDescription = headline
            onClick = onNavigateToServers
        }

        else -> {
            headline = stringResource(R.string.home_no_servers_yet)
            actionDescription = stringResource(R.string.home_add_first_server)
            onClick = onAddServer
        }
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = actionDescription },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_active_server_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Projects the real [ConnectionState] onto [ConnectControl]'s three-state
 * visual model.
 *
 * All six [StartupStage]s collapse into [ConnectVisualState.Connecting] —
 * [ConnectControl] renders one "busy" look regardless of which stage, and the
 * stage text itself is shown separately via [labelRes].
 *
 * [ConnectionState.Disconnecting] maps to [ConnectVisualState.Connecting]:
 * teardown is a transient in-flight action, the same as a stage of startup.
 * [ConnectVisualState.Disconnected] would wrongly invite a fresh connect tap
 * mid-teardown, and [ConnectVisualState.Connected] would wrongly suggest the
 * tunnel is still up. `Connecting`'s own component-level tap guard already
 * refuses input in this bucket, which is exactly teardown's semantics too.
 *
 * [ConnectionState.Failed] maps to [ConnectVisualState.Disconnected]: nothing
 * is connected, so the correct next action is the same "tap to connect" the
 * plain disconnected state offers. `Connecting` would misrepresent a stopped
 * attempt as one still in progress. The failure reason and its redacted
 * detail are still shown, via [labelRes] and [ConnectionDetail] — this
 * mapping only decides the control's own colour and tap behaviour, not
 * whether the failure is communicated at all.
 */
private fun ConnectionState.toVisualState(): ConnectVisualState =
    when (this) {
        ConnectionState.Disconnected -> ConnectVisualState.Disconnected
        is ConnectionState.Connecting -> ConnectVisualState.Connecting
        is ConnectionState.Connected -> ConnectVisualState.Connected
        ConnectionState.Disconnecting -> ConnectVisualState.Connecting
        is ConnectionState.Failed -> ConnectVisualState.Disconnected
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
