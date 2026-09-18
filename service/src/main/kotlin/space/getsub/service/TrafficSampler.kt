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
 * fall back to near zero mid-session. A falling reading is read as a wrap, not
 * as a reset — the alternative silently loses 4 GiB of a long session.
 *
 * The first reading is a baseline, not traffic: a sampler attached to a session
 * already in flight must not report the counter's whole history as this
 * session's.
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
     * A reading that fell has wrapped a 32-bit counter, not restarted.
     *
     * 64-bit ABIs never reach this branch in any realistic session, so the
     * correction is unconditionally against `2^32`.
     */
    private fun delta(
        previous: Long,
        current: Long,
    ): Long = if (current >= previous) current - previous else (UINT32_SPAN - previous) + current

    private companion object {
        const val UINT32_SPAN = 1L shl 32
    }
}
