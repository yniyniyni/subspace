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
    private val sampler = TrafficSampler()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        sampler.reset()
        job =
            scope.launch {
                while (isActive) {
                    read()?.let { emit(sampler.accept(it)) }
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
