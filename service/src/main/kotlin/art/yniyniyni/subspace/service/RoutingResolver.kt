// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.requiredGeoFiles

/** What the start sequence should do about routing. */
internal sealed interface RoutingResolution {
    /** No active rule set. The config carries `"rules": []` — M1's proven shape. */
    data object Off : RoutingResolution

    data class Active(val ruleSet: RoutingRuleSet) : RoutingResolution

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
 * `GeoAssetRepository` all have `internal` constructors scoped to `:core:data`,
 * so `:service` cannot build one, and this project carries no mocking library
 * (§10.7 does not justify adding one for three reads). That indirection is what
 * makes `RoutingResolverTest` a plain JVM test rather than an instrumented one.
 */
internal class RoutingResolver(
    private val activeRuleSetId: suspend () -> Long?,
    private val loadRuleSet: suspend (Long) -> RoutingRuleSet?,
    private val installedGeoFiles: suspend () -> Set<String>,
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
        val ruleSet = loadRuleSet(id) ?: return RoutingResolution.Off

        val missing = ruleSet.requiredGeoFiles() - installedGeoFiles()
        return if (missing.isEmpty()) {
            RoutingResolution.Active(ruleSet)
        } else {
            RoutingResolution.MissingGeoData(missing)
        }
    }
}
