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
 * plain `static size_t`, and we build `armeabi-v7a` and `x86`, where `size_t`
 * is 32 bits. A byte counter therefore wraps at 4 GiB and the display would
 * fall back to near zero mid-session. A falling reading distinguishes a wrap
 * from a genuine reset by examining the previous value: only values above 2³²
 * can reset (the 32-bit counter cannot have held such a value), so a falling
 * reading from there is not a wrap. The alternative to this distinction
 * silently loses 4 GiB of a long session.
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
     * Computes the difference between consecutive counter readings, handling
     * both 32-bit wraps and genuine resets on 64-bit ABIs.
     *
     * A falling reading (curr < prev) can mean either:
     *
     * - A 32-bit wrap: prev ≤ 2³², counter rolled over, add the gap to 2³² plus curr
     * - A genuine reset: prev > 2³², impossible in a 32-bit counter, so it must
     *   have been a 64-bit cumulative value before the tunnel teardown reset it to 0.
     *   Return curr as-is.
     *
     * 64-bit ABIs can accumulate counters exceeding 4 GiB in multi-hour sessions,
     * so this distinction is load-bearing on real devices.
     */
    private fun delta(
        prev: Long,
        curr: Long,
    ): Long = when {
        curr >= prev -> curr - prev
        prev >= UINT32_SPAN -> curr
        else -> (UINT32_SPAN - prev) + curr
    }

    private companion object {
        const val UINT32_SPAN = 1L shl 32
    }
}
