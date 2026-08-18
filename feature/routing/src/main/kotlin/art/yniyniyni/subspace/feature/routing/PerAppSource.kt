// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.InstalledApp
import art.yniyniyni.subspace.core.data.InstalledAppsSource
import art.yniyniyni.subspace.core.data.PerAppRepository
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `:core:data` slice the per-app picker needs, following [RoutingSource]'s
 * shape and existing for the same reason: [PerAppRepository] has an `internal`
 * constructor scoped to `:core:data` (§3), so this module cannot build a real one
 * to test against. [BoundPerAppSource] is the only thing that touches it.
 */
internal interface PerAppSource {
    /** The stored selection. See [PerAppRepository.selection]. */
    val selection: Flow<PerAppSelection>

    /** Every installed app except ours, sorted by label. See [InstalledAppsSource.installed]. */
    suspend fun installed(): List<InstalledApp>

    /** Writes mode and packages together — see [PerAppViewModel.save] for why they are one call. */
    suspend fun apply(mode: PerAppMode, packages: Set<String>)
}

@Singleton
internal class BoundPerAppSource
@Inject
constructor(
    private val perAppRepository: PerAppRepository,
    private val installedApps: InstalledAppsSource,
) : PerAppSource {
    override val selection: Flow<PerAppSelection> = perAppRepository.selection

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
}
