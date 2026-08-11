// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import kotlinx.coroutines.CancellationException
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
    /**
     * Reports a swallowed measurement failure.
     *
     * A hook rather than a direct `android.util.Log` call, for two reasons. This
     * class is deliberately free of Android types so its scheduling and fencing
     * logic is testable on the JVM — and `Log` is a stub off-device that *throws*,
     * so calling it from the catch below would have let the very exception this
     * guard exists to contain escape after all. The test that found that is the
     * one asserting nothing reaches the scope's handler.
     *
     * Takes a class name, never a message: a Room or libXray error can quote a
     * stored config value (§5.6).
     */
    private val onMeasurementError: (String) -> Unit = {},
) {
    /**
     * One semaphore for the runner, not one per run.
     *
     * A superseded run's measurements cannot be interrupted — that is the whole
     * reason the run-id fence exists — so a per-run semaphore let the old run's
     * permits and the new run's permits coexist, allowing up to twice
     * [concurrency] live `core.Instance`s in `:bg`. In a process whose heap is the
     * stated reason for the bound, and with 4 already an unverified guess, a bound
     * that doubles under ordinary supersession is not a bound.
     */
    private val gate = Semaphore(concurrency)

    private val lock = Any()

    // Both guarded by `lock`.
    private var job: Job? = null
    private var activeRunId: Long = NO_RUN

    // The broad catch inside is deliberate and is the whole point of the guard:
    // `scope` belongs to TunnelService and carries the start-sequence exception
    // handler, so *any* escaping type — Room's SQLiteException, an
    // IllegalArgumentException from a stored enum name, a socket failure under fd
    // pressure — publishes a fabricated tunnel failure and corrupts connection
    // state. Naming individual types would leave exactly the gaps this exists to
    // close. CancellationException is rethrown so supersession still works.
    @Suppress("TooGenericExceptionCaught")
    fun start(
        runId: Long,
        profileIds: LongArray,
        optionsFor: (Int) -> LatencyOptions,
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
                profileIds
                    .mapIndexed { index, profileId ->
                        async {
                            gate.withPermit {
                                // A measurement must never be able to fail this
                                // coroutine. `scope` belongs to TunnelService and
                                // carries the start-sequence CoroutineExceptionHandler,
                                // so an escaping throw published a fabricated
                                // `CoreStartFailed` — §5.5's lying UI while the tunnel
                                // was still carrying traffic — and left currentState
                                // `Failed`, which opens startTunnel's duplicate-connect
                                // guard and leaks the live TUN fd (§5.4). Room can throw
                                // from loadProfile and a socket bind can throw under fd
                                // pressure, so this is reachable from a "Test all" tap.
                                //
                                // CancellationException is rethrown: swallowing it would
                                // break supersession, which is cooperative.
                                val result =
                                    try {
                                        loadProfile(profileId)
                                            ?.let { profile -> measure(profile, optionsFor(index)) }
                                            // Deleted between the list rendering and
                                            // the run reaching it. A real outcome, not
                                            // a silent skip: §10.4 — the row must stop
                                            // saying "testing".
                                            ?: LatencyResult.failed(LatencyOutcome.UNREACHABLE)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        onMeasurementError(e.javaClass.simpleName)
                                        LatencyResult.failed(LatencyOutcome.UNREACHABLE)
                                    }
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

        /**
         * Below every id any caller mints. `:main` derives Home's run id as
         * `-profileId`, so `-1` — the obvious sentinel — is exactly the id of a run
         * for the first row Room ever inserted. `isCurrent` would then pass for a
         * superseded Home measurement after a cancel, and the whole design rests on
         * that fence being inviolable.
         */
        const val NO_RUN = Long.MIN_VALUE
    }
}
