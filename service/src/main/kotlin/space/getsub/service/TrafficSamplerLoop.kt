// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.getsub.core.model.TrafficSample

/**
 * The timer half of the sampler (spec §1.5).
 *
 * The sampler lives in `:bg` and runs whether or not a UI is bound, because
 * liveness (spec §4.3, Part 3) must detect an unreachable server with the app
 * backgrounded — `ConnectionState` is the service's property under
 * ARCHITECTURE.md §5.5. The UI is a second consumer of samples that already
 * exist, not the reason they are taken.
 *
 * **One `TrafficSampler` per session, not one for this loop's whole
 * lifetime.** [stop] only calls `Job.cancel()`, which is cooperative:
 * cancellation is observed at the next [delay], not mid-body, so a call
 * landing between the `while (isActive)` check and `delay` lets that
 * iteration's [TrafficSampler.accept] run to completion regardless.
 * [TerminalOutcome]'s own KDoc documents this exact class of bug
 * ("`cancel()` alone cannot stop it"). A fast disconnect-then-reconnect can
 * therefore have the old job's tail end still inside `accept()` while
 * [start] launches a new job — and `TrafficSampler` is documented
 * not-thread-safe, plain unsynchronised `var`s. Sharing one instance across
 * both jobs would let the dying job's `accept()` corrupt the new session's
 * accumulation. [start] instead builds a fresh [TrafficSampler] and captures
 * it in the launched coroutine's closure, so an old job's tail end and a new
 * job's start operate on two separate instances with nothing shared to
 * corrupt.
 *
 * This narrows the failure to one **residual**, deliberately left open: the
 * old job can still call [emit] once, with a stale sample from the session
 * that just ended, after [start] has already begun the new one. That is a
 * transient display value — the next tick overwrites it — not corrupted
 * state, and it is what the `isActive` check inside the loop below narrows
 * (not closes: any check-then-act here is still a race) rather than a
 * guarantee this class makes.
 *
 * @param emit called with each accumulated sample. **It must broadcast under
 *   `TunnelService`'s own lock**: `RemoteCallbackList.beginBroadcast()` throws
 *   when a broadcast is already in flight, so an unsynchronised per-second push
 *   would race state publication and take the service down.
 */
internal class TrafficSamplerLoop(
    private val scope: CoroutineScope,
    private val read: () -> TunnelCounters?,
    private val emit: (TrafficSample) -> Unit,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val sampler = TrafficSampler()
        job =
            scope.launch {
                while (isActive) {
                    read()?.let { reading ->
                        val sample = sampler.accept(reading)
                        // Narrows, does not close, the residual this class's
                        // KDoc names: a cancellation landing after accept()
                        // but before this check still slips one stale emit
                        // through. Deliberately not stronger than that.
                        if (isActive) emit(sample)
                    }
                    delay(intervalMillis)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal companion object {
        /**
         * Spec §1.5: a guess at where display smoothness and wakeup cost
         * balance, named rather than inlined so spec §9 row 5 has one place to
         * change. Do not tune it by reasoning.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000
    }
}
