// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.getsub.core.data.InstalledApp
import space.getsub.core.data.InstalledAppsSource
import space.getsub.core.data.PerAppRepository
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.service.TunnelClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `:core:data` slice the per-app picker needs, following [RoutingSource]'s
 * shape and existing for the same reason: this module cannot build a real
 * [PerAppRepository] to test against. Its own constructor is public, but it takes
 * a `SettingsRepository`, whose constructor **is** `internal` to `:core:data`
 * (§3) — so the seam is needed transitively, and would still be needed if
 * `PerAppRepository` were made `internal` too. [BoundPerAppSource] is the only
 * thing that touches it.
 */
internal interface PerAppSource {
    /**
     * The **raw** stored selection — [PerAppRepository.userSelection], never
     * [PerAppRepository.selection].
     *
     * The picker round-trips this: it seeds a draft from it and writes the draft
     * back through [apply]. The effective flow collapses `Off` and the empty
     * deny-list to no packages, so seeding from it and writing back would erase
     * the user's list on the second save. Nothing on this screen wants the
     * effective set — that one exists for the tunnel.
     */
    val userSelection: Flow<PerAppSelection>

    /** Every installed app except ours, sorted by label. See [InstalledAppsSource.installed]. */
    suspend fun installed(): List<InstalledApp>

    /** Writes mode and packages together — see [PerAppViewModel.save] for why they are one call. */
    suspend fun apply(mode: PerAppMode, packages: Set<String>)

    /**
     * Whether there is a tunnel a save would disturb, so the screen can say so
     * before the user commits.
     *
     * Deliberately wider than "connected": every `Connecting` stage counts. A
     * start sequence takes seconds (geo resolution, config validation), and a
     * save landing in that window really does restart it — a notice that
     * appeared only once `Connected` arrived would be silent for exactly the
     * case the user is most likely to be surprised by.
     *
     * It is **not** the service's `ownTunnelActive()` gate, though it was once
     * described as mirroring it. That gate is "not `Disconnected` and not
     * `Failed`", which includes `Reconnecting`; this excludes `Reconnecting`
     * deliberately, because a reapply offered there would do nothing. See
     * [BoundPerAppSource.isTunnelActive] for the argument, which is about what a
     * reapply would accomplish rather than what interfaces are up.
     */
    val isTunnelActive: Flow<Boolean>

    /** Rebuilds the tunnel so a saved selection takes effect. No-op when nothing is running. */
    suspend fun reapply()
}

@Singleton
internal class BoundPerAppSource
@Inject
constructor(
    private val perAppRepository: PerAppRepository,
    private val installedApps: InstalledAppsSource,
    private val tunnelClient: TunnelClient,
) : PerAppSource {
    override val userSelection: Flow<PerAppSelection> = perAppRepository.userSelection

    override suspend fun installed(): List<InstalledApp> = installedApps.installed()

    override suspend fun apply(mode: PerAppMode, packages: Set<String>) {
        // Packages first, then mode. The order matters at exactly one moment: a
        // process death between the two writes. Landing packages first leaves the
        // old mode governing a new list, which the resolver handles; landing mode
        // first could leave AllowList governing an empty list — the one state
        // PerAppResolver refuses to start (§6.3).
        perAppRepository.setUserPackages(packages)
        perAppRepository.setMode(mode)
    }

    /**
     * Whether a per-app change has a live session to reapply to.
     *
     * Exhaustive with no `else`: M8 added `Reconnecting` and an `is`-chain
     * absorbed it silently (spec §2.2 chose this shape for
     * `FailureReason.retryability` for the same reason).
     *
     * `Reconnecting` is **false**, and deliberately not "true because a retained
     * TUN exists". The value answers what a reapply would accomplish, not what
     * interfaces are up: `TunnelService.reapplyPerAppFromCommand` samples
     * `liveSession`, which `settleRetryableFailure` has already nulled by then,
     * so the command returns without doing anything. Reporting active here would
     * put an affordance on screen that the service silently ignores. The next
     * successful attempt applies the current selection anyway.
     */
    override val isTunnelActive: Flow<Boolean> =
        tunnelClient.state.map { state ->
            when (state) {
                is ConnectionState.Connected,
                is ConnectionState.Connecting,
                -> true

                is ConnectionState.Reconnecting,
                is ConnectionState.Failed,
                ConnectionState.Disconnected,
                ConnectionState.Disconnecting,
                -> false
            }
        }

    override suspend fun reapply() = tunnelClient.reapplyPerApp()
}
