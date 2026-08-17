// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.GeoInstallRequest
import art.yniyniyni.subspace.core.data.GeoInstallResult
import art.yniyniyni.subspace.core.data.InstalledGeoAsset
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [GeoAssetRepository] slice this screen needs, wrapped behind an interface for the same
 * reason [SettingsSource] wraps [art.yniyniyni.subspace.core.data.SettingsRepository]:
 * [GeoAssetRepository]'s constructor is `internal` to `:core:data` (§11), so this module cannot
 * build a real instance to test against, and a plain JVM [SettingsViewModel] test needs a fake it
 * can hand back canned [GeoInstallResult]s from.
 */
internal interface GeoAssetSource {
    /** Every recorded geo asset, installed or merely attempted. See [GeoAssetRepository.observeAll]. */
    val installedAssets: Flow<List<InstalledGeoAsset>>

    /**
     * Downloads, validates and installs one geo database. Never throws (§10.4).
     *
     * This is what "Update now" calls directly. It ignores both the 7-day freshness cap and the
     * unmetered constraint by construction: [GeoAssetRepository.install] itself never consults
     * [GeoAssetRepository.isDueForRefresh] or any metered setting (§A.5) — those only gate
     * `:app`'s scheduled [GeoRefreshWorker][art.yniyniyni.subspace.sync.GeoRefreshWorker] runs,
     * which this module cannot reach anyway (`:feature:settings` cannot depend on `:app`, same as
     * [AppVersionSource]'s KDoc explains).
     */
    suspend fun install(request: GeoInstallRequest): GeoInstallResult

    /**
     * Drops the recorded row for [fileName]. See [GeoAssetRepository.remove]'s own KDoc — this is
     * the seam branch review Finding 3 wires a "Remove" action for custom sources through to, so a
     * typo'd URL is not retried by a scheduled refresh forever with no way to stop it.
     */
    suspend fun remove(fileName: String)
}

@Singleton
internal class BoundGeoAssetSource
@Inject
constructor(
    private val repository: GeoAssetRepository,
) : GeoAssetSource {
    override val installedAssets: Flow<List<InstalledGeoAsset>> = repository.observeAll()

    override suspend fun install(request: GeoInstallRequest): GeoInstallResult = repository.install(request)

    override suspend fun remove(fileName: String) = repository.remove(fileName)
}
