// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [RoutingRepository], [SettingsRepository] and [GeoAssetRepository] slice
 * this feature needs, following
 * [art.yniyniyni.subspace.feature.settings.SettingsSource]'s shape: all three
 * repositories have `internal` constructors scoped to `:core:data` (§3), so
 * this module cannot build real instances of them to test against.
 * [BoundRoutingSource] is the one place that touches the real repositories;
 * [RoutingViewModel] goes through this interface instead, so
 * [RoutingViewModelTest] can exercise it against a plain JVM fake.
 */
internal interface RoutingSource {
    /** Every stored rule set. See [RoutingRepository.observeAll]. */
    val ruleSets: Flow<List<RoutingRuleSet>>

    /**
     * The currently active rule set, or `null` when routing is off. See
     * [SettingsRepository.activeRoutingRuleSetId].
     */
    val activeRuleSetId: Flow<Long?>

    /**
     * The `.dat` filenames actually on disk. The activation gate's other
     * input, alongside a rule set's own
     * [art.yniyniyni.subspace.core.model.requiredGeoFiles] (spec §4.3).
     */
    val installedGeoFiles: Flow<Set<String>>

    /**
     * Filenames whose most recent install attempt failed — the "Last update
     * failed" marker [RoutingListScreen] shows beside, but never in place of,
     * [installedGeoFiles]'s "needs geo files" marker: a missing file blocks
     * activation, a failed refresh of an already-installed file does not.
     *
     * Defaults to an always-empty flow so [RoutingViewModelTest]'s
     * `FakeSource` — which predates this property and has no reason to
     * simulate a failed download — keeps compiling and passing unmodified;
     * only [BoundRoutingSource] overrides it with a real answer.
     */
    val failedGeoFiles: Flow<Set<String>> get() = flowOf(emptySet())

    /** Sets the active rule set, or turns routing off when [id] is `null`. */
    suspend fun setActive(id: Long?)

    /** Deletes a rule set. A no-op if it no longer exists. */
    suspend fun delete(id: Long)
}

@Singleton
internal class BoundRoutingSource
@Inject
constructor(
    private val routingRepository: RoutingRepository,
    private val settingsRepository: SettingsRepository,
    private val geoAssetRepository: GeoAssetRepository,
) : RoutingSource {
    override val ruleSets: Flow<List<RoutingRuleSet>> = routingRepository.observeAll()

    override val activeRuleSetId: Flow<Long?> = settingsRepository.activeRoutingRuleSetId

    /**
     * The activation gate's input, and it must be **the same question the
     * service asks**.
     *
     * `observeAll()` is only the trigger here, not the answer. Deriving the set
     * from the rows directly — `installedAt != null` — asks the database, while
     * `RoutingResolver` in `:service` asks
     * [GeoAssetRepository.installedFileNames], which additionally requires
     * `File(root, name).isFile`. Those disagree exactly when a row says
     * installed and the file is gone: a storage manager sweep, a partial wipe, a
     * rollback that could not restore. In that state a database-only gate shows
     * no marker, enables the selector, permits activation — and the next connect
     * hard-fails with `GeoDataMissing`, which is the outcome this gate exists to
     * prevent.
     *
     * So: re-read through the repository on every emission. The cost is a
     * handful of `File.isFile` calls, already on IO inside the repository, and
     * what it buys is that the screen and the service cannot drift apart.
     */
    override val installedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { geoAssetRepository.installedFileNames() }

    override val failedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { assets ->
            assets.filter { it.lastFailure != null }.map { it.fileName }.toSet()
        }

    override suspend fun setActive(id: Long?) = settingsRepository.setActiveRoutingRuleSetId(id)

    override suspend fun delete(id: Long) = routingRepository.delete(id)
}
