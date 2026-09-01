// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import kotlinx.coroutines.flow.Flow
import space.getsub.core.data.GeoAssetRepository
import space.getsub.core.data.GeoInstallRequest
import space.getsub.core.data.GeoInstallResult
import space.getsub.core.data.InstalledGeoAsset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [GeoAssetRepository] slice this screen needs, wrapped behind an interface for the same
 * reason [SettingsSource] wraps [space.getsub.core.data.SettingsRepository]:
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
     * `:app`'s scheduled [GeoRefreshWorker][space.getsub.sync.GeoRefreshWorker] runs,
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
