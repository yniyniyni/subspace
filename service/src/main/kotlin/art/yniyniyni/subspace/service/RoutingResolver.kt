// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import java.io.File

/** What the start sequence should do about routing. */
internal sealed interface RoutingResolution {
    /** No active rule set. The config carries `"rules": []` — M1's proven shape. */
    data object Off : RoutingResolution

    /**
     * @property assetDir what `XRAY_LOCATION_ASSET` must name for this session.
     *   A profile with its own geo sources resolves to its live generation;
     *   a hand-made set resolves to the shared catalogue root. Carrying the
     *   directory here prevents the service from re-reading mutable state.
     */
    data class Active(val ruleSet: RoutingRuleSet, val assetDir: File) : RoutingResolution

    /**
     * The active rule set references geo databases that are not on disk.
     *
     * @property missing the filenames, so the user is told what to re-download.
     *   Filenames are shape, not content — `geoip.dat` reveals nothing (§5.6).
     */
    data class MissingGeoData(val missing: Set<String>) : RoutingResolution
}

/**
 * Decides the routing half of a start attempt.
 *
 * Takes suspend lambdas rather than the repositories themselves for the reason
 * [ConnectionRecorder] does: `RoutingRepository`, `SettingsRepository` and
 * `RuleSetAssets` have `internal` constructors scoped to `:core:data`, so
 * `:service` cannot build one, and this project carries no mocking library
 * (§10.7 does not justify adding one for these reads). That indirection is what
 * makes `RoutingResolverTest` a plain JVM test rather than an instrumented one.
 */
internal class RoutingResolver(
    private val activeRuleSetId: suspend () -> Long?,
    private val loadStored: suspend (Long) -> StoredRuleSet?,
    private val installedGeoFiles: suspend (File) -> Set<String>,
    private val assetDirFor: (setId: Long, generation: Long, hasOwnSources: Boolean) -> File,
) {
    /**
     * §4.4: the gate runs here, before config generation, so a missing database
     * produces [RoutingResolution.MissingGeoData] rather than the generic
     * `ConfigRejected` `testXray` would report.
     */
    @Suppress("ReturnCount") // One early return per outcome; see the comments on each.
    suspend fun resolve(): RoutingResolution {
        val id = activeRuleSetId() ?: return RoutingResolution.Off
        // A rule set deleted while it was active leaves a dangling id. Routing
        // off is the honest reading — the user removed the rules — and it must
        // not stop the tunnel from starting.
        val stored = loadStored(id) ?: return RoutingResolution.Off
        val hasOwnSources = !stored.geoIpUrl.isNullOrBlank() || !stored.geoSiteUrl.isNullOrBlank()
        val assetDir = assetDirFor(id, stored.assetGeneration, hasOwnSources)

        val missing = stored.ruleSet.requiredGeoFiles() - installedGeoFiles(assetDir)
        return if (missing.isEmpty()) {
            RoutingResolution.Active(stored.ruleSet, assetDir)
        } else {
            RoutingResolution.MissingGeoData(missing)
        }
    }
}
