// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.data.ResolvedAssetUse
import art.yniyniyni.subspace.core.data.RuleSetAssetScope
import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import java.io.File

private const val MAX_GENERATION_RESOLUTION_ATTEMPTS = 3

/** Continuous generation replacement prevented a stable retained snapshot. */
internal class RoutingGenerationChurnException : IllegalStateException("routing generation changed during startup")

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
    private val assetScope: RuleSetAssetScope,
) {
    /**
     * Resolves once at the service boundary and retains an own-source generation
     * for the complete [block]. A second stored read after lease acquisition
     * closes the Room-snapshot-to-filesystem race; replacement retries stay
     * bounded so continuous imports cannot livelock tunnel startup.
     */
    @Suppress("ReturnCount") // Off, deleted, and stable retained snapshots terminate independently.
    suspend fun <T> withResolution(block: suspend (RoutingResolution) -> T): T {
        repeat(MAX_GENERATION_RESOLUTION_ATTEMPTS) {
            val id = activeRuleSetId() ?: return block(RoutingResolution.Off)
            // A rule set deleted while it was active leaves a dangling id. Routing
            // off is the honest reading — the user removed the rules.
            val snapshot = loadStored(id) ?: return block(RoutingResolution.Off)
            val hasOwnSources = snapshot.usesOwnGeneration
            val use =
                assetScope.withResolvedAssetDir(id, snapshot.assetGeneration, hasOwnSources) { assetDir ->
                    val retained =
                        if (hasOwnSources) {
                            loadStored(id)?.takeIf { current ->
                                current.assetGeneration == snapshot.assetGeneration && current.usesOwnGeneration
                            }
                        } else {
                            snapshot
                        }
                    if (retained == null) {
                        ResolutionAttempt.Retry
                    } else {
                        ResolutionAttempt.Complete(block(retained.toResolution(assetDir)))
                    }
                }
            when (use) {
                ResolvedAssetUse.GenerationUnavailable -> Unit
                is ResolvedAssetUse.Used ->
                    when (val attempt = use.value) {
                        is ResolutionAttempt.Complete -> return attempt.value
                        ResolutionAttempt.Retry -> Unit
                    }
            }
        }
        throw RoutingGenerationChurnException()
    }

    /** §4.4's named missing-file gate runs while [assetDir] remains retained. */
    private suspend fun StoredRuleSet.toResolution(assetDir: File): RoutingResolution {
        val missing = ruleSet.requiredGeoFiles() - installedGeoFiles(assetDir)
        return if (missing.isEmpty()) {
            RoutingResolution.Active(ruleSet, assetDir)
        } else {
            RoutingResolution.MissingGeoData(missing)
        }
    }

    private sealed interface ResolutionAttempt<out T> {
        data class Complete<T>(val value: T) : ResolutionAttempt<T>

        data object Retry : ResolutionAttempt<Nothing>
    }
}
