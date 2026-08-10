// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs one measurement run in `:bg`.
 *
 * Generic in the profile type so the scheduling, fencing and cancellation logic
 * is testable on the JVM without Room or libXray; [TunnelService] binds it to
 * `StoredProfile`.
 *
 * ## Why a run id and not a cancellable job alone
 *
 * `invoke("ping", …)` is a blocking call into Go with no interrupt, so
 * cancelling the coroutine stops *scheduling* but cannot stop a measurement
 * already inside the native call. That measurement still completes and still
 * wants to deliver. Fencing every delivery on [activeRunId] is what keeps a
 * superseded run's late result from landing on a row the next run is currently
 * retesting — which would show the user a number from the wrong test.
 */
internal class LatencyRunner<T>(
    private val measure: suspend (T, LatencyOptions) -> LatencyResult,
    private val loadProfile: suspend (Long) -> T?,
    private val scope: CoroutineScope,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    private val lock = Any()

    // Both guarded by `lock`.
    private var job: Job? = null
    private var activeRunId: Long = NO_RUN

    fun start(
        runId: Long,
        profileIds: LongArray,
        options: LatencyOptions,
        onResult: (Long, Long, LatencyResult) -> Unit,
        onFinished: (Long) -> Unit,
    ) {
        val previous =
            synchronized(lock) {
                val old = job
                activeRunId = runId
                old
            }
        // Cancels scheduling only. A measurement already in the native call runs
        // to completion; its result is dropped by the fence in `deliver`.
        previous?.cancel()

        val started =
            scope.launch {
                val gate = Semaphore(concurrency)
                profileIds
                    .map { profileId ->
                        async {
                            gate.withPermit {
                                val result =
                                    loadProfile(profileId)
                                        ?.let { profile -> measure(profile, options) }
                                        // Deleted between the list rendering and the
                                        // run reaching it. A real outcome, not a
                                        // silent skip: §10.4 — the row must stop
                                        // saying "testing".
                                        ?: LatencyResult.failed(LatencyOutcome.UNREACHABLE)
                                if (isCurrent(runId)) onResult(runId, profileId, result)
                            }
                        }
                    }.awaitAll()
                if (isCurrent(runId)) onFinished(runId)
            }

        synchronized(lock) {
            // Only claim the slot if nothing superseded us while we were starting.
            if (activeRunId == runId) job = started
        }
    }

    /**
     * Stops scheduling for [runId].
     *
     * A stale cancel — one naming a run that has already been superseded — is
     * ignored rather than tearing down whatever is running now.
     */
    fun cancel(runId: Long) {
        val toCancel =
            synchronized(lock) {
                if (activeRunId != runId) return
                activeRunId = NO_RUN
                job.also { job = null }
            }
        toCancel?.cancel()
    }

    private fun isCurrent(runId: Long): Boolean = synchronized(lock) { activeRunId == runId }

    companion object {
        /**
         * Each concurrent proxy-head measurement holds a live `core.Instance`, so
         * this bounds memory as much as it bounds time. Four takes a 40-server
         * group from over three minutes to roughly fifty seconds at a 5s timeout.
         *
         * A starting value, not a derived one — the first thing to revisit if a
         * device run shows `:bg` under memory pressure.
         */
        const val DEFAULT_CONCURRENCY = 4
        const val NO_RUN = -1L
    }
}
