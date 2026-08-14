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

    // GeoAssetRepository.installedFileNames() is a suspend, one-shot read (it
    // also verifies the file is actually still on disk); this screen needs a
    // flow that recomposes when an install completes, so it maps
    // observeAll() instead of calling that read once.
    override val installedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { assets ->
            assets.filter { it.installedAt != null }.map { it.fileName }.toSet()
        }

    override val failedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { assets ->
            assets.filter { it.lastFailure != null }.map { it.fileName }.toSet()
        }

    override suspend fun setActive(id: Long?) = settingsRepository.setActiveRoutingRuleSetId(id)

    override suspend fun delete(id: Long) = routingRepository.delete(id)
}
