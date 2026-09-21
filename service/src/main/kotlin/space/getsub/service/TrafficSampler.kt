// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.TrafficSample

/**
 * Turns hev's raw cumulative counters into a session total.
 *
 * Pure by construction: it owns no timer, opens no JNI call and touches no
 * clock, so every edge below is a JVM unit test rather than a device
 * observation. [TrafficSamplerLoop] supplies the timer.
 *
 * **Why deltas rather than the raw value** (spec §1.3): hev's counters are
 * plain `static size_t`, and we build `arm64-v8a`, `armeabi-v7a` and
 * `x86_64` (`service/build.gradle.kts`). On the 32-bit ABI, `size_t` is 32
 * bits, so a byte counter can wrap at 4 GiB and the raw value would fall back
 * to near zero mid-session; on the two 64-bit ABIs it cannot wrap at all, but
 * a retained-TUN restart on a network handoff resets the counter to whatever
 * it held before, and that reset also makes the reading fall. A falling
 * reading is therefore ambiguous — a 32-bit wrap and a genuine reset look
 * identical once the previous value is below 2³² — and there is no way to
 * tell them apart from the two numbers alone, so [delta] does not try. It
 * treats every falling reading as the start of a fresh counter epoch and
 * returns the new value as-is. Getting this wrong the other way was a real
 * defect: on a 64-bit ABI a retained-TUN restart always produces a falling
 * reading with the previous value nowhere near 2³², so any inference that
 * called that a wrap added a spurious 4 GiB per network transition.
 *
 * The first reading is a baseline, not traffic: a sampler attached to a session
 * already in flight must not report the counter's whole history as this
 * session's.
 *
 * **Not thread-safe.** Must be driven from a single coroutine or dispatcher;
 * concurrent calls to [accept] or [reset] are not synchronized.
 */
internal class TrafficSampler {
    private var previous: TunnelCounters? = null
    private var uplinkBytes = 0L
    private var downlinkBytes = 0L
    private var uplinkPackets = 0L
    private var downlinkPackets = 0L

    fun accept(reading: TunnelCounters): TrafficSample {
        val prev = previous
        if (prev != null) {
            uplinkBytes += delta(prev.uplinkBytes, reading.uplinkBytes)
            downlinkBytes += delta(prev.downlinkBytes, reading.downlinkBytes)
            uplinkPackets += delta(prev.uplinkPackets, reading.uplinkPackets)
            downlinkPackets += delta(prev.downlinkPackets, reading.downlinkPackets)
        }
        previous = reading
        return TrafficSample(
            uplinkBytes = uplinkBytes,
            downlinkBytes = downlinkBytes,
            uplinkPackets = uplinkPackets,
            downlinkPackets = downlinkPackets,
        )
    }

    fun reset() {
        previous = null
        uplinkBytes = 0L
        downlinkBytes = 0L
        uplinkPackets = 0L
        downlinkPackets = 0L
    }

    /**
     * Computes the difference between consecutive counter readings.
     *
     * A rising reading is the ordinary case: the delta is `curr - prev`.
     *
     * A falling reading (`curr < prev`) is ambiguous — it is either a 32-bit
     * wrap (`armeabi-v7a`) or a genuine reset from a retained-TUN restart
     * (any ABI, most commonly on a network handoff), and the two are
     * indistinguishable from `prev` and `curr` alone once `prev < 2³²`. This
     * always treats it as a reset and returns `curr` as-is:
     *
     * - If it really was a reset, that is exactly right.
     * - If it really was a wrap, this under-counts by `2³² - prev` — the
     *   bytes moved in the one sampling interval before the rollover
     *   ([TrafficSamplerLoop] samples at 1 s), so the loss is bounded by one
     *   second of traffic per 4 GiB crossed.
     *
     * The alternative — guessing wrap whenever `prev < 2³²` — is wrong every
     * time a retained-TUN restart fires on a 64-bit ABI, where `size_t` is 64
     * bits and the counter cannot wrap at all: it added a spurious ~4 GiB per
     * network transition, which is worse than this method's bounded
     * under-count on the ABI where a wrap can actually happen.
     */
    private fun delta(
        prev: Long,
        curr: Long,
    ): Long = if (curr >= prev) curr - prev else curr
}
