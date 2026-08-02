// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState

/**
 * What [HomeScreen] renders.
 *
 * §5.5: [connection] is a mirror of what [TunnelConnection] reports, never a
 * value this screen infers on its own — see [HomeViewModel]'s `init` block.
 *
 * @property connection the tunnel's real state, from the service.
 * @property activeProfile the profile [art.yniyniyni.subspace.core.data.SettingsRepository.activeProfileId]
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
) {
    /**
     * Whether tapping the connect control should attempt a connection.
     *
     * False whenever there is nothing to connect to ([activeProfile] is
     * `null`) or that row's persisted config failed to decode
     * ([StoredProfile.outbound] `null` — a corrupt row, per its own KDoc).
     * Also false while the tunnel is not [ConnectionState.Disconnected]:
     * a connect attempt already in flight, or already connected, must go
     * through [canDisconnect] instead, not stack a second attempt.
     */
    val canConnect: Boolean
        get() = activeProfile?.outbound != null && connection is ConnectionState.Disconnected

    val canDisconnect: Boolean
        get() =
            connection is ConnectionState.Connected ||
                connection is ConnectionState.Connecting
}
