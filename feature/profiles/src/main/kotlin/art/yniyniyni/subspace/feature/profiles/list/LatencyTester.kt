// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.data.LatencyCache
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.service.TunnelClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The measurement surface the Servers screen needs, behind one seam.
 *
 * Exists for the same reason [ProfileSource][art.yniyniyni.subspace.feature.profiles.ProfileSource]
 * does: the things behind it — [LatencyCache], [SettingsRepository] and
 * [TunnelClient] — cannot be built by hand in a `:feature:profiles` unit test
 * ([SettingsRepository]'s constructor is `internal` to `:core:data`, and
 * [TunnelClient] needs a `Context` and a live binder). Screens talk to this;
 * [BoundLatencyTester] is the one place that touches the real collaborators.
 */
internal interface LatencyTester {
    /** Measured results by profile id. Absence means never measured, never zero. */
    val results: StateFlow<Map<Long, LatencyResult>>

    /** Profile ids with a measurement in flight. */
    val testing: StateFlow<Set<Long>>

    /**
     * Measures [profileIds], superseding any run already in flight.
     *
     * A single-server test is just a one-element list — there is deliberately no
     * separate path for it, so the row action and the group action cannot drift.
     */
    suspend fun test(profileIds: List<Long>)

    /**
     * Stops scheduling the current run.
     *
     * Not instantaneous: a measurement already inside libXray's blocking ping
     * finishes on its own, and its result is discarded rather than shown. Rows
     * still testing return to idle.
     */
    fun cancel()

    /** True the first time it is asked for [groupId] this session — see [LatencyCache.claimLaunchRun]. */
    fun claimLaunchRun(groupId: Long): Boolean

    /** The user's global ping-on-launch setting. */
    val pingOnLaunch: Flow<Boolean>

    /** Whether ping-on-launch may run while the active network is metered. */
    val pingOnLaunchMetered: Flow<Boolean>
}

@Singleton
internal class BoundLatencyTester
@Inject
constructor(
    private val cache: LatencyCache,
    private val tunnel: TunnelClient,
    private val settings: SettingsRepository,
) : LatencyTester {
    override val results: StateFlow<Map<Long, LatencyResult>> = cache.results
    override val testing: StateFlow<Set<Long>> = cache.testing
    override val pingOnLaunch: Flow<Boolean> = settings.pingOnLaunch
    override val pingOnLaunchMetered: Flow<Boolean> = settings.pingOnLaunchMetered

    /**
     * Monotonic, and the fence both sides of the binder check. Cancellation
     * cannot interrupt an in-flight native ping, so a superseded run's results
     * still arrive; without a fresh id per run they would land on rows the new
     * run is retesting.
     */
    private val runId = AtomicLong(0)

    override suspend fun test(profileIds: List<Long>) {
        if (profileIds.isEmpty()) return
        // Resolved here rather than in :bg, so the service never has to reach
        // into settings to interpret a run.
        val options =
            LatencyOptions(
                mode = settings.pingMode.first(),
                timeoutSeconds = settings.pingTimeoutSeconds.first(),
                checkUrl = settings.pingCheckUrl.first(),
            )
        val id = runId.incrementAndGet()
        cache.markTesting(profileIds)
        tunnel.startLatencyRun(
            runId = id,
            profileIds = profileIds,
            options = options,
            onResult = { profileId, result -> cache.put(profileId, result) },
            // Clears the in-flight flag without writing results for rows that
            // never produced one — a measurement that did not happen must not be
            // recorded as one.
            onFinished = { cache.finish(profileIds) },
        )
    }

    override fun cancel() {
        tunnel.cancelLatencyRun(runId.get())
        cache.finish(cache.testing.value)
    }

    override fun claimLaunchRun(groupId: Long): Boolean = cache.claimLaunchRun(groupId)
}
