// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.StartupStage
import space.getsub.core.ui.component.ConnectControl
import space.getsub.core.ui.component.ConnectVisualState
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import space.getsub.core.ui.component.StatTile
import space.getsub.core.ui.format.formatByteCount

private const val UPTIME_TICK_MILLIS = 1_000L

/**
 * The connect screen: what M3 replaces M1's paste-a-link walking skeleton
 * with.
 *
 * Connects to the profile [space.getsub.core.data.SettingsRepository.activeProfileId]
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

    // Keyed on the active profile: switching servers should measure the new one,
    // and onHomeShown is a no-op for a profile that already has a reading.
    LaunchedEffect(state.activeProfile?.id) { viewModel.onHomeShown() }

    HomeScreenContent(
        state = state,
        actions =
        HomeActions(
            onConnect = { onRequestConsent(viewModel::onConsentGranted) },
            onDisconnect = viewModel::onDisconnect,
            onNavigateToServers = onNavigateToServers,
            onAddServer = onAddServer,
            onTestLatency = viewModel::onTestLatency,
        ),
        modifier = modifier,
    )
}

/**
 * [HomeScreenContent]'s four callbacks, grouped into one carrier.
 *
 * A real detekt `LongParameterList` signal, not a suppression candidate —
 * same call [ClashYaml][space.getsub.core.parser]'s `ClashCommon`
 * makes for the same rule: a carrier keeps the callbacks named at every call
 * site while a parameter list six deep would not.
 */
internal data class HomeActions(
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onNavigateToServers: () -> Unit,
    val onAddServer: () -> Unit,
    /**
     * M4.5: measures the active profile. The only other trigger is ping-on-launch,
     * once per session — nothing measures on connect and nothing on a timer.
     */
    val onTestLatency: () -> Unit,
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
                    // Spec §7.3. Unlike Connecting, a reconnect has no bound —
                    // a Retryable reason retries for as long as a network
                    // exists — and with the kill switch on the user has no
                    // connectivity while it does. This is that state's only
                    // in-app exit, so unlike the branch above it must act.
                    ConnectVisualState.Reconnecting -> if (state.canDisconnect) actions.onDisconnect()
                }
            },
        )

        Text(
            text = stringResource(state.connection.labelRes()),
            style = MaterialTheme.typography.titleMedium,
        )

        ConnectionDetail(state.connection)

        if (state.activeProfileUnsupported) {
            Text(
                text = stringResource(R.string.home_active_server_unsupported),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ActiveServerTile(
            state = state,
            onNavigateToServers = actions.onNavigateToServers,
            onAddServer = actions.onAddServer,
        )

        StatTilesRow(state = state, onTest = actions.onTestLatency)

        AssistChip(
            onClick = actions.onAddServer,
            label = { Text(stringResource(R.string.home_add_server)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
            },
        )

        // Still deliberately NOT rendered: the route chip the design prototype
        // shows. This build measures no per-app routing yet (M5), and drawing
        // it with placeholder text would tell the user this security tool
        // measured something it did not (§10.1).
        //
        // The LATENCY tile was the first of that row to be fed (M4.5). The
        // DOWN/UP tiles beside it are fed by M8.5, from hev-socks5-tunnel's own
        // TUN-level counters — libXray at this project's pinned version exposes
        // no stats call at all (ARCHITECTURE.md §14.4, amended separately to
        // record that).
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

        // §7.3: whether traffic is currently blocked or flowing in the clear is
        // deliberately NOT shown here. That distinction is carried by the
        // ongoing notification (§6.3), which is the surface a user actually
        // sees while the app is backgrounded mid-reconnect; duplicating it on
        // Home would mean widening ConnectionState.Reconnecting and its parcel
        // discriminant to carry a fact the notification already reports.
        // Deferred to M8.5 — see the roadmap, not a TODO here.
        ConnectionState.Disconnected,
        ConnectionState.Disconnecting,
        is ConnectionState.Connecting,
        is ConnectionState.Reconnecting,
        -> Unit
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
/**
 * The design system's [StatTile] row: LATENCY, and — for a live session —
 * DOWN/UP beside it.
 *
 * `StatTile`'s own KDoc asked that whichever milestone first has a real value to
 * show be the one to render it. M4.5 is that milestone for latency; M8.5 is the
 * same for traffic, from `hev-socks5-tunnel`'s own TUN-level counters — not the
 * Xray stats API, which does not exist at this project's pinned libXray version.
 *
 * LATENCY: every non-`OK` outcome renders text rather than a number.
 * `delayMillis` is read on the `OK` branch alone: libXray reports a failed ping
 * as a `10000`/`11000` sentinel, and `StatTile` formats nothing itself — it
 * renders exactly what it is handed, which makes formatting the caller's
 * responsibility and this branch the place §10.1 is either honoured or
 * violated. Tapping measures, and so does ping-on-launch once per session.
 * Nothing else: no measurement on connect, and no timer.
 *
 * DOWN/UP: rendered only while [HomeState.connection] is
 * [ConnectionState.Connected] — never on [HomeState.traffic] alone, because an
 * ordinary in-session disconnect while the UI stays bound does not clear
 * `TunnelClient`'s cached sample (see [TunnelConnection.traffic]'s KDoc).
 * [formatByteCount] does the formatting, same as [HomeState.latency]'s branch
 * above; `0 B` for no traffic yet is a real measurement, not a placeholder, so
 * unlike latency there is no em-dash variant here (§10.1). Not tappable: there
 * is no on-demand action for traffic the way there is for a latency test, only
 * the passive per-second stream [HomeViewModel] mirrors into
 * [HomeState.traffic].
 */
@Composable
private fun StatTilesRow(
    state: HomeState,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latency = state.latency
    val latencyValue =
        when {
            state.isMeasuringLatency -> stringResource(R.string.home_latency_testing)
            latency == null -> stringResource(R.string.home_latency_none)
            latency.outcome == LatencyOutcome.OK -> stringResource(R.string.home_latency_ms, latency.delayMillis)
            latency.outcome == LatencyOutcome.UNSUPPORTED -> stringResource(R.string.home_latency_unsupported)
            latency.outcome == LatencyOutcome.FOREIGN_VPN -> stringResource(R.string.home_latency_foreign_vpn)
            else -> stringResource(R.string.home_latency_failed)
        }
    val latencyHint = stringResource(R.string.home_latency_test_description)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatTile(
            value = latencyValue,
            label = stringResource(R.string.home_latency_label),
            accent = false,
            modifier =
            Modifier
                .clickable(
                    enabled = state.activeProfile != null && !state.isMeasuringLatency,
                    role = Role.Button,
                    onClick = onTest,
                )
                .semantics { contentDescription = latencyHint },
        )
        if (state.connection is ConnectionState.Connected) {
            StatTile(
                value = formatByteCount(state.traffic?.downlinkBytes ?: 0),
                label = stringResource(R.string.home_traffic_down),
                accent = false,
            )
            StatTile(
                value = formatByteCount(state.traffic?.uplinkBytes ?: 0),
                label = stringResource(R.string.home_traffic_up),
                accent = false,
            )
        }
    }
}

/**
 * The active server, and where tapping it goes — see [HomeActions] for the split
 * between navigating to the list and going straight to import.
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
 *
 * [ConnectionState.Reconnecting] maps to [ConnectVisualState.Reconnecting], its
 * own visual state — not to [ConnectVisualState.Connecting], which is where it
 * started (spec §7.3). `Connecting` renders identically but refuses every tap
 * inside `ConnectControl` itself, and a reconnect is unbounded: a `Retryable`
 * reason retries for as long as a network exists, and with the kill switch on
 * the user has no connectivity meanwhile. That mapping therefore produced a
 * state with no exit and no bound. [ConnectVisualState.Connected] was not the
 * alternative — it renders the connected colour and would claim a tunnel that
 * is down, the same false-reassurance defect §6.3's notification had.
 *
 * [HomeState.canDisconnect] is true for `Reconnecting` and this mapping is what
 * lets that boolean reach the control: the tap handler and `ConnectControl`'s
 * own guard each refused it independently, so all three had to change together.
 */
private fun ConnectionState.toVisualState(): ConnectVisualState =
    when (this) {
        ConnectionState.Disconnected -> ConnectVisualState.Disconnected
        is ConnectionState.Connecting -> ConnectVisualState.Connecting
        is ConnectionState.Connected -> ConnectVisualState.Connected
        ConnectionState.Disconnecting -> ConnectVisualState.Connecting
        is ConnectionState.Failed -> ConnectVisualState.Disconnected
        is ConnectionState.Reconnecting -> ConnectVisualState.Reconnecting
    }

private fun ConnectionState.labelRes(): Int =
    when (this) {
        is ConnectionState.Disconnected -> R.string.state_disconnected
        is ConnectionState.Disconnecting -> R.string.state_disconnecting
        is ConnectionState.Connected -> R.string.state_connected
        is ConnectionState.Connecting -> stage.labelRes()
        is ConnectionState.Failed -> reason.labelRes()
        // Generic, not reason.labelRes(): unlike Failed, a Reconnecting attempt
        // is not something the user needs to act on, so it gets one steady
        // label rather than cycling through whichever reason triggered each
        // retry. The blocked/open distinction (§7.3) is the notification's to
        // report, not this label's — see ConnectionDetail above.
        is ConnectionState.Reconnecting -> R.string.home_state_reconnecting
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

// One exhaustive enum-to-resource mapping; splitting it would only hide the same branches.
@Suppress("CyclomaticComplexMethod")
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
        FailureReason.GeoDataMissing -> R.string.failure_geo_data_missing
        FailureReason.PerAppAllowListEmpty -> R.string.failure_per_app_allow_list_empty
        FailureReason.PassthroughOverrideUnavailable -> R.string.failure_passthrough_override_unavailable
        FailureReason.PassthroughRejectedAtConnect -> R.string.failure_passthrough_rejected_at_connect
    }
