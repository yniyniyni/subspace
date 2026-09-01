// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import space.getsub.core.data.StoredProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyResult

/**
 * What [HomeScreen] renders.
 *
 * §5.5: [connection] is a mirror of what [TunnelConnection] reports, never a
 * value this screen infers on its own — see [HomeViewModel]'s `init` block.
 *
 * @property connection the tunnel's real state, from the service.
 * @property activeProfile the profile [space.getsub.core.data.SettingsRepository.activeProfileId]
 *   currently names, or `null` when nothing is selected. This is what Home
 *   connects to now — never `ProfileRepository`'s first row (the retired
 *   `profiles.firstOrNull()` shortcut).
 * @property hasAnyProfile whether at least one profile exists in any group,
 *   selected or not. Distinct from [activeProfile] being non-null: a store
 *   with profiles but none selected should send the user to pick one, while
 *   a genuinely empty store should send them straight to import — see
 *   [canConnect].
 */
internal data class HomeState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val activeProfile: StoredProfile? = null,
    val hasAnyProfile: Boolean = false,
    /**
     * The active profile's measured latency, or `null` when nothing has been
     * measured for it this session.
     *
     * Null renders an em-dash, never `0 ms`.
     *
     * Filled by a tap, and once per session by ping-on-launch when that setting is
     * on ([HomeViewModel.onHomeShown]). Nothing else: M4.5 adds no measurement to
     * the connect path and no periodic refresh, so the tunnel start sequence is
     * untouched and nothing wakes the device to measure while the screen is off —
     * the §11 six-hour case is hard enough already, and it belongs to M7.
     */
    val latency: LatencyResult? = null,
    /** True while the active profile's measurement is in flight. */
    val isMeasuringLatency: Boolean = false,
) {
    /**
     * Whether tapping the connect control should attempt a connection.
     *
     * False whenever there is nothing to connect to ([activeProfile] is
     * `null`), that row's persisted config failed to decode
     * ([StoredProfile.outbound] `null` — a corrupt row, per its own KDoc), or
     * [StoredProfile.connectable] is false — a stored profile whose protocol or transport
     * `:core:xray` cannot yet generate a working config for (fix round 2, Important finding
     * 4: this used to check only that an outbound existed, so a row whose transport the
     * generator could not emit passed here, prompted for VPN permission, started the
     * foreground service, and failed there instead of being refused up front — see
     * [activeProfileUnsupported] for how that case is now explained rather than just
     * silently disabled). Which transports qualify is [StoredProfile.connectable]'s to
     * decide, not this screen's — `ws`/`grpc`/`xhttp` are emitted now and this gate needed
     * no change when they became so.
     * True from [ConnectionState.Failed] as well as [ConnectionState.Disconnected]. Both are
     * terminal states with no tunnel up, and a failure is exactly what a user retries.
     * `TunnelService.startTunnel` already accepts a connect from `Failed` and [HomeScreen]
     * already renders it as `ConnectVisualState.Disconnected`, so accepting only
     * `Disconnected` here left a control that looked retryable and silently ignored taps
     * until some unrelated lifecycle event happened to reset the state (PR #4 review, P1
     * finding B).
     *
     * Retrying does not clear the failure: [connection] is mirrored from the service (§5.5),
     * so the reason and redacted detail stay on screen until the service publishes the
     * retry's own first `Connecting`.
     *
     * False while the tunnel is [ConnectionState.Connecting], [ConnectionState.Connected] or
     * [ConnectionState.Disconnecting]: an attempt already in flight, or a live session, must
     * go through [canDisconnect] instead, not stack a second attempt.
     */
    val canConnect: Boolean
        get() =
            activeProfile?.connectable == true &&
                (connection is ConnectionState.Disconnected || connection is ConnectionState.Failed)

    val canDisconnect: Boolean
        get() =
            connection is ConnectionState.Connected ||
                connection is ConnectionState.Connecting

    /**
     * Whether [activeProfile] decoded fine but [canConnect] is still false because
     * [StoredProfile.connectable] says this build cannot generate a working config for it —
     * the one case where disabling the connect control needs an explanation to the user
     * rather than just a silently inert tap (fix round 2, Important finding 4). False for a
     * corrupt row ([StoredProfile.outbound] `null`) — that is a decode problem, not the
     * transport/protocol gap this screen explains here.
     */
    val activeProfileUnsupported: Boolean
        get() = activeProfile?.let { it.outbound != null && !it.connectable } == true
}
